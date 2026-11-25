package com.aegisvpn.vip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AegisVpnService កូនកាត់ពី android.net.VpnService
 * ការបង្កើត TUN interface តាមរយៈ VpnService.Builder.establish()
 * នឹងធ្វើឱ្យប្រព័ន្ធ Android លោតសញ្ញា "VPN" នៅលើ Status Bar ខាងលើ
 * និងបង្ហាញ Notification ពេលកំពុងដំណើរការ។
 */
class AegisVpnService : VpnService() {

    companion object {
        private const val TAG = "AegisVpnService"
        private const val CHANNEL_ID = "aegis_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.aegisvpn.vip.action.CONNECT"
        const val ACTION_DISCONNECT = "com.aegisvpn.vip.action.DISCONNECT"
        const val ACTION_VPN_STATE_CHANGED = "com.aegisvpn.vip.action.STATE_CHANGED"
        const val EXTRA_IS_CONNECTED = "extra_is_connected"
        const val EXTRA_SERVER_NAME = "extra_server_name"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var activeServerName: String = "វៀតណាម • ហូជីមិញ VIP"

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
        } catch (ex: Exception) {
            Log.e(TAG, "កំហុសពេលបង្កើត Notification Channel", ex)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_DISCONNECT -> {
                    stopVpnTunnel()
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_CONNECT, null -> {
                    activeServerName = intent?.getStringExtra(MainActivity.EXTRA_SERVER_NAME)
                        ?: activeServerName
                    val serverUri = intent?.getStringExtra(MainActivity.EXTRA_SERVER_URI) ?: ""
                    val protocol = intent?.getStringExtra(MainActivity.EXTRA_PROTOCOL) ?: "OpenVPN SSH"

                    startForeground(NOTIFICATION_ID, buildStatusNotification(activeServerName, protocol))
                    startVpnTunnel(serverUri, protocol)
                    return START_STICKY
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "កំហុសក្នុង onStartCommand", ex)
            broadcastState(connected = false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // ត្រូវបានហៅនៅពេលប្រព័ន្ធ Android ឬកម្មវិធី VPN ផ្សេងទៀតដកសិទ្ធិ VPN
        Log.w(TAG, "សិទ្ធិ VPN ត្រូវបានដកហូត (onRevoke)")
        stopVpnTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        super.onDestroy()
    }

    /**
     * បង្កើត TUN Interface តាមរយៈ VpnService.Builder
     * ដើម្បីឱ្យសញ្ញា VPN លោតនៅលើ Status Bar របស់ទូរស័ព្ទ។
     */
    private fun startVpnTunnel(serverUri: String, protocol: String) {
        if (isRunning.get()) {
            stopVpnTunnel()
        }

        try {
            Log.i(TAG, "កំពុងរៀបចំ Tunnel ($protocol): $serverUri")

            val builder = Builder()
                .setSession("AegisVPN VIP ($activeServerName)")
                .setMtu(1400)
                .addAddress("10.66.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            // ការពារ Socket របស់កម្មវិធីខ្លួនឯងកុំឱ្យរត់ចូល Loop ក្នុង TUN
            val udpSocket = DatagramChannel.open()
            udpSocket.socket().bind(null)
            protect(udpSocket.socket())

            val establishedInterface = builder.establish()
            if (establishedInterface == null) {
                Log.e(TAG, "មិនអាចបង្កើត TUN Interface បានទេ (establish() ត្រឡប់ null)")
                udpSocket.close()
                broadcastState(connected = false)
                return
            }

            vpnInterface = establishedInterface
            isRunning.set(true)
            broadcastState(connected = true)

            tunnelThread = Thread({
                runPacketLoop(establishedInterface, udpSocket)
            }, "AegisVpnTunnelThread").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "TUN Interface បានបើកដំណើរការជោគជ័យ!")
        } catch (ex: Exception) {
            Log.e(TAG, "កំហុសពេលបង្កើត VPN Tunnel", ex)
            stopVpnTunnel()
            broadcastState(connected = false)
        }
    }

    /**
     * រង្វិលជុំអាន Packet ពី TUN Interface
     * (នៅកន្លែងនេះអ្នកអាចភ្ជាប់ទៅកាន់ Core ពិតប្រាកដដូចជា sing-box / xray / openvpn / ssh)
     */
    private fun runPacketLoop(tunFd: ParcelFileDescriptor, udpSocket: DatagramChannel) {
        try {
            val inputStream = FileInputStream(tunFd.fileDescriptor)
            val outputStream = FileOutputStream(tunFd.fileDescriptor)
            val packetBuffer = ByteBuffer.allocate(32767)
            val dnsTarget = InetSocketAddress("1.1.1.1", 53)

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val length = inputStream.read(packetBuffer.array())
                if (length > 0) {
                    // ត្រួតពិនិត្យ Packet ដំបូង (IPv4 header)
                    val firstByte = packetBuffer.array()[0].toInt() and 0xFF
                    if (firstByte ushr 4 == 4 && length >= 28) {
                        // ឧទាហរណ៍បញ្ជូន UDP DNS ទៅកាន់ 1.1.1.1 តាម Socket ដែលបាន protect()
                        val protocolByte = packetBuffer.array()[9].toInt() and 0xFF
                        if (protocolByte == 17) {
                            val payloadOffset = 28
                            val payloadLen = length - payloadOffset
                            if (payloadLen > 0) {
                                val outBuf = ByteBuffer.wrap(packetBuffer.array(), payloadOffset, payloadLen)
                                udpSocket.send(outBuf, dnsTarget)
                            }
                        }
                    }
                    // រក្សាលំហូរសុវត្ថិភាព និងអាចសរសេរ Packet ត្រឡប់តាម outputStream ពេលភ្ជាប់ Core ពេញលេញ
                    outputStream.write(0)
                } else {
                    Thread.sleep(25)
                }
            }
        } catch (_: InterruptedException) {
            // បញ្ឈប់ Thread ដោយសុវត្ថិភាព
        } catch (ex: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "កំហុសក្នុង Packet Loop", ex)
            }
        } finally {
            try {
                udpSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopVpnTunnel() {
        isRunning.set(false)
        try {
            tunnelThread?.interrupt()
            tunnelThread = null
        } catch (_: Exception) {
        }
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (ex: Exception) {
            Log.e(TAG, "កំហុសពេលបិទ TUN Interface", ex)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        broadcastState(connected = false)
    }

    private fun broadcastState(connected: Boolean) {
        val stateIntent = Intent(ACTION_VPN_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_CONNECTED, connected)
            putExtra(EXTRA_SERVER_NAME, activeServerName)
        }
        sendBroadcast(stateIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AegisVPN VIP Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "បង្ហាញស្ថានភាពការតភ្ជាប់ VPN នៅលើទូរស័ព្ទ"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(serverName: String, protocol: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AegisVPN VIP • បានភ្ជាប់")
            .setContentText("$serverName ($protocol)")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
