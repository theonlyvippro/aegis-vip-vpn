package com.aegisvip.vpn


import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity សម្រាប់គ្រប់គ្រងការស្នើសុំសិទ្ធិ VPN (VpnService.prepare)
 * និងបញ្ជាបើក/បិទ AegisVpnService ព្រមទាំងទទួលស្ថានភាពតភ្ជាប់ត្រឡប់មកវិញ។
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AegisMainActivity"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_SERVER_URI = "extra_server_uri"
        const val EXTRA_PROTOCOL = "extra_protocol"
    }

    private var pendingServerName: String = "វៀតណាម • ហូជីមិញ VIP"
    private var pendingServerUri: String = "openvpn+ssh://vip-user@vn-ssh.aegisvpn.net:2222"
    private var pendingProtocol: String = "OpenVPN SSH"
    private var isReceiverRegistered = false

    // ទទួលលទ្ធផលបន្ទាប់ពីអ្នកប្រើប្រាស់ចុច "OK" លើប្រអប់ស្នើសុំសិទ្ធិ VPN របស់ Android
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                if (result.resultCode == Activity.RESULT_OK) {
                    Log.i(TAG, "អ្នកប្រើប្រាស់បានអនុញ្ញាតសិទ្ធិ VPN រួចរាល់")
                    startVpnServiceInternal(
                        serverName = pendingServerName,
                        serverUri = pendingServerUri,
                        protocol = pendingProtocol
                    )
                } else {
                    Log.w(TAG, "អ្នកប្រើប្រាស់បានបដិសេធសិទ្ធិ VPN")
                    onVpnConnectionStateChanged(connected = false, reason = "permission_denied")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "កំហុសពេលទទួលលទ្ធផលសិទ្ធិ VPN", ex)
            }
        }

    // ទទួលសារ Broadcast ពី AegisVpnService ពេល Tunnel បើក ឬផ្តាច់
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == AegisVpnService.ACTION_VPN_STATE_CHANGED) {
                    val connected = intent.getBooleanExtra(AegisVpnService.EXTRA_IS_CONNECTED, false)
                    val server = intent.getStringExtra(AegisVpnService.EXTRA_SERVER_NAME) ?: pendingServerName
                    onVpnConnectionStateChanged(connected = connected, serverName = server)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "កំហុសពេលទទួលស្ថានភាព VPN", ex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            registerVpnStateReceiver()
        } catch (ex: Exception) {
            Log.e(TAG, "កំហុសពេលចាប់ផ្តើម MainActivity", ex)
        }
    }

    override fun onDestroy() {
        try {
            if (isReceiverRegistered) {
                unregisterReceiver(vpnStateReceiver)
                isReceiverRegistered = false
            }
        } catch (ex: Exception) {
            Log.e(TAG, "កំហុសពេលបិទ Receiver", ex)
        }
        super.onDestroy()
    }

    /**
     * ចាប់ផ្តើមភ្ជាប់ VPN៖ ពិនិត្យសិទ្ធិ VpnService.prepare() ជាមុន
     * បើមិនទាន់មានសិទ្ធិ ប្រព័ន្ធ Android នឹងបង្ហាញផ្ទាំងសួរអ្នកប្រើប្រាស់។
     */
    fun connectVpn(
        serverName: String,
        serverUri: String,
        protocol: String
    ) {
        try {
            pendingServerName = serverName
            pendingServerUri = serverUri
            pendingProtocol = protocol

            val prepareIntent: Intent? = VpnService.prepare(this)
            if (prepareIntent != null) {
                Log.i(TAG, "កំពុងស្នើសុំសិទ្ធិ VpnService ពីប្រព័ន្ធ...")
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                Log.i(TAG, "មានសិទ្ធិ VpnService រួចរាល់ កំពុងចាប់ផ្តើម Service...")
                startVpnServiceInternal(serverName, serverUri, protocol)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "បរាជ័យក្នុងការចាប់ផ្តើមភ្ជាប់ VPN", ex)
            onVpnConnectionStateChanged(connected = false, reason = ex.message ?: "error")
        }
    }

    /**
     * ផ្តាច់ការតភ្ជាប់ VPN និងបញ្ឈប់ AegisVpnService
     */
    fun disconnectVpn() {
        try {
            val intent = Intent(this, AegisVpnService::class.java).apply {
                action = AegisVpnService.ACTION_DISCONNECT
            }
            ContextCompat.startForegroundService(this, intent)
        } catch (ex: Exception) {
            Log.e(TAG, "បរាជ័យក្នុងការផ្តាច់ VPN", ex)
        }
    }

    /**
     * បញ្ជូនព័ត៌មាន Server / Config ទៅកាន់ AegisVpnService ជា Foreground Service
     */
    private fun startVpnServiceInternal(
        serverName: String,
        serverUri: String,
        protocol: String
    ) {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_CONNECT
            putExtra(EXTRA_SERVER_NAME, serverName)
            putExtra(EXTRA_SERVER_URI, serverUri)
            putExtra(EXTRA_PROTOCOL, protocol)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun registerVpnStateReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(AegisVpnService.ACTION_VPN_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnStateReceiver, filter)
        }
        isReceiverRegistered = true
    }

    /**
     * កែសម្រួលមុខងារនេះដើម្បីភ្ជាប់ជាមួយ UI (XML / Jetpack Compose / WebView)
     */
    private fun onVpnConnectionStateChanged(
        connected: Boolean,
        serverName: String = pendingServerName,
        reason: String? = null
    ) {
        Log.d(TAG, "ស្ថានភាព VPN: connected=$connected, server=$serverName, reason=$reason")
    }
}
