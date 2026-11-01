package app.tapshare

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web mode: shows the TapShare website (bundled from /docs) so the app can pair with the site,
 * with another app in Web mode, or with any browser, using a six-character code.
 *
 * The page is served from https://appassets.androidplatform.net/assets/..., and received files
 * are saved to Downloads/TapShare through the TapShareNative bridge below.
 */
class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val bridge = NativeBridge()

    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private val chooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            chooserCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
            chooserCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = findViewById(R.id.web)
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(bridge, "TapShareNative")

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                chooserCallback?.onReceiveValue(null)
                chooserCallback = callback
                return try {
                    chooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    chooserCallback = null
                    false
                }
            }
        }

        // Back steps out of a sub-screen of the page first, then leaves Web mode.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    web.evaluateJavascript("window.TapShareBack ? window.TapShareBack() : false") { r ->
                        if (r != "true") {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )

        web.loadUrl(startUrl())
    }

    /** A link like .../TapShare/web/?join=ABC123 opens straight on the send screen. */
    private fun startUrl(): String {
        val base = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/web/index.html"
        val code = intent?.data?.getQueryParameter("join")
        return if (code != null && Regex("[A-Za-z0-9]{6}").matches(code)) "$base?join=$code" else base
    }

    override fun onDestroy() {
        bridge.abortAll()
        chooserCallback?.onReceiveValue(null)
        chooserCallback = null
        web.removeJavascriptInterface("TapShareNative")
        web.destroy()
        super.onDestroy()
    }

    /** Saves files received by the page into Downloads/TapShare. Called from the page's JavaScript. */
    private inner class NativeBridge {
        private inner class Pending(val uri: Uri, val out: OutputStream)

        private val files = ConcurrentHashMap<Int, Pending>()
        private val nextId = AtomicInteger(1)

        /** Starts a file and returns its id, or -1 when it can't be created. */
        @JavascriptInterface
        fun begin(name: String, mime: String): Int {
            var uri: Uri? = null
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TapShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return -1
                val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException()
                val id = nextId.getAndIncrement()
                files[id] = Pending(uri, out)
                id
            } catch (e: Exception) {
                uri?.let { runCatching { contentResolver.delete(it, null, null) } }
                -1
            }
        }

        /** Appends a base64-encoded block. */
        @JavascriptInterface
        fun write(id: Int, base64: String): Boolean {
            val p = files[id] ?: return false
            return try {
                p.out.write(Base64.decode(base64, Base64.DEFAULT))
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Completes the file and returns the name it was saved under, or "" on failure. */
        @JavascriptInterface
        fun finish(id: Int): String {
            val p = files.remove(id) ?: return ""
            return try {
                p.out.close()
                contentResolver.update(
                    p.uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
                contentResolver.query(
                    p.uri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
            } catch (e: Exception) {
                runCatching { contentResolver.delete(p.uri, null, null) }
                ""
            }
        }

        /** Discards a half-received file. */
        @JavascriptInterface
        fun abort(id: Int) {
            val p = files.remove(id) ?: return
            runCatching { p.out.close() }
            runCatching { contentResolver.delete(p.uri, null, null) }
        }

        fun abortAll() {
            files.keys.toList().forEach { abort(it) }
        }
    }
}
