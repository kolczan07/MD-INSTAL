package pl.mdinstal.narzedzia

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

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
        checkForUpdate()
    }

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

    private fun checkForUpdate() {
        val currentCode = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { return }

        thread {
            try {
                val url = URL("https://gist.githubusercontent.com/kolczan07/34cbbbb60f48bb2c9589e62f7c467e5b/raw/mdinstal-version.txt")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val latestCode = conn.inputStream.bufferedReader().readText().trim().toIntOrNull()
                conn.disconnect()

                if (latestCode != null && latestCode > currentCode) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Dostępna nowa wersja")
                            .setMessage("Pobrać i zainstalować teraz?")
                            .setPositiveButton("Pobierz i zainstaluj") { _, _ ->
                                downloadAndInstall(
                                    "https://github.com/kolczan07/md-instal_releases/releases/latest/download/MD-INSTAL-latest.apk",
                                    "MD-INSTAL-latest.apk"
                                )
                            }
                            .setNegativeButton("Później", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                // brak internetu / błąd — cicho ignorujemy
            }
        }
    }

        private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            // Usuń plik po 2 minutach (żeby instalator zdążył odczytać)
            thread {
                Thread.sleep(120_000)
                try {
                    if (file.exists()) file.delete()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setMessage("Nie udało się otworzyć instalatora: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setMessage("Nie udało się otworzyć instalatora: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun doPickApp() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps: List<ResolveInfo> = packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        if (apps.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Brak dostępnych aplikacji.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Wybierz aplikację")
            .setItems(labels) { _, which ->
                val app = apps[which]
                val pkg = app.activityInfo.packageName
                val label = app.loadLabel(packageManager).toString()
                val jsLabel = label.replace("\\", "\\\\").replace("'", "\\'")
                web.evaluateJavascript(
                    "window.onAppPicked && window.onAppPicked('$pkg', '$jsLabel');",
                    null
                )
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickApp() {
            runOnUiThread { doPickApp() }
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread {
                try {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Nie można uruchomić tej aplikacji.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                } catch (e: Exception) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Błąd: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

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
