// ... existing code ...
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android VpnService that binds a local TUN interface and prepares VLESS node
 * configuration (via [VlessNodeConfig]) for an underlying Xray/tunnel core.
 */
class AegisVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var activeNode: VlessNodeConfig? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopVpnTunnel()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val fallbackServerName = intent?.getStringExtra(EXTRA_SERVER_NAME)?.trim().orEmpty()
        val rawVlessUri = intent?.getStringExtra(EXTRA_VLESS_URI)?.trim().orEmpty()

        val parsedNode = if (rawVlessUri.isNotEmpty()) {
            try {
                VlessNodeConfig.parse(rawVlessUri)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        activeNode = parsedNode
        val sessionTitle = parsedNode?.remark?.ifBlank { null }
            ?: fallbackServerName.ifBlank { "AegisVIP Node" }

        startForegroundCompat(sessionTitle, parsedNode)
        startVpnTunnel(sessionTitle, parsedNode)
        return START_STICKY
    }

    /**
     * Builds a full Xray client JSON config using [VlessNodeConfig.toXrayOutboundJson]
     * and writes it to `filesDir/xray_active_config.json` so a native Xray core can read it.
     */
    private fun writeXrayClientConfig(node: VlessNodeConfig): File? {
        return try {
            val socksInbound = JSONObject().apply {
                put("tag", "socks-in")
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put(
                    "settings",
                    JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    }
                )
            }

            val directOutbound = JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
            }

            val fullConfig = JSONObject().apply {
                put("log", JSONObject().put("loglevel", "warning"))
                put("inbounds", JSONArray().put(socksInbound))
                put(
                    "outbounds",
                    JSONArray()
                        .put(node.toXrayOutboundJson("proxy"))
                        .put(directOutbound)
                )
            }

            val configFile = File(filesDir, "xray_active_config.json")
            configFile.writeText(fullConfig.toString(2), Charsets.UTF_8)
            configFile
        } catch (_: Exception) {
            null
        }
    }

    private fun startVpnTunnel(sessionName: String, node: VlessNodeConfig?) {
        // Restart cleanly if already running with a different node
        if (isRunning.get()) {
            stopVpnTunnel()
        }
        isRunning.set(true)

        try {
            val configFile = if (node != null) writeXrayClientConfig(node) else null

            val builder = Builder()
                .setSession(sessionName)
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1400)

            // Avoid routing loop if connecting directly to the remote VPN server IP
            node?.address?.takeIf { it.isNotBlank() }?.let { addr ->
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
                // Keep route exclusions optional; DNS & tunnel setup remain active
                val _targetHost = addr
            }

            val pfd = builder.establish()
            if (pfd == null) {
                stopVpnTunnel()
                return
            }
            vpnInterface = pfd

            tunnelThread = Thread({
                try {
                    runTunnelCore(pfd, node, configFile)
                } catch (_: Exception) {
                    // Ensure loop exits safely on error
                } finally {
                    if (isRunning.get()) {
                        stopVpnTunnel()
                    }
                }
            }, "AegisVpnTunnelThread").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            stopVpnTunnel()
        }
    }

    /**
     * Hook point where your Xray/sing-box library or tun2socks binary reads [configFile]
     * and forwards packets across [tunFd].
     */
    private fun runTunnelCore(tunFd: ParcelFileDescriptor, node: VlessNodeConfig?, configFile: File?) {
        val _fd = tunFd.fd
        val _node = node
        val _cfg = configFile

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun stopVpnTunnel() {
        isRunning.set(false)
        activeNode = null
        tunnelThread?.interrupt()
        tunnelThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        } finally {
            vpnInterface = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AegisVIP VPN Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active VPN tunnel status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, node: VlessNodeConfig?): Notification {
        val contentText = if (node != null) {
            "${node.network.uppercase()} • ${node.address}:${node.port}"
        } else {
            "VPN tunnel active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(title: String, node: VlessNodeConfig?) {
        try {
            val notification = buildNotification(title, node)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Fallback silently if foreground notification permission is restricted
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopVpnTunnel()
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpnTunnel()
        stopForegroundCompat()
        stopSelf()
        super.onRevoke()
    }

    companion object {
        const val ACTION_START = "com.aegisvip.vpn.action.START"
        const val ACTION_STOP = "com.aegisvip.vpn.action.STOP"
        const val EXTRA_SERVER_NAME = "com.aegisvip.vpn.extra.SERVER_NAME"
        const val EXTRA_VLESS_URI = "com.aegisvip.vpn.extra.VLESS_URI"

        private const val CHANNEL_ID = "aegisvip_vpn_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
// ... existing code ...
