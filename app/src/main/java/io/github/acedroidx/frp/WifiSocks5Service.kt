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

class WifiSocks5Service : LifecycleService() {
    companion object {
        private const val TAG = "WifiSocks5Service"

        @Volatile
        var isRunning: Boolean = false

        const val ACTION_START = "io.github.acedroidx.frp.wifi_socks5.START"
        const val ACTION_STOP = "io.github.acedroidx.frp.wifi_socks5.STOP"

        private const val NOTIFICATION_ID = 20001

        const val WIFI_PORT = 10001

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

    private val wifiNetworkRef = AtomicReference<Network?>(null)

    @Volatile
    private var wifiValidated: Boolean = false

    private val serverLock = Any()

    private var connectivityManager: ConnectivityManager? = null

    @Volatile
    private var wifiCallbackRegistered: Boolean = false

    private var wifiServer: ServerSocket? = null
    private var wifiServerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureBGNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止指令，准备关闭 10001 监听")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                Log.i(TAG, "收到启动指令，开始前台服务并监听 WiFi VALIDATED 状态")
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
        // 兼容从后台直接拉起 Service 的场景：必须保证前台通知渠道已创建
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

        val stopIntent = Intent(this, WifiSocks5Service::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "shell_bg")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.socks5_notification_title))
            .setContentText("WiFi SOCKS5: $WIFI_PORT")
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_baseline_delete_24, getString(R.string.stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateServer() {
        Log.i(TAG, "updateServer: wifiValidated=$wifiValidated")
        setServerEnabled(wifiValidated)
    }

    private fun setServerEnabled(enabled: Boolean) {
        synchronized(serverLock) {
            if (enabled) {
                if (wifiServer != null) return
                // 防止短时间内多次 updateServer() 触发并发启动，导致 bind 竞争出现 EADDRINUSE
                if (wifiServerJob?.isActive == true) return
                wifiServerJob = serviceScope.launch {
                    // 启动阶段可能会被多次触发，这里做有限次重试，避免偶发的端口占用导致永远起不来
                    var attempt = 0
                    while (serviceScope.isActive) {
                        // WiFi 状态已变化则退出
                        if (!wifiValidated) break

                        attempt++
                        try {
                            // 仅允许本机访问：绑定到 loopback，避免局域网其他设备连接
                            // 显式绑定 IPv4 localhost，避免某些机型 loopback 解析为 ::1 导致 127.0.0.1 探测失败
                            val server = ServerSocket(WIFI_PORT, 50, InetAddress.getByName("127.0.0.1"))
                            synchronized(serverLock) {
                                wifiServer = server
                            }
                            Log.i(TAG, "已绑定本地监听：${server.inetAddress} : ${server.localPort}")
                            Log.i(TAG, "SOCKS5 WiFi server listening on $WIFI_PORT")
                            acceptLoop(server)
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "WiFi server failed(attempt=$attempt): ${e.message}")
                            synchronized(serverLock) {
                                try {
                                    wifiServer?.close()
                                } catch (_: Exception) {
                                }
                                wifiServer = null
                            }
                            // EADDRINUSE 等临时错误：延迟后重试；避免无限空转
                            if (attempt >= 10) break
                            delay(300)
                        }
                    }
                }
            } else {
                Log.i(TAG, "WiFi 未验证或丢失，关闭 10001 监听")
                try {
                    wifiServer?.close()
                } catch (_: Exception) {
                }
                wifiServer = null
                wifiServerJob?.cancel()
                wifiServerJob = null
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

            // 1) 握手：选择无认证
            if (!negotiate(input, output)) return

            // 2) CONNECT 请求
            val request = readRequest(input) ?: return
            if (request.command != CMD_CONNECT) {
                writeReply(output, REP_COMMAND_NOT_SUPPORTED)
                return
            }

            val network = waitForWifiNetwork()
            if (network == null) {
                writeReply(output, REP_GENERAL_FAILURE)
                return
            }

            val remote = try {
                val s = network.socketFactory.createSocket(request.host, request.port)
                s.tcpNoDelay = true
                s
            } catch (e: Exception) {
                Log.w(TAG, "Connect remote failed: ${e.message}")
                writeReply(output, REP_GENERAL_FAILURE)
                return
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
            // ignore
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
        val port: Int
    )

    private fun readRequest(input: InputStream): SocksRequest? {
        val ver = input.readByteOrThrow()
        if (ver != SOCKS_VERSION_5) return null

        val cmd = input.readByteOrThrow()
        input.readByteOrThrow() // RSV
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

        return SocksRequest(command = cmd, host = host, port = port)
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

        if (wifiCallbackRegistered) {
            Log.i(TAG, "WiFi NetworkCallback 已注册，跳过重复注册")
            refreshValidatedStateFromSystem()
            return
        }

        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            cm.registerNetworkCallback(wifiRequest, wifiCallback)
            wifiCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback(wifi) failed: ${e.javaClass.simpleName}", e)
        }

        refreshValidatedStateFromSystem()
    }

    private fun refreshValidatedStateFromSystem() {
        val cm = connectivityManager ?: return
        try {
            var ok = false
            cm.allNetworks.forEach { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@forEach
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!validated) return@forEach
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    ok = true
                    wifiNetworkRef.set(n)
                }
            }
            wifiValidated = ok
            Log.i(TAG, "系统刷新 WiFi VALIDATED=$wifiValidated")
        } catch (e: Exception) {
            Log.w(TAG, "refreshValidatedStateFromSystem failed: ${e.message}")
        }
        updateServer()
    }

    private fun stopNetworkTracking() {
        val cm = connectivityManager
        if (cm != null && wifiCallbackRegistered) {
            try {
                cm.unregisterNetworkCallback(wifiCallback)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback(wifi) failed: ${e.javaClass.simpleName}", e)
            } finally {
                wifiCallbackRegistered = false
            }
        }
        wifiNetworkRef.set(null)
        wifiValidated = false
        connectivityManager = null
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworkRef.set(network)
            Log.i(TAG, "WiFi network available")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            wifiValidated = validated
            Log.i(TAG, "WiFi capabilities changed: validated=$validated")
            if (!validated) {
                val current = wifiNetworkRef.get()
                if (current == network) {
                    wifiNetworkRef.set(null)
                }
            } else {
                wifiNetworkRef.set(network)
            }
            updateServer()
        }

        override fun onLost(network: Network) {
            val current = wifiNetworkRef.get()
            if (current == network) {
                wifiNetworkRef.set(null)
            }
            wifiValidated = false
            Log.i(TAG, "WiFi network lost")
            updateServer()
        }
    }

    private suspend fun waitForWifiNetwork(): Network? {
        repeat(50) {
            val n = wifiNetworkRef.get()
            if (n != null) return n
            delay(200)
        }
        return null
    }

    private fun stopServer() {
        synchronized(serverLock) {
            try {
                wifiServer?.close()
            } catch (_: Exception) {
            }
            wifiServer = null
            wifiServerJob?.cancel()
            wifiServerJob = null
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
