package com.aegisvip.vpn

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        setContentView(webView)

        // ផ្ទុក UI ពី assets/index.html ឬបង្ហាញសារស្វាគមន៍បើមិនទាន់ដាក់ file
        try {
            assets.open("index.html").close()
            webView.loadUrl("file:///android_asset/index.html")
        } catch (e: Exception) {
            val fallbackHtml = """
                <html>
                <body style="background:#090d16;color:#f8fafc;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;">
                  <div>
                    <h2 style="color:#38bdf8;">AegisVIP VPN Ready</h2>
                    <p>APK Build ជោគជ័យ! សូមបញ្ចូល UI ទៅ app/src/main/assets/index.html</p>
                  </div>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL(null, fallbackHtml, "text/html", "UTF-8", null)
        }
    }
}
