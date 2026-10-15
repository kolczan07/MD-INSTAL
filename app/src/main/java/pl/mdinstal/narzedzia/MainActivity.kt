package pl.mdinstal.narzedzia

import android.Manifest
import android.annotation.SuppressLint
import androidx.fragment.app.FragmentActivity
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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread


class MainActivity : FragmentActivity() {

    private lateinit var web: WebView
    private var printWeb: WebView? = null
    private val host = "appassets.androidplatform.net"
    private var pendingIgluUser: String = ""
    private var pendingIgluPass: String = ""
    private var pendingIgluRemember: Boolean = false

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
            if (Build.VERSION.SDK_INT >= 33) {
                @Suppress("DEPRECATION")
                setAlgorithmicDarkeningAllowed(true)
            } else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                forceDark = android.webkit.WebSettings.FORCE_DARK_AUTO
            }
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
                if (u.host?.endsWith("iglutech.eu") == true) return false
                if ((s == "http" || s == "https") && u.host != host) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("iglutech.eu/Account/Login") == true) {
                    val js = """
                        (function(){
                          try {
                            var u = document.getElementById('Input_Username');
                            var p = document.getElementById('Input_Password');
                            var r = document.getElementById('Input_RememberLogin');
                            if (u) u.value = ${jsString(pendingIgluUser)};
                            if (p) p.value = ${jsString(pendingIgluPass)};
                            if (r) r.checked = $pendingIgluRemember;
                          } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
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

    private fun downloadAndInstall(url: String, fileName: String) {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (dir != null && dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    try { f.delete() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("Pobieranie aktualizacji MD-INSTAL")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        thread {
            var downloading = true
            while (downloading) {
                val q = DownloadManager.Query().setFilterById(downloadId)
                val c = dm.query(q)
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloading = false
                            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                            runOnUiThread { installApk(file) }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            downloading = false
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setMessage("Nie udało się pobrać aktualizacji.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }
                c.close()
                if (downloading) Thread.sleep(1000)
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

        private fun jsString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun authenticateBiometric(onSuccess: () -> Unit) {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
        }
               val prompt = BiometricPrompt(this, executor, callback)
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("MD-INSTAL")
            .setSubtitle("Potwierdź tożsamość")
            .setAllowedAuthenticators(authenticators)
        prompt.authenticate(builder.build())
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
        fun getAppIcon(packageName: String): String {
            return try {
                val drawable = packageManager.getApplicationIcon(packageName)
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)

                val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                val bytes = baos.toByteArray()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun openIglutech() {
            runOnUiThread {
                pendingIgluUser = PasswordStore.load(this@MainActivity, "iglu_user") ?: ""
                pendingIgluPass = PasswordStore.load(this@MainActivity, "iglu_pass") ?: ""
                pendingIgluRemember = PasswordStore.load(this@MainActivity, "iglu_remember") == "true"

                if (pendingIgluUser.isEmpty()) {
                    web.loadUrl("https://service.iglutech.eu")
                    return@runOnUiThread
                }
                authenticateBiometric {
                    web.loadUrl("https://service.iglutech.eu")
                }
            }
        }

        @JavascriptInterface
        fun saveIglutechCreds(user: String, pass: String, remember: Boolean) {
            runOnUiThread {
                authenticateBiometric {
                    PasswordStore.save(this@MainActivity, "iglu_user", user)
                    PasswordStore.save(this@MainActivity, "iglu_pass", pass)
                    PasswordStore.save(this@MainActivity, "iglu_remember", if (remember) "true" else "false")
                    web.evaluateJavascript("window.onIglutechSaved && window.onIglutechSaved();", null)
                }
            }
        }

        @JavascriptInterface
        fun clearIglutechCreds() {
            runOnUiThread {
                authenticateBiometric {
                    PasswordStore.delete(this@MainActivity, "iglu_user")
                    PasswordStore.delete(this@MainActivity, "iglu_pass")
                    PasswordStore.delete(this@MainActivity, "iglu_remember")
                    web.evaluateJavascript("window.onIglutechCleared && window.onIglutechCleared();", null)
                }
            }
        }

        @JavascriptInterface
        fun getIglutechUser(): String {
            return PasswordStore.load(this@MainActivity, "iglu_user") ?: ""
        }

        @JavascriptInterface
        fun hasIglutechCreds(): Boolean {
            return PasswordStore.has(this@MainActivity, "iglu_user")
        }

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
