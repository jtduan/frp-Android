package io.github.acedroidx.frp

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.BufferedReader
import java.io.FileReader
import java.util.Collections.emptyMap
import java.net.InetSocketAddress
import java.net.Socket


class ShellService : LifecycleService() {
    companion object {
        private const val MAX_LOG_LINES = 50

        private const val FRPC_CONFIG_CELL_5G = "5g.toml"
        private const val FRPC_CONFIG_WIFI = "wifi.toml"
    }

    private val _processThreads = MutableStateFlow(mutableMapOf<FrpConfig, ShellThread>())
    val processThreads = _processThreads.asStateFlow()

    private val _logText = MutableStateFlow("")

    // 为每个配置创建一个日志流
    private val _configLogs = MutableStateFlow(mutableMapOf<FrpConfig, String>())
    val configLogs = _configLogs.asStateFlow()

    fun clearConfigLog(config: FrpConfig) {
        val logFile = config.getLogFile(this)
        if (logFile.exists()) {
            logFile.delete()
        }
        // 清空内存中的日志
        _configLogs.update { currentLogs ->
            currentLogs.toMutableMap().apply {
                put(config, "")
            }
        }
    }

    private fun logToConfig(config: FrpConfig, msg: String) {
        // 让用户能在 UI 的“配置日志”里直接看到联动启动的关键状态，方便定位 frpc 未启动原因
        Log.i("adx", "[${config.fileName}] $msg")
        appendToConfigLog(config, msg)
    }

    fun getConfigLog(config: FrpConfig): String {
        // 优先返回内存中的实时日志
        return _configLogs.value[config] ?: run {
            // 如果内存中没有，从文件读取
            val logFile = config.getLogFile(this)
            if (!logFile.exists()) {
                return ""
            }

            val lines = mutableListOf<String>()
            BufferedReader(FileReader(logFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lines.add(line!!)
                }
            }

            // 只返回最多50行
            val logContent = if (lines.size <= MAX_LOG_LINES) {
                lines.joinToString("\n")
            } else {
                lines.takeLast(MAX_LOG_LINES).joinToString("\n")
            }

            // 同时更新内存中的日志
            _configLogs.update { currentLogs ->
                currentLogs.toMutableMap().apply {
                    put(config, logContent)
                }
            }

            logContent
        }
    }

    fun getFrpVersion(type: FrpType): String {
        return try {
            val ainfo = packageManager.getApplicationInfo(
                packageName, PackageManager.GET_SHARED_LIBRARY_FILES
            )
            val command = listOf("${ainfo.nativeLibraryDir}/${type.getLibName()}", "-v")

            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()

            process.waitFor()

            // frp版本信息通常在stdout或stderr中，优先使用stdout
            if (output.isNotEmpty()) {
                // 提取版本号，通常格式为 "frpc version x.x.x" 或类似格式
                val versionRegex = Regex("""(\d+\.\d+\.\d+)""")
                val match = versionRegex.find(output)
                match?.value ?: output.take(20) // 如果找不到版本号，返回前20个字符
            } else if (errorOutput.isNotEmpty()) {
                val versionRegex = Regex("""(\d+\.\d+\.\d+)""")
                val match = versionRegex.find(errorOutput)
                match?.value ?: errorOutput.take(20)
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            Log.e("adx", "Failed to get frp version: ${e.message}")
            "Error"
        }
    }

    private fun appendToConfigLog(config: FrpConfig, logLine: String) {
        val logDir = config.getLogDir(this)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val logFile = config.getLogFile(this)
        val lines = mutableListOf<String>()

        // 读取现有日志
        if (logFile.exists()) {
            BufferedReader(FileReader(logFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lines.add(line!!)
                }
            }
        }

        // 添加新日志行
        lines.add(logLine)

        // 如果超过最大行数，删除最旧的行
        while (lines.size > MAX_LOG_LINES) {
            lines.removeAt(0)
        }

        // 写回文件
        FileWriter(logFile, false).use { writer ->
            lines.forEach { line ->
                writer.write(line + "\n")
            }
        }

        // 实时更新内存中的日志流
        val logContent = lines.joinToString("\n")
        _configLogs.update { currentLogs ->
            currentLogs.toMutableMap().apply {
                put(config, logContent)
            }
        }
    }

    // Binder given to clients
    private val binder = LocalBinder()

    private var frpcLinkMonitorJob: Job? = null

    // 仅对需要与端口联动的 frpc 配置生效：用户“期望运行”才自动拉起
    private val desiredLinkedFrpcConfigs = MutableStateFlow(setOf<FrpConfig>())
    private val pendingRestartLinkedFrpcConfigs = MutableStateFlow(setOf<FrpConfig>())

    // 给 UI 使用：联动配置（frpc_wifi / frpc_5g）即使还没真正启动 frpc 进程，也要能体现“用户已打开开关”的状态
    val desiredLinkedFrpcConfigsFlow = desiredLinkedFrpcConfigs.asStateFlow()

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder(), IBinder {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): ShellService = this@ShellService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        ensureFrpcLinkMonitor()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureFrpcLinkMonitor()

        // 处理 STOP_ALL action，不需要 frpConfig 参数
        if (intent?.action == ShellServiceAction.STOP_ALL) {
            // 停止所有正在运行的配置
            val allConfigs = _processThreads.value.keys.toList()
            for (config in allConfigs) {
                stopFrp(config)
            }

            // STOP_ALL 时直接关闭两个 socks5 service，并清理联动状态
            desiredLinkedFrpcConfigs.value = emptySet()
            pendingRestartLinkedFrpcConfigs.value = emptySet()
            stopWifiSocks5Service()
            stopFiveGSocks5Service()

            // 停止前台服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
            stopSelf()

            Toast.makeText(this, getString(R.string.toast_all_configs_stopped), Toast.LENGTH_SHORT)
                .show()

            // 关闭应用
            val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("EXIT_APP", true)
                // 检查是否需要从最近任务中排除
                val preferences = getSharedPreferences("data", MODE_PRIVATE)
                val excludeFromRecents =
                    preferences.getBoolean(PreferencesKey.EXCLUDE_FROM_RECENTS, false)
                if (excludeFromRecents) {
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
            }
            startActivity(mainActivityIntent)

            return START_NOT_STICKY
        }

        val frpConfig: ArrayList<FrpConfig>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.extras?.getParcelableArrayList(
                    IntentExtraKey.FrpConfig, FrpConfig::class.java
                )
            } else {
                @Suppress("DEPRECATION") intent?.extras?.getParcelableArrayList(IntentExtraKey.FrpConfig)
            }
        if (frpConfig == null) {
            Log.e("adx", "frpConfig is null")
            Toast.makeText(this, getString(R.string.toast_frp_config_null), Toast.LENGTH_SHORT)
                .show()
            return START_NOT_STICKY
        }
        val hideServiceToast = getSharedPreferences("data", MODE_PRIVATE).getBoolean(
            PreferencesKey.HIDE_SERVICE_TOAST, false
        )
        when (intent?.action) {
            ShellServiceAction.START -> {
                for (config in frpConfig) {
                    markLinkedFrpcDesired(config, true)
                    startFrp(config)
                }
                startForeground(1, showNotification())

                // 仅当确实启动了 frp 进程时才提示“已启动”，避免联动等待 socks5 端口导致的误导
                if (!hideServiceToast && _processThreads.value.isNotEmpty()) {
                    Toast.makeText(
                        this, getString(R.string.service_start_toast), Toast.LENGTH_SHORT
                    ).show()
                }
            }

            ShellServiceAction.STOP -> {
                for (config in frpConfig) {
                    markLinkedFrpcDesired(config, false)
                    stopFrp(config)
                }
                startForeground(1, showNotification())
                if (_processThreads.value.isEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION") stopForeground(true)
                    }
                    stopSelf()
                    if (!hideServiceToast) {
                        Toast.makeText(
                            this, getString(R.string.service_stop_toast), Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startFrp(config: FrpConfig) {
        Log.d("adx", "start config is $config")

        // frpc_5g / frpc_wifi 需要与 socks5 端口联动：端口不可用则不启动
        val requiredPort = getRequiredSocks5PortForLinkedFrpc(config)
        if (requiredPort != null) {
            logToConfig(config, "联动启动：需要等待本地端口 $requiredPort")
            // 先确保对应 socks5 service 已启动（WiFi/5G 互不影响）
            // 注意：不能在主线程用 Thread.sleep 轮询等待，否则会阻塞同进程 service 启动，导致端口永远等不到
            startLinkedSocks5Service(config)

            lifecycleScope.launch {
                val ok = waitLocalPortOpenSuspend(requiredPort)
                if (!ok) {
                    logToConfig(config, "等待端口 $requiredPort 超时：暂不启动 frpc，进入待重启队列")
                    // 端口暂不可用：加入待重启集合，交由联动监控在端口恢复后自动启动
                    pendingRestartLinkedFrpcConfigs.update { it + config }
                    return@launch
                }
                logToConfig(config, "端口 $requiredPort 已就绪，准备启动 frpc")
                startFrpAfterSocks5Ready(config, requiredPort)
            }
            return
        }

        startFrpAfterSocks5Ready(config, null)
    }

    private fun startFrpAfterSocks5Ready(config: FrpConfig, requiredPort: Int?) {

        val dir = config.getDir(this)
        val file = config.getFile(this)
        if (!file.exists()) {
            Log.w("adx", "file is not exist,service won't start")
            logToConfig(config, "启动失败：配置文件不存在 -> ${file.absolutePath}")
            Toast.makeText(this, getString(R.string.toast_config_not_exist), Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (_processThreads.value.contains(config)) {
            Log.w("adx", "frp is already running")
            logToConfig(config, "已在运行：跳过重复启动")
            // Respect user preference to hide service start/stop related toasts
            val hideServiceToast = getSharedPreferences("data", MODE_PRIVATE).getBoolean(
                PreferencesKey.HIDE_SERVICE_TOAST, false
            )
            if (!hideServiceToast) {
                Toast.makeText(this, getString(R.string.toast_frp_running), Toast.LENGTH_SHORT)
                    .show()
            }
            return
        }
        val ainfo = packageManager.getApplicationInfo(
            packageName, PackageManager.GET_SHARED_LIBRARY_FILES
        )

        // 启动前检查：确认 nativeLibraryDir 下存在对应的 frp 二进制（libfrpc.so / libfrps.so）
        // 如果 APK 未打包 jniLibs，这里会直接缺文件，ProcessBuilder 会报 error=2
        val frpBinFile = File(ainfo.nativeLibraryDir, config.type.getLibName())
        if (!frpBinFile.exists()) {
            val msg = "Missing native binary: ${frpBinFile.absolutePath}"
            Log.e("adx", msg)
            logToConfig(config, "启动失败：找不到可执行文件 -> ${frpBinFile.absolutePath}")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }

        val commandList =
            listOf(frpBinFile.absolutePath, "-c", config.fileName)
        Log.d("adx", "${dir}\n${commandList}")
        logToConfig(config, "执行命令：${commandList.joinToString(" ")}")
        try {
            val thread = runCommand(commandList, dir, config)
            _processThreads.update { it.toMutableMap().apply { put(config, thread) } }

            // 注意：这里只能说明“进程已拉起并加入列表”，不代表配置一定正确。若配置错误，后续会很快输出退出码。
            logToConfig(config, "frpc 进程已启动（已加入运行列表）")

            // 如果是联动配置且启动成功，清理待重启标记
            if (requiredPort != null) {
                pendingRestartLinkedFrpcConfigs.update { it - config }
            }
        } catch (e: Exception) {
            Log.e("adx", e.stackTraceToString())
            logToConfig(config, "启动异常：${e.javaClass.simpleName} - ${e.message}")
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun stopFrp(config: FrpConfig) {
        val thread = _processThreads.value[config]
//        thread?.interrupt()
        thread?.stopProcess()
        _processThreads.update {
            it.toMutableMap().apply { remove(config) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!_processThreads.value.isEmpty()) {
            _processThreads.value.forEach {
//                it.value.interrupt()
                it.value.stopProcess()
            }
            _processThreads.update { emptyMap() }
        }

        frpcLinkMonitorJob?.cancel()
        frpcLinkMonitorJob = null

        desiredLinkedFrpcConfigs.value = emptySet()
        pendingRestartLinkedFrpcConfigs.value = emptySet()

        stopWifiSocks5Service()
        stopFiveGSocks5Service()
    }

    private fun ensureFrpcLinkMonitor() {
        if (frpcLinkMonitorJob != null) return

        frpcLinkMonitorJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    syncLinkedFrpcWithSocks5Port()
                } catch (e: Exception) {
                    Log.w("adx", "frpc link monitor error: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    private suspend fun syncLinkedFrpcWithSocks5Port() {
        val desired = desiredLinkedFrpcConfigs.value
        if (desired.isEmpty()) return

        desired.forEach { config ->
            val requiredPort = getRequiredSocks5PortForLinkedFrpc(config) ?: return@forEach

            // 确保对应 socks5 service 在
            startLinkedSocks5Service(config)

            val portOk = isLocalPortOpenSuspend(requiredPort)
            val isRunning = _processThreads.value.containsKey(config)

            if (!portOk) {
                if (isRunning) {
                    // 端口不可用：自动关闭对应 frpc
                    pendingRestartLinkedFrpcConfigs.update { it + config }
                    stopFrp(config)
                } else {
                    pendingRestartLinkedFrpcConfigs.update { it + config }
                }
                return@forEach
            }

            // 端口可用：如果之前因为端口不可用被停止，则自动恢复
            if (!isRunning && pendingRestartLinkedFrpcConfigs.value.contains(config)) {
                startFrp(config)
            }
        }
    }

    private fun markLinkedFrpcDesired(config: FrpConfig, desired: Boolean) {
        if (getRequiredSocks5PortForLinkedFrpc(config) == null) return
        if (desired) {
            desiredLinkedFrpcConfigs.update { it + config }
        } else {
            desiredLinkedFrpcConfigs.update { it - config }
            pendingRestartLinkedFrpcConfigs.update { it - config }
            stopLinkedSocks5ServiceIfIdle(config)
        }
    }

    private fun getRequiredSocks5PortForLinkedFrpc(config: FrpConfig): Int? {
        if (config.type != FrpType.FRPC) return null
        return when (config.fileName) {
            FRPC_CONFIG_CELL_5G -> 10002
            FRPC_CONFIG_WIFI -> 10001
            else -> null
        }
    }

    private fun startLinkedSocks5Service(config: FrpConfig) {
        when (getRequiredSocks5PortForLinkedFrpc(config)) {
            10001 -> startWifiSocks5Service()
            10002 -> startFiveGSocks5Service()
        }
    }

    private fun stopLinkedSocks5ServiceIfIdle(config: FrpConfig) {
        when (getRequiredSocks5PortForLinkedFrpc(config)) {
            10001 -> {
                val hasAnyWifiDesired = desiredLinkedFrpcConfigs.value.any { getRequiredSocks5PortForLinkedFrpc(it) == 10001 }
                if (!hasAnyWifiDesired) stopWifiSocks5Service()
            }

            10002 -> {
                val hasAnyCellDesired = desiredLinkedFrpcConfigs.value.any { getRequiredSocks5PortForLinkedFrpc(it) == 10002 }
                if (!hasAnyCellDesired) stopFiveGSocks5Service()
            }
        }
    }

    private suspend fun waitLocalPortOpenSuspend(port: Int): Boolean {
        // 启动 socks5 后给一点时间让端口真正开始监听
        repeat(40) {
            if (isLocalPortOpenSuspend(port)) return true
            delay(200)
        }
        return false
    }

    private fun startWifiSocks5Service() {
        val intent = Intent(this, WifiSocks5Service::class.java).apply {
            action = WifiSocks5Service.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopWifiSocks5Service() {
        val intent = Intent(this, WifiSocks5Service::class.java).apply {
            action = WifiSocks5Service.ACTION_STOP
        }
        startService(intent)
    }

    private fun startFiveGSocks5Service() {
        val intent = Intent(this, FiveGSocks5Service::class.java).apply {
            action = FiveGSocks5Service.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopFiveGSocks5Service() {
        val intent = Intent(this, FiveGSocks5Service::class.java).apply {
            action = FiveGSocks5Service.ACTION_STOP
        }
        startService(intent)
    }

    private suspend fun isLocalPortOpenSuspend(port: Int): Boolean {
        // 端口探测必须在 IO 线程执行；否则在部分系统上会触发 NetworkOnMainThreadException，导致永远探测失败
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun runCommand(command: List<String>, dir: File, config: FrpConfig): ShellThread {
        val processThread = ShellThread(command, dir) { logLine ->
            _logText.value += logLine + "\n"
            appendToConfigLog(config, logLine)
        }
        processThread.start()
        return processThread
    }

    private fun showNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                // 检查是否需要从最近任务中排除
                val preferences = getSharedPreferences("data", MODE_PRIVATE)
                val excludeFromRecents =
                    preferences.getBoolean(PreferencesKey.EXCLUDE_FROM_RECENTS, false)
                if (excludeFromRecents) {
                    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        // 创建停止所有配置的 PendingIntent
        val stopAllIntent = Intent(this, ShellService::class.java).apply {
            action = ShellServiceAction.STOP_ALL
        }
        val stopAllPendingIntent = PendingIntent.getService(
            this, 0, stopAllIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "shell_bg")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.frp_notification_title)).setContentText(
                getString(
                    R.string.frp_notification_content, _processThreads.value.size
                )
            )
            //.setTicker("test")
            .setContentIntent(pendingIntent).addAction(
                R.drawable.ic_baseline_delete_24, getString(R.string.stop), stopAllPendingIntent
            )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } else {
            notification.build()
        }
    }
}