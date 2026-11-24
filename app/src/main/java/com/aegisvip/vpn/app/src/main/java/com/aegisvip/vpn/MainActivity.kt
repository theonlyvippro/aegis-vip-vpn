package com.aegisvip.vpn

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingServerName: String = "AegisVIP Node"

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                launchVpnService(pendingServerName)
                notifyWebState(connected = true, permissionGranted = true)
            } else {
                notifyWebState(connected = false, permissionGranted = false)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(VpnBridge(), "AndroidVpn")
        setContentView(webView)

        try {
            assets.open("index.html").close()
            webView.loadUrl("file:///android_asset/index.html")
        } catch (_: Exception) {
            val fallbackHtml = """
                <html>
                <body style="background:#090d16;color:#f8fafc;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;gap:12px;">
                  <h2 style="color:#38bdf8;margin:0;">AegisVIP VPN Service Ready</h2>
                  <button onclick="AndroidVpn.startVpn('SG VIP #01')" style="padding:12px 20px;border-radius:10px;border:0;background:#38bdf8;color:#090d16;font-weight:bold;">ភ្ជាប់ VPN</button>
                  <button onclick="AndroidVpn.stopVpn()" style="padding:12px 20px;border-radius:10px;border:0;background:#334155;color:#fff;font-weight:bold;">ផ្តាច់ VPN</button>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL(null, fallbackHtml, "text/html", "UTF-8", null)
        }
    }

    private fun requestOrStartVpn(serverName: String) {
        pendingServerName = serverName.ifBlank { "AegisVIP Node" }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            launchVpnService(pendingServerName)
            notifyWebState(connected = true, permissionGranted = true)
        }
    }

    private fun launchVpnService(serverName: String) {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_START
            putExtra(AegisVpnService.EXTRA_SERVER_NAME, serverName)
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_STOP
        }
        startService(intent)
        notifyWebState(connected = false, permissionGranted = true)
    }

    private fun notifyWebState(connected: Boolean, permissionGranted: Boolean) {
        runOnUiThread {
            val js = "window.onNativeVpnState && window.onNativeVpnState($connected, $permissionGranted);"
            webView.evaluateJavascript(js, null)
        }
    }

    inner class VpnBridge {
        @JavascriptInterface
        fun startVpn(serverName: String = "AegisVIP Node") {
            runOnUiThread {
                requestOrStartVpn(serverName)
            }
        }

        @JavascriptInterface
        fun stopVpn() {
            runOnUiThread {
                stopVpnService()
            }
        }
    }
}
