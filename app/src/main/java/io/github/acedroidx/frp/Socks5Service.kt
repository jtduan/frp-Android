package io.github.acedroidx.frp

import android.app.Notification
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

class Socks5Service : LifecycleService() {
    companion object {
        private const val TAG = "Socks5Service"

        @Volatile
        var isRunning: Boolean = false

        const val ACTION_START = "io.github.acedroidx.frp.socks5.START"
        const val ACTION_STOP = "io.github.acedroidx.frp.socks5.STOP"

        private const val NOTIFICATION_ID = 20001

        private const val WIFI_PORT = 10001
        private const val CELL_PORT = 10002

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
        private const val REP_ADDR_TYPE_NOT_SUPPORTED: Byte = 0x08
    }

    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val wifiNetworkRef = AtomicReference<Network?>(null)
    private val cellNetworkRef = AtomicReference<Network?>(null)

    private var wifiServer: ServerSocket? = null
    private var cellServer: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startNetworkTracking()
                startServersIfNeeded()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServers()
        stopNetworkTracking()
        serviceScope.cancel()
        isRunning = false
    }

    private fun buildNotification(): Notification {
        val openAppPendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val stopIntent = Intent(this, Socks5Service::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "shell_bg")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.socks5_notification_title))
            .setContentText(getString(R.string.socks5_notification_content, WIFI_PORT, CELL_PORT))
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_baseline_delete_24, getString(R.string.stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startServersIfNeeded() {
        if (wifiServer != null || cellServer != null) return

        serviceScope.launch {
            try {
                wifiServer = ServerSocket(WIFI_PORT)
                Log.i(TAG, "SOCKS5 WiFi server listening on $WIFI_PORT")
                acceptLoop(wifiServer!!, OutboundType.WIFI)
            } catch (e: Exception) {
                Log.e(TAG, "WiFi server failed: ${e.message}")
            }
        }

        serviceScope.launch {
            try {
                cellServer = ServerSocket(CELL_PORT)
                Log.i(TAG, "SOCKS5 Cellular server listening on $CELL_PORT")
                acceptLoop(cellServer!!, OutboundType.CELLULAR)
            } catch (e: Exception) {
                Log.e(TAG, "Cellular server failed: ${e.message}")
            }
        }
    }

    private suspend fun acceptLoop(server: ServerSocket, outboundType: OutboundType) {
        while (serviceScope.isActive) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break
            }
            serviceScope.launch {
                client.use {
                    handleClient(client, outboundType)
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket, outboundType: OutboundType) {
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

            val network = waitForNetwork(outboundType)
            if (network == null) {
                // 没有对应网络时直接失败，避免错误走默认网络
                writeReply(output, REP_GENERAL_FAILURE)
                return
            }

            val remote = try {
                // 关键：使用指定 Network 的 socketFactory 创建出站连接
                val s = network.socketFactory.createSocket(request.host, request.port)
                s.tcpNoDelay = true
                s
            } catch (e: Exception) {
                Log.w(TAG, "Connect remote failed: ${e.message}")
                writeReply(output, REP_GENERAL_FAILURE)
                return
            }

            remote.use {
                // RFC1928：成功后返回 BND.ADDR/BND.PORT，这里填 0.0.0.0:0
                writeReply(output, REP_SUCCEEDED)

                val remoteIn = remote.getInputStream()
                val remoteOut = remote.getOutputStream()

                // 3) 双向转发
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

            else -> {
                return null
            }
        }

        val portHi = input.readByteOrThrow().toInt() and 0xFF
        val portLo = input.readByteOrThrow().toInt() and 0xFF
        val port = (portHi shl 8) or portLo

        return SocksRequest(command = cmd, host = host, port = port)
    }

    private fun writeReply(output: OutputStream, rep: Byte) {
        // 0.0.0.0:0
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
                // ignore
            } catch (_: EOFException) {
                // ignore
            } catch (_: IOException) {
                // ignore
            }
        }
    }

    private enum class OutboundType {
        WIFI, CELLULAR
    }

    private fun startNetworkTracking() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cellRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        // 注意：requestNetwork() 在部分 ROM/版本上会触发权限校验（CHANGE_NETWORK_STATE/WRITE_SETTINGS）导致崩溃。
        // 这里我们只需要监听网络可用性，不需要主动请求系统拉起网络，因此使用 registerNetworkCallback()。
        try {
            cm.registerNetworkCallback(wifiRequest, wifiCallback)
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback(wifi) failed: ${e.message}")
        }
        try {
            cm.registerNetworkCallback(cellRequest, cellCallback)
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback(cell) failed: ${e.message}")
        }
    }

    private fun stopNetworkTracking() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(wifiCallback)
        } catch (_: Exception) {
        }
        try {
            cm.unregisterNetworkCallback(cellCallback)
        } catch (_: Exception) {
        }
        wifiNetworkRef.set(null)
        cellNetworkRef.set(null)
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworkRef.set(network)
            Log.i(TAG, "WiFi network available")
        }

        override fun onLost(network: Network) {
            val current = wifiNetworkRef.get()
            if (current == network) {
                wifiNetworkRef.set(null)
            }
            Log.i(TAG, "WiFi network lost")
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellNetworkRef.set(network)
            Log.i(TAG, "Cellular network available")
        }

        override fun onLost(network: Network) {
            val current = cellNetworkRef.get()
            if (current == network) {
                cellNetworkRef.set(null)
            }
            Log.i(TAG, "Cellular network lost")
        }
    }

    private suspend fun waitForNetwork(type: OutboundType): Network? {
        // 简单等待网络就绪，避免在网络切换时拿到 null。
        repeat(50) {
            val n = when (type) {
                OutboundType.WIFI -> wifiNetworkRef.get()
                OutboundType.CELLULAR -> cellNetworkRef.get()
            }
            if (n != null) return n
            delay(200)
        }
        return null
    }

    private fun stopServers() {
        try {
            wifiServer?.close()
        } catch (_: Exception) {
        }
        try {
            cellServer?.close()
        } catch (_: Exception) {
        }
        wifiServer = null
        cellServer = null
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
