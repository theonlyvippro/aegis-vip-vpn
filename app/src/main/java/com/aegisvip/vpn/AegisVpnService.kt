package com.aegisvip.vpn

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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AegisVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.aegisvip.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.aegisvip.vpn.DISCONNECT"
        const val BROADCAST_VPN_STATE = "com.aegisvip.vpn.VPN_STATE"
        private const val CHANNEL_ID = "AegisVpnChannel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "AegisVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                if (!isRunning.get()) {
                    val serverName = intent.getStringExtra("SERVER_NAME") ?: "AegisVPN VIP"
                    startVpn(serverName)
                }
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn(serverName: String) {
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("កំពុងភ្ជាប់ទៅកាន់ $serverName..."))

            val builder = Builder()
            builder.setSession("AegisVPN VIP")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not disallow self package", e)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "បរាជ័យក្នុងការបង្កើត VPN Interface")
                stopVpn()
                return
            }

            isRunning.set(true)
            sendStateBroadcast(true, "0 B", "0 B")
            updateNotification("បានភ្ជាប់: $serverName")

            vpnThread = Thread({
                runVpnPacketLoop()
            }, "AegisVpnThread")
            vpnThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "មានបញ្ហាក្នុងការចាប់ផ្តើម VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun runVpnPacketLoop() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)
        val packet = ByteBuffer.allocate(32767)
        var totalUpBytes = 0L
        var totalDownBytes = 0L
        var lastBroadcastTime = System.currentTimeMillis()

        try {
            while (isRunning.get() && !Thread.interrupted()) {
                val length = inputStream.read(packet.array())
                if (length > 0) {
                    packet.limit(length)
                    totalUpBytes += length
                    packet.clear()
                } else {
                    Thread.sleep(50)
                }

                val now = System.currentTimeMillis()
                if (now - lastBroadcastTime >= 1000) {
                    sendStateBroadcast(true, formatBytes(totalUpBytes), formatBytes(totalDownBytes))
                    lastBroadcastTime = now
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN Thread ត្រូវបានបញ្ឈប់")
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "កំហុសពេលកំពុងអាន Packet: ${e.message}")
            }
        } finally {
            try {
                inputStream.close()
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        vpnThread?.interrupt()
        vpnThread = null

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "កំហុសពេលបិទ VPN Interface", e)
        }

        sendStateBroadcast(false, "0 B", "0 B")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStateBroadcast(connected: Boolean, upSpeed: String, downSpeed: String) {
        val intent = Intent(BROADCAST_VPN_STATE).apply {
            setPackage(packageName)
            putExtra("CONNECTED", connected)
            putExtra("UP_SPEED", upSpeed)
            putExtra("DOWN_SPEED", downSpeed)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aegis VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "បង្ហាញស្ថានភាពដំណើរការរបស់ Aegis VPN"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AegisVPN VIP")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
