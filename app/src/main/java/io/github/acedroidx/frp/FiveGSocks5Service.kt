package io.github.acedroidx.frp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

class FiveGSocks5Service : LifecycleService() {
    companion object {
        private const val TAG = "FiveGSocks5Service"

        private const val CELL_VALIDATED_GRACE_MS = 5_000L
        private const val CELL_DATA_STATE_GRACE_MS = 5_000L

        @Volatile
        var isRunning: Boolean = false

        const val ACTION_START = "io.github.acedroidx.frp.5g_socks5.START"
        const val ACTION_STOP = "io.github.acedroidx.frp.5g_socks5.STOP"

        private const val NOTIFICATION_ID = 20002

        const val CELL_PORT = 10002

        // SOCKS5 constants
        private const val SOCKS_VERSION_5: Byte = 0x05
        private const val METHOD_NO_AUTH: Byte = 0x00
        private const val METHOD_NO_ACCEPTABLE: Byte = (0xFF).toByte()

        private const val CMD_CONNECT: Byte = 0x01

        private const val ATYP_IPV4: Byte = 0x01
        private const val ATYP_DOMAIN: Byte = 0x03
        private const val ATYP_IPV6: Byte = 0x04

        private const val REP_SUCCEEDED: Byte = 0x00
        private const val REP_GENERAL_FAILURE: Byte = 0x01
        private const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
    }

    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val cellNetworkRef = AtomicReference<Network?>(null)

    @Volatile
    private var cellValidated: Boolean = false

    @Volatile
    private var lastCellValidatedAt: Long = 0L

    @Volatile
    private var lastMobileDataConnectedAt: Long = 0L

    private val serverLock = Any()

    private var connectivityManager: ConnectivityManager? = null

    @Volatile
    private var cellCallbackRegistered: Boolean = false

    private var cellServer: ServerSocket? = null
    private var cellServerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureBGNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止指令，准备关闭 10002 监听")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                Log.i(TAG, "收到启动指令，开始前台服务并监听蜂窝 VALIDATED 状态")
                startForeground(NOTIFICATION_ID, buildNotification())
                startNetworkTracking()
                updateServer()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        stopNetworkTracking()
        serviceScope.cancel()
        isRunning = false
    }

    private fun ensureBGNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel("shell_bg", name, importance).apply {
                description = descriptionText
            }
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppPendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val stopIntent = Intent(this, FiveGSocks5Service::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "shell_bg")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.socks5_notification_title))
            .setContentText("Cellular SOCKS5: ${CELL_PORT}")
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_baseline_delete_24, getString(R.string.stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateServer() {
        // 仅在蜂窝网络已验证且确实拿到蜂窝 Network 时才启用本地监听
        // 部分机型可能出现 cellValidated 误判为 true 的情况，用 networkRef 进一步收紧条件
        // 进一步收紧：必须移动数据处于连接状态，否则即使系统残留蜂窝 Network 也不应开启 10002
        val now = System.currentTimeMillis()
        val hasCellNetwork = cellNetworkRef.get() != null
        // WiFi 切换时蜂窝 VALIDATED 可能短暂抖动为 false；如果服务已在运行，则给一个宽限期
        val validatedOk = cellValidated || (cellServer != null && now - lastCellValidatedAt <= CELL_VALIDATED_GRACE_MS)
        val dataConnected = isMobileDataConnectedInternal()
        if (dataConnected) {
            lastMobileDataConnectedAt = now
        }
        // dataState 在网络切换瞬间也可能抖动，给一个宽限期，避免误关 10002
        val dataOk =
            dataConnected || (cellServer != null && now - lastMobileDataConnectedAt <= CELL_DATA_STATE_GRACE_MS)

        val enabled = hasCellNetwork && dataOk && validatedOk
        Log.i(
            TAG,
            "updateServer: cellValidated=$cellValidated, hasCellNetwork=$hasCellNetwork, validatedOk=$validatedOk, dataOk=$dataOk"
        )
        setServerEnabled(enabled)
    }

    private fun isMobileDataConnectedInternal(): Boolean {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            // 这里不需要读取任何敏感信息：仅用系统提供的数据连接状态做兜底判定
            tm.dataState == TelephonyManager.DATA_CONNECTED
        } catch (_: Exception) {
            false
        }
    }

    private fun isMobileDataConnected(): Boolean {
        // 对外保留原方法名，便于后续阅读；内部实现统一走 internal
        return isMobileDataConnectedInternal()
    }

    private fun setServerEnabled(enabled: Boolean) {
        synchronized(serverLock) {
            if (enabled) {
                if (cellServer != null) return
                // 防止短时间内多次 updateServer() 触发并发启动，导致 bind 竞争出现 EADDRINUSE
                if (cellServerJob?.isActive == true) return
                cellServerJob = serviceScope.launch {
                    // 启动阶段可能会被多次触发，这里做有限次重试，避免偶发的端口占用导致永远起不来
                    var attempt = 0
                    while (serviceScope.isActive) {
                        // 蜂窝状态已变化则退出
                        if (!cellValidated) break

                        attempt++
                        try {
                            // 仅允许本机访问：绑定到 loopback，避免局域网其他设备连接
                            // 显式绑定 IPv4 localhost，避免某些机型 loopback 解析为 ::1 导致 127.0.0.1 探测失败
                            val server = ServerSocket(CELL_PORT, 50, InetAddress.getByName("127.0.0.1"))
                            synchronized(serverLock) {
                                cellServer = server
                            }
                            Log.i(TAG, "已绑定本地监听：${server.inetAddress} : ${server.localPort}")
                            Log.i(TAG, "SOCKS5 Cellular server listening on $CELL_PORT")
                            acceptLoop(server)
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "Cellular server failed(attempt=$attempt): ${e.message}")
                            synchronized(serverLock) {
                                try {
                                    cellServer?.close()
                                } catch (_: Exception) {
                                }
                                cellServer = null
                            }
                            // EADDRINUSE 等临时错误：延迟后重试；避免无限空转
                            if (attempt >= 10) break
                            delay(300)
                        }
                    }
                }
            } else {
                if (cellServer != null) {
                    Log.i(TAG, "蜂窝未验证或丢失，关闭 10002 监听")
                }
                try {
                    cellServer?.close()
                } catch (_: Exception) {
                }
                cellServer = null
                cellServerJob?.cancel()
                cellServerJob = null
            }
        }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (serviceScope.isActive) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break
            }
            serviceScope.launch {
                client.use {
                    handleClient(client)
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000

            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiate(input, output)) return

            val request = readRequest(input) ?: return
            if (request.command != CMD_CONNECT) {
                writeReply(output, REP_COMMAND_NOT_SUPPORTED)
                return
            }

            suspend fun getCellNetworkWithRefresh(): Network? {
                val n1 = waitForCellNetwork()
                if (n1 != null) return n1
                // WiFi 切换期间蜂窝 Network 可能在重建，主动刷新一次再等
                refreshValidatedStateFromSystem()
                return waitForCellNetwork()
            }

            fun connectRemoteViaNetwork(network: Network): Socket {
                val addr = if (request.isDomain) {
                    // socks5h: 域名需要由代理侧解析；并且要保证走蜂窝网络的 DNS
                    // 这里使用 network.getAllByName 来强制在指定 Network 上解析
                    val addrs = network.getAllByName(request.host)
                    addrs.firstOrNull() ?: throw IOException("DNS resolve empty")
                } else {
                    // IP 直连：不会触发外部 DNS
                    InetAddress.getByName(request.host)
                }
                val s = network.socketFactory.createSocket(addr, request.port)
                s.tcpNoDelay = true
                return s
            }

            val network = getCellNetworkWithRefresh()
            if (network == null) {
                writeReply(output, REP_GENERAL_FAILURE)
                return
            }

            val remote = try {
                connectRemoteViaNetwork(network)
            } catch (e: Exception) {
                // 连接失败时，可能是 networkRef 指向旧 Network；刷新并重试一次
                Log.w(
                    TAG,
                    "Connect remote failed(first): host=${request.host}, port=${request.port}, isDomain=${request.isDomain}, err=${e.javaClass.simpleName}:${e.message}"
                )
                refreshValidatedStateFromSystem()
                val n2 = waitForCellNetwork()
                if (n2 == null) {
                    writeReply(output, REP_GENERAL_FAILURE)
                    return
                }
                try {
                    connectRemoteViaNetwork(n2)
                } catch (e2: Exception) {
                    Log.w(
                        TAG,
                        "Connect remote failed(second): host=${request.host}, port=${request.port}, isDomain=${request.isDomain}, err=${e2.javaClass.simpleName}:${e2.message}"
                    )
                    writeReply(output, REP_GENERAL_FAILURE)
                    return
                }
            }

            remote.use {
                writeReply(output, REP_SUCCEEDED)

                val remoteIn = remote.getInputStream()
                val remoteOut = remote.getOutputStream()

                val up = serviceScope.launch { pipe(input, remoteOut) }
                val down = serviceScope.launch { pipe(remoteIn, output) }

                try {
                    up.join()
                } finally {
                    down.cancel()
                    remote.close()
                }
            }
        } catch (_: CancellationException) {
        } catch (_: EOFException) {
            // 端口探测/客户端未按 SOCKS5 协议发送数据就断开：属于正常情况，不需要打印错误日志
        } catch (_: SocketException) {
            // 客户端或服务端主动断开：属于正常情况
        } catch (e: Exception) {
            Log.d(TAG, "Client handler error: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    private fun negotiate(input: InputStream, output: OutputStream): Boolean {
        val ver = input.readByteOrThrow()
        if (ver != SOCKS_VERSION_5) return false

        val nMethods = input.readByteOrThrow().toInt() and 0xFF
        val methods = ByteArray(nMethods)
        input.readFullyOrThrow(methods)

        val ok = methods.any { it == METHOD_NO_AUTH }
        output.write(byteArrayOf(SOCKS_VERSION_5, if (ok) METHOD_NO_AUTH else METHOD_NO_ACCEPTABLE))
        output.flush()
        return ok
    }

    private data class SocksRequest(
        val command: Byte,
        val host: String,
        val port: Int,
        val isDomain: Boolean
    )

    private fun readRequest(input: InputStream): SocksRequest? {
        val ver = input.readByteOrThrow()
        if (ver != SOCKS_VERSION_5) return null

        val cmd = input.readByteOrThrow()
        input.readByteOrThrow()
        val atyp = input.readByteOrThrow()

        val host = when (atyp) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                input.readFullyOrThrow(addr)
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }

            ATYP_IPV6 -> {
                val addr = ByteArray(16)
                input.readFullyOrThrow(addr)
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }

            ATYP_DOMAIN -> {
                val len = input.readByteOrThrow().toInt() and 0xFF
                val domain = ByteArray(len)
                input.readFullyOrThrow(domain)
                String(domain)
            }

            else -> return null
        }

        val portHi = input.readByteOrThrow().toInt() and 0xFF
        val portLo = input.readByteOrThrow().toInt() and 0xFF
        val port = (portHi shl 8) or portLo

        return SocksRequest(command = cmd, host = host, port = port, isDomain = atyp == ATYP_DOMAIN)
    }

    private fun writeReply(output: OutputStream, rep: Byte) {
        val reply = byteArrayOf(
            SOCKS_VERSION_5,
            rep,
            0x00,
            ATYP_IPV4,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
        output.write(reply)
        output.flush()
    }

    private suspend fun pipe(input: InputStream, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val buf = ByteArray(16 * 1024)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: SocketException) {
            } catch (_: EOFException) {
            } catch (_: IOException) {
            }
        }
    }

    private fun startNetworkTracking() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        if (cellCallbackRegistered) {
            Log.i(TAG, "Cellular NetworkCallback 已注册，跳过重复注册")
            refreshValidatedStateFromSystem()
            return
        }

        val cellRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            // 必须具备可上网能力，避免 IMS/信令等蜂窝网络被误当成“可用数据网络”
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // 避免被运营商/系统标记为受限的蜂窝网络（某些场景移动数据关闭仍可能存在）
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        try {
            // 主动请求蜂窝网络：WiFi 开启后系统可能会回收蜂窝链路，导致虽然本地监听存在但无法通过蜂窝出站连接。
            // requestNetwork 会让系统尽可能提供并维持一个满足条件的蜂窝 Network。
            cm.requestNetwork(cellRequest, cellCallback)
            cellCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback(cell) failed: ${e.javaClass.simpleName}", e)
        }

        refreshValidatedStateFromSystem()
    }

    private fun refreshValidatedStateFromSystem() {
        val cm = connectivityManager ?: return
        try {
            // WiFi 开启时，activeNetwork 可能是 WiFi，但蜂窝数据网络仍然可用。
            // 这里从系统所有网络中选择“具备上网能力且已验证”的蜂窝网络。
            // 同时要求移动数据处于连接状态，避免关闭移动数据仍残留蜂窝 Network 导致误启。
            val now = System.currentTimeMillis()
            val dataConnected = isMobileDataConnectedInternal()
            if (dataConnected) {
                lastMobileDataConnectedAt = now
            }
            val dataOk =
                dataConnected || (cellServer != null && now - lastMobileDataConnectedAt <= CELL_DATA_STATE_GRACE_MS)
            if (!dataOk) {
                cellValidated = false
                cellNetworkRef.set(null)
                Log.i(TAG, "移动数据未连接，跳过蜂窝网络选择")
                Log.i(TAG, "系统刷新蜂窝 VALIDATED=$cellValidated")
                updateServer()
                return
            }
            var selected: Network? = null
            var selectedCaps: NetworkCapabilities? = null
            var selectedValidated: Boolean = false
            cm.allNetworks.forEach { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@forEach
                // 先找“可上网蜂窝候选网络”，VALIDATED 用于质量判断，不作为唯一开关
                val candidate = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                if (candidate) {
                    val validatedCandidate = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    // 优先选择已验证的蜂窝网络；否则先保留一个候选，继续扫描是否能找到 validated 的
                    if (selected == null || (!selectedValidated && validatedCandidate)) {
                        selected = n
                        selectedCaps = caps
                        selectedValidated = validatedCandidate
                    }
                }
            }

            val ok = selected != null
            val validated = ok && (selectedCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            cellValidated = validated
            if (ok) {
                cellNetworkRef.set(selected)
                if (validated) {
                    lastCellValidatedAt = System.currentTimeMillis()
                }
                Log.i(TAG, "选中蜂窝网络: $selectedCaps")
            } else {
                // WiFi 切换时可能短暂找不到蜂窝网络，宽限期内先保留现有引用，避免误关 10002
                val keep = cellServer != null && now - lastCellValidatedAt <= CELL_VALIDATED_GRACE_MS
                if (!keep) {
                    cellNetworkRef.set(null)
                }
            }
            Log.i(TAG, "系统刷新蜂窝 VALIDATED=$cellValidated")
        } catch (e: Exception) {
            Log.w(TAG, "refreshValidatedStateFromSystem failed: ${e.message}")
        }
        updateServer()
    }

    private fun stopNetworkTracking() {
        val cm = connectivityManager
        if (cm != null && cellCallbackRegistered) {
            try {
                cm.unregisterNetworkCallback(cellCallback)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback(cell) failed: ${e.javaClass.simpleName}", e)
            } finally {
                cellCallbackRegistered = false
            }
        }
        cellNetworkRef.set(null)
        cellValidated = false
        connectivityManager = null
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 仅记录事件；真正启用由 onCapabilitiesChanged/refreshValidatedStateFromSystem 决定
            Log.i(TAG, "Cellular network available")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // WiFi 开启时蜂窝仍可能可用：不依赖 activeNetwork，仅按能力判断
            val candidate = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            val validated = candidate && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (candidate) {
                cellNetworkRef.set(network)
                cellValidated = validated
                if (validated) {
                    lastCellValidatedAt = System.currentTimeMillis()
                }
                Log.i(TAG, "Cellular capabilities changed: candidate=true, validated=$validated")
            } else {
                val current = cellNetworkRef.get()
                if (current == network) {
                    cellNetworkRef.set(null)
                    cellValidated = false
                }
                Log.i(TAG, "Cellular capabilities changed: candidate=false")
            }
            updateServer()
        }

        override fun onLost(network: Network) {
            val current = cellNetworkRef.get()
            val now = System.currentTimeMillis()
            Log.i(TAG, "Cellular network lost")

            // WiFi 开关/网络切换期间，蜂窝可能会短暂触发 onLost，但随后会重建新的蜂窝 Network。
            // 这里不立即清空 networkRef，避免立刻关闭 10002；改为延迟刷新并在宽限期后再决定是否关闭。
            if (current == network) {
                cellValidated = false

                serviceScope.launch {
                    delay(1000)
                    refreshValidatedStateFromSystem()
                }

                // 宽限期过后仍找不到可用蜂窝网络才真正清理
                serviceScope.launch {
                    delay(CELL_VALIDATED_GRACE_MS)
                    val stillSame = cellNetworkRef.get() == network
                    val dataOk =
                        isMobileDataConnectedInternal() || (cellServer != null && System.currentTimeMillis() - lastMobileDataConnectedAt <= CELL_DATA_STATE_GRACE_MS)
                    if (stillSame && dataOk && cellServer != null) {
                        // 仍在宽限期内运行，保持现状，等待下一轮刷新
                        return@launch
                    }
                    if (stillSame) {
                        cellNetworkRef.set(null)
                    }
                    updateServer()
                }
            } else {
                updateServer()
            }
        }
    }

    private suspend fun waitForCellNetwork(): Network? {
        repeat(50) {
            val n = cellNetworkRef.get()
            if (n != null) return n
            delay(200)
        }
        return null
    }

    private fun stopServer() {
        synchronized(serverLock) {
            try {
                cellServer?.close()
            } catch (_: Exception) {
            }
            cellServer = null
            cellServerJob?.cancel()
            cellServerJob = null
        }
    }
}

private fun InputStream.readByteOrThrow(): Byte {
    val v = this.read()
    if (v < 0) throw EOFException()
    return v.toByte()
}

private fun InputStream.readFullyOrThrow(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = this.read(buf, off, buf.size - off)
        if (n < 0) throw EOFException()
        off += n
    }
}
