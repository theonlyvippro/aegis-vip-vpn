package com.aegisvip.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-contained parsed VLESS configuration model so AegisVpnService compiles
 * cleanly regardless of file ordering in the project.
 */
data class VlessNodeConfig(
    val remark: String,
    val uuid: String,
    val address: String,
    val port: Int,
    val encryption: String = "none",
    val network: String = "xhttp",
    val host: String = "",
    val headerType: String = "auto",
    val path: String = "/",
    val security: String = "tls",
    val fingerprint: String = "chrome",
    val sni: String = ""
) {
    fun toXrayOutboundJson(tag: String = "proxy"): JSONObject {
        val effectiveSni = sni.ifBlank { host.ifBlank { address } }
        val effectiveHost = host.ifBlank { effectiveSni }
        val normalizedPath = if (path.startsWith("/")) path else "/$path"

        val userObj = JSONObject().apply {
            put("id", uuid)
            put("encryption", encryption.ifBlank { "none" })
            put("level", 0)
        }

        val vnextItem = JSONObject().apply {
            put("address", address)
            put("port", port)
            put("users", JSONArray().put(userObj))
        }

        val settingsObj = JSONObject().apply {
            put("vnext", JSONArray().put(vnextItem))
        }

        val streamSettings = JSONObject().apply {
            put("network", network.ifBlank { "tcp" })
            if (security.isNotBlank() && security != "none") {
                put("security", security)
            }

            if (security.equals("tls", ignoreCase = true)) {
                val tlsObj = JSONObject().apply {
                    put("serverName", effectiveSni)
                    if (fingerprint.isNotBlank()) {
                        put("fingerprint", fingerprint)
                    }
                }
                put("tlsSettings", tlsObj)
            }

            when (network.lowercase()) {
                "xhttp" -> {
                    val xhttpObj = JSONObject().apply {
                        put("path", normalizedPath)
                        if (effectiveHost.isNotBlank()) {
                            put("host", effectiveHost)
                        }
                    }
                    put("xhttpSettings", xhttpObj)
                }
                "ws" -> {
                    val wsObj = JSONObject().apply {
                        put("path", normalizedPath)
                        if (effectiveHost.isNotBlank()) {
                            put("headers", JSONObject().put("Host", effectiveHost))
                        }
                    }
                    put("wsSettings", wsObj)
                }
            }
        }

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "vless")
            put("settings", settingsObj)
            put("streamSettings", streamSettings)
        }
    }

    companion object {
        fun parse(rawUri: String): VlessNodeConfig {
            val trimmed = rawUri.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("VLESS URI cannot be empty")
            }

            val normalizedUri = if (trimmed.length >= 8 && trimmed.substring(0, 8).equals("vless://", ignoreCase = true)) {
                "vless://" + trimmed.substring(8)
            } else {
                throw IllegalArgumentException("URI must start with vless://")
            }

            val hashIndex = normalizedUri.indexOf('#')
            val withoutFragment = if (hashIndex >= 0) normalizedUri.substring(0, hashIndex) else normalizedUri
            val rawFragment = if (hashIndex >= 0) normalizedUri.substring(hashIndex + 1) else ""

            val decodedRemark = try {
                if (rawFragment.isNotEmpty()) {
                    URLDecoder.decode(rawFragment, StandardCharsets.UTF_8.name())
                } else {
                    ""
                }
            } catch (_: Exception) {
                rawFragment
            }

            val parsed = Uri.parse(withoutFragment)
            val uuid = parsed.userInfo?.trim().orEmpty()
            val address = parsed.host?.trim().orEmpty()
            val port = if (parsed.port in 1..65535) parsed.port else 443

            if (uuid.isEmpty()) {
                throw IllegalArgumentException("Missing UUID in VLESS URI")
            }
            if (address.isEmpty()) {
                throw IllegalArgumentException("Missing server address in VLESS URI")
            }

            val encryption = parsed.getQueryParameter("encryption")?.trim().orEmpty().ifEmpty { "none" }
            val network = parsed.getQueryParameter("type")?.trim().orEmpty().ifEmpty { "tcp" }
            val host = parsed.getQueryParameter("host")?.trim().orEmpty()
            val headerType = parsed.getQueryParameter("headerType")?.trim().orEmpty().ifEmpty { "auto" }
            val rawPath = parsed.getQueryParameter("path")?.trim().orEmpty().ifEmpty { "/" }
            val security = parsed.getQueryParameter("security")?.trim().orEmpty().ifEmpty { "none" }
            val fingerprint = parsed.getQueryParameter("fp")?.trim().orEmpty()
            val sni = parsed.getQueryParameter("sni")?.trim().orEmpty()

            val finalRemark = decodedRemark.ifBlank { "$address:$port" }

            return VlessNodeConfig(
                remark = finalRemark,
                uuid = uuid,
                address = address,
                port = port,
                encryption = encryption,
                network = network,
                host = host,
                headerType = headerType,
                path = rawPath,
                security = security,
                fingerprint = fingerprint,
                sni = sni
            )
        }
    }
}

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

        val parsedNode: VlessNodeConfig? = if (rawVlessUri.isNotEmpty()) {
            try {
                VlessNodeConfig.parse(rawVlessUri)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        activeNode = parsedNode
        val sessionTitle: String = parsedNode?.remark?.takeIf { it.isNotBlank() }
            ?: fallbackServerName.ifBlank { "AegisVIP Node" }

        startForegroundCompat(sessionTitle, parsedNode)
        startVpnTunnel(sessionTitle, parsedNode)
        return START_STICKY
    }

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

            node?.let {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
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
