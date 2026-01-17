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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        private const val SOCKS5_HEALTH_CHECK_INTERVAL_MS = 30_000L
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

    private var socks5MonitorJob: Job? = null

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
        ensureSocks5Monitor()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        ensureSocks5Monitor()

        // 处理 STOP_ALL action，不需要 frpConfig 参数
        if (intent?.action == ShellServiceAction.STOP_ALL) {
            // 停止所有正在运行的配置
            val allConfigs = _processThreads.value.keys.toList()
            for (config in allConfigs) {
                stopFrp(config)
            }

            syncSocks5WithFrpc()

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
                    startFrp(config)
                }

                syncSocks5WithFrpc()
                if (!hideServiceToast) {
                    Toast.makeText(
                        this, getString(R.string.service_start_toast), Toast.LENGTH_SHORT
                    ).show()
                }
                startForeground(1, showNotification())
            }

            ShellServiceAction.STOP -> {
                for (config in frpConfig) {
                    stopFrp(config)
                }

                syncSocks5WithFrpc()
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
        val dir = config.getDir(this)
        val file = config.getFile(this)
        if (!file.exists()) {
            Log.w("adx", "file is not exist,service won't start")
            Toast.makeText(this, getString(R.string.toast_config_not_exist), Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (_processThreads.value.contains(config)) {
            Log.w("adx", "frp is already running")
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
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }

        val commandList =
            listOf(frpBinFile.absolutePath, "-c", config.fileName)
        Log.d("adx", "${dir}\n${commandList}")
        try {
            val thread = runCommand(commandList, dir, config)
            _processThreads.update { it.toMutableMap().apply { put(config, thread) } }
            syncSocks5WithFrpc()
        } catch (e: Exception) {
            Log.e("adx", e.stackTraceToString())
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
        syncSocks5WithFrpc()
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

        socks5MonitorJob?.cancel()
        socks5MonitorJob = null
        stopSocks5Service()
    }

    private fun ensureSocks5Monitor() {
        if (socks5MonitorJob != null) return

        socks5MonitorJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    syncSocks5WithFrpc()
                } catch (e: Exception) {
                    Log.w("adx", "socks5 monitor error: ${e.message}")
                }
                delay(SOCKS5_HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun syncSocks5WithFrpc() {
        val hasFrpcRunning = _processThreads.value.keys.any { it.type == FrpType.FRPC }
        if (hasFrpcRunning) {
            // 启动 frpc 时确保 socks5 服务可用；不可用则拉起
            if (!isSocks5ServiceHealthy()) {
                startSocks5Service()
            }
        } else {
            // 没有任何 frpc 时关闭 socks5（与端口转发保持一致）
            stopSocks5Service()
        }
    }

    private fun startSocks5Service() {
        val intent = Intent(this, Socks5Service::class.java).apply {
            action = Socks5Service.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSocks5Service() {
        val intent = Intent(this, Socks5Service::class.java).apply {
            action = Socks5Service.ACTION_STOP
        }
        startService(intent)
    }

    private fun isSocks5ServiceHealthy(): Boolean {
        if (!Socks5Service.isRunning) return false
        // 同时检查两个监听端口，避免 service 进程在但监听线程已挂
        return isLocalPortOpen(10001) && isLocalPortOpen(10002)
    }

    private fun isLocalPortOpen(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 500)
            }
            true
        } catch (_: Exception) {
            false
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