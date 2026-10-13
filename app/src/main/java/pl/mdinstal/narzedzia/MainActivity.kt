package pl.mdinstal.narzedzia

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var printWeb: WebView? = null
    private val host = "appassets.androidplatform.net"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = request?.url?.let { loader.shouldInterceptRequest(it) }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                val s = u.scheme
                if ((s == "http" || s == "https") && u.host != host) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                    }
                    return true
                }
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Anuluj") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity)
                input.setText(defaultValue ?: "")
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("Anuluj") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://$host/assets/index.html")
    }

    /** Drukowanie / zapis do PDF: osobny, niewidoczny WebView z gotowym HTML-em formularza. */
    private fun doPrint(html: String, landscape: Boolean, title: String) {
        val pv = WebView(this)
        printWeb = pv
        var started = false
        pv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (started || view == null) return
                started = true
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val size = if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape()
                else PrintAttributes.MediaSize.ISO_A4.asPortrait()
                // marginesy w tysięcznych cala: 5 mm (poziomo, schemat) / 4 mm (pionowo, protokół)
                val m = if (landscape) 197 else 157
                val attrs = PrintAttributes.Builder()
                    .setMediaSize(size)
                    .setMinMargins(PrintAttributes.Margins(m, m, m, m))
                    .build()
                pm.print(title, view.createPrintDocumentAdapter(title), attrs)
            }
        }
        pv.loadDataWithBaseURL("https://$host/", html, "text/html", "UTF-8", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun getVersion(): String {
            return try {
                val pi = packageManager.getPackageInfo(packageName, 0)
                "${pi.versionName} (build ${pi.versionCode})"
            } catch (e: Exception) {
                "?"
            }
        }

        @JavascriptInterface
        fun printHtml(html: String, landscape: Boolean, title: String) {
            runOnUiThread { doPrint(html, landscape, title) }
        }

        @JavascriptInterface
        fun radioState(playing: Boolean, name: String) {
            runOnUiThread {
                val i = Intent(this@MainActivity, RadioService::class.java)
                if (playing) {
                    i.putExtra("name", name)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                } else {
                    stopService(i)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){return window.appBack?window.appBack():false})()"
        ) { r -> if (r != "true") moveTaskToBack(true) }
    }

    override fun onDestroy() {
        stopService(Intent(this, RadioService::class.java))
        web.destroy()
        super.onDestroy()
    }
}
