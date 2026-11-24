package com.aegisvip.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

class AegisVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopVpnTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "AegisVIP Node"
        startVpnTunnel(serverName)
        return START_STICKY
    }

    private fun startVpnTunnel(sessionName: String) {
        if (isRunning.getAndSet(true)) return

        try {
            val builder = Builder()
                .setSession(sessionName)
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1400)

            vpnInterface = builder.establish()

            tunnelThread = Thread({
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
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

    private fun stopVpnTunnel() {
        isRunning.set(false)
        tunnelThread?.interrupt()
        tunnelThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        } finally {
            vpnInterface = null
        }
    }

    override fun onDestroy() {
        stopVpnTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpnTunnel()
        stopSelf()
        super.onRevoke()
    }

    companion object {
        const val ACTION_START = "com.aegisvip.vpn.action.START"
        const val ACTION_STOP = "com.aegisvip.vpn.action.STOP"
        const val EXTRA_SERVER_NAME = "com.aegisvip.vpn.extra.SERVER_NAME"
    }
}
