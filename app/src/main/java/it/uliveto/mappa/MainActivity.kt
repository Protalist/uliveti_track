package it.uliveto.mappa

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var pendingExport: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("uliveto", MODE_PRIVATE) }

    companion object {
        private const val REQ_LOCATION = 1
        private const val REQ_EXPORT = 2
        private const val REQ_IMPORT = 3
        private const val REQ_FOLDER = 4
        private const val MAIN_FILE = "uliveto.geojson"
        private const val BACKUP_DIR = "backup"
        // L'app viene servita da un'origine https "finta" così GPS e salvataggio funzionano
        private const val START_URL = "https://appassets.androidplatform.net/assets/index.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // salvataggio dati (localStorage)
            setGeolocationEnabled(true)
            allowFileAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCallback = callback
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        REQ_LOCATION
                    )
                }
            }
        }

        webView.addJavascriptInterface(Bridge(), "Android")
        webView.loadUrl(START_URL)
    }

    /** Funzioni chiamabili dalla pagina con window.Android.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun exportData(json: String, fileName: String) {
            runOnUiThread {
                pendingExport = json
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, fileName)
                }
                startActivityForResult(intent, REQ_EXPORT)
            }
        }

        @JavascriptInterface
        fun importData() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQ_IMPORT)
            }
        }

        /** Apre il selettore di cartelle di Android */
        @JavascriptInterface
        fun chooseFolder() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                startActivityForResult(intent, REQ_FOLDER)
            }
        }

        /** Nome della cartella scelta, o stringa vuota */
        @JavascriptInterface
        fun getFolderName(): String = folder()?.name ?: ""

        @JavascriptInterface
        fun forgetFolder() {
            prefs.getString("folder", null)?.let { old ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(old),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) { }
            }
            prefs.edit().remove("folder").apply()
        }

        /**
         * Scrive uliveto.geojson nella cartella (sovrascrivendolo) e una copia
         * del giorno in backup/uliveto-AAAA-MM-GG.geojson.
         * silent = true per il salvataggio automatico: avvisa solo in caso di errore.
         */
        @JavascriptInterface
        fun saveToFolder(json: String, day: String, silent: Boolean) {
            io.execute {
                val dir = folder()
                if (dir == null) {
                    runOnUiThread { js("onFolderLost()") }
                    return@execute
                }
                try {
                    writeFile(dir, MAIN_FILE, json)
                    val backups = dir.findFile(BACKUP_DIR)?.takeIf { it.isDirectory }
                        ?: dir.createDirectory(BACKUP_DIR)
                    backups?.let { writeFile(it, "uliveto-$day.geojson", json) }
                    if (!silent) runOnUiThread { js("toast(" + JSONObject.quote("Salvato nella cartella " + (dir.name ?: "")) + ")") }
                } catch (e: Exception) {
                    runOnUiThread { js("toast('Non riesco a scrivere nella cartella: sceglila di nuovo')") }
                }
            }
        }

        /** Legge uliveto.geojson dalla cartella e lo passa alla mappa */
        @JavascriptInterface
        fun loadFromFolder() {
            io.execute {
                val file = folder()?.findFile(MAIN_FILE)
                if (file == null) {
                    runOnUiThread { js("toast('Nella cartella non c’è ancora $MAIN_FILE')") }
                    return@execute
                }
                try {
                    val text = contentResolver.openInputStream(file.uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    runOnUiThread { js("importData(${JSONObject.quote(text)})") }
                } catch (e: Exception) {
                    runOnUiThread { js("toast('Non sono riuscito a leggere il file')") }
                }
            }
        }
    }

    /** La cartella scelta, se il permesso è ancora valido */
    private fun folder(): DocumentFile? {
        val saved = prefs.getString("folder", null) ?: return null
        val uri = Uri.parse(saved)
        val stillAllowed = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!stillAllowed) return null
        return DocumentFile.fromTreeUri(this, uri)?.takeIf { it.exists() && it.canWrite() }
    }

    private fun writeFile(dir: DocumentFile, name: String, text: String) {
        val file = dir.findFile(name) ?: dir.createFile("application/json", name)
            ?: throw IllegalStateException("create failed")
        // "wt" = sovrascrive il contenuto precedente
        contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("open failed")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            pendingExport = null
            return
        }
        when (requestCode) {
            REQ_FOLDER -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs.edit().putString("folder", uri.toString()).apply()
                    val name = DocumentFile.fromTreeUri(this, uri)?.name ?: ""
                    js("onFolderSet(${JSONObject.quote(name)})")
                } catch (e: Exception) {
                    js("toast('Non posso usare questa cartella, scegline un’altra')")
                }
            }
            REQ_EXPORT -> {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write((pendingExport ?: "").toByteArray(Charsets.UTF_8))
                    }
                    js("toast('File salvato')")
                } catch (e: Exception) {
                    js("toast('Non sono riuscito a salvare il file')")
                }
                pendingExport = null
            }
            REQ_IMPORT -> {
                try {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
                    js("importData(${JSONObject.quote(text)})")
                } catch (e: Exception) {
                    js("toast('Non sono riuscito a leggere il file')")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
            geoOrigin = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Il tasto indietro chiude prima finestre e modalità aperte nella mappa
        webView.evaluateJavascript("handleBack()") { result ->
            if (result != "true") finish()
        }
    }

    private fun js(code: String) = webView.evaluateJavascript(code, null)
}
