package com.felix.closet

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread

/**
 * The Android app is the web app (web/, bundled into assets at build time) in a WebView, plus what a WebView
 * cannot do on its own: pick photos, save a backup file, and call model APIs without browser CORS limits.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: String? = null

    private val pickOne = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        deliver(uri?.let { arrayOf(it) })
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        deliver(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    private val pickDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        deliver(uri?.let { arrayOf(it) })
    }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingSave; pendingSave = null
        if (uri == null || text == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }.isSuccess
        Toast.makeText(this, if (ok) "备份已保存" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    private fun deliver(uris: Array<Uri>?) { fileCallback?.onReceiveValue(uris); fileCallback = null }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.parseColor("#131312") else Color.parseColor("#FAF9F6")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        web = WebView(this).apply { setBackgroundColor(bg) }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        // Keep the page clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder().addPathHandler("/", WebAssets(this)).build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(Bridge(), "ClosetAndroid")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } // links open in the browser
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val images = params.acceptTypes.any { it.startsWith("image") }
                val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                runCatching {
                    val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    when {
                        images && multiple -> pickMany.launch(req)
                        images -> pickOne.launch(req)
                        else -> pickDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                }.onFailure { deliver(null) }
                return true
            }
        }

        // Back closes the top sheet/page inside the app first, then goes back to 今天, then leaves.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.closetBack ? closetBack() : false") { r ->
                    if (r != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://$HOST/index.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun js(code: String) = runOnUiThread { if (!isDestroyed) web.evaluateJavascript(code, null) }

    inner class Bridge {
        /** POST to a model API from native code (no CORS). Answers with __closetNative(id, status, text); status -1 = timeout, -2 = network error. */
        @JavascriptInterface
        fun http(id: Int, url: String, headersJson: String, body: String, timeoutMs: Int) {
            thread {
                val (status, text) = try {
                    require(url.startsWith("https://")) { "只支持 https 接口" }
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 15000
                    c.readTimeout = timeoutMs.coerceIn(5000, 300000)
                    c.doOutput = true
                    val h = JSONObject(headersJson)
                    for (k in h.keys()) c.setRequestProperty(k, h.getString(k))
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val code = c.responseCode
                    val s = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    c.disconnect()
                    code to s
                } catch (e: SocketTimeoutException) {
                    -1 to "timeout"
                } catch (e: Exception) {
                    -2 to (e.message ?: e.javaClass.simpleName)
                }
                js("window.__closetNative && __closetNative($id, $status, ${JSONObject.quote(text)})")
            }
        }

        /** Lets the user pick where to save [text] (a backup). */
        @JavascriptInterface
        fun saveFile(name: String, text: String) {
            runOnUiThread { pendingSave = text; saveDoc.launch(name) }
        }

        /** The old native wardrobe in backup format, once; null when there is none or it was already moved. */
        @JavascriptInterface
        fun legacyBackup(): String? {
            val sp = getSharedPreferences("settings", MODE_PRIVATE)
            if (sp.getBoolean("migrated_to_web", false)) return null
            val json = File(filesDir, "closet.json").takeIf { it.exists() }?.readText()
            val imgDir = File(filesDir, "img")
            val out = Legacy.toBackup(json, { name -> File(imgDir, name).takeIf { it.isFile }?.readBytes() }, sp.all)
            if (out == null) sp.edit().putBoolean("migrated_to_web", true).apply()
            return out?.toString()
        }

        /** Called by the web side after the import succeeded; the old files stay on disk as a fallback. */
        @JavascriptInterface
        fun legacyDone() {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("migrated_to_web", true).apply()
        }
    }

    companion object { const val HOST = "appassets.androidplatform.net" }
}

/** Serves assets/web/ with explicit MIME types (ES modules need text/javascript). */
class WebAssets(private val act: ComponentActivity) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        val p = path.substringBefore('?').ifEmpty { "index.html" }
        return try {
            val type = MIME[p.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
            val text = type.startsWith("text/") || type.endsWith("json") || type.endsWith("javascript") || type.endsWith("manifest+json")
            WebResourceResponse(type, if (text) "utf-8" else null, act.assets.open("web/$p"))
        } catch (e: IOException) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
        }
    }

    companion object {
        val MIME = mapOf(
            "html" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript", "css" to "text/css",
            "json" to "application/json", "webmanifest" to "application/manifest+json", "png" to "image/png",
            "jpg" to "image/jpeg", "svg" to "image/svg+xml", "woff2" to "font/woff2", "txt" to "text/plain",
        )
    }
}
