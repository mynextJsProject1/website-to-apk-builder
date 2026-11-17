package com.websitebuilder.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingView: CleanLoadingView

    private var appName = "Web2APK"
    private var websiteUrl = ""
    private var backButtonEnabled = true
    private var fullscreenEnabled = false
    private var downloadEnabled = true

    private var splashStartedAt = 0L
    private var pageLoaded = false
    private var splashRemoved = false

    private val handler = Handler(Looper.getMainLooper())
    private val minimumSplashTime = 2000L

    private val hideSplashRunnable = Runnable {
        removeSplashIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!loadConfiguration()) return

        title = appName

        if (fullscreenEnabled) {
            enableFullscreen()
        }

        splashStartedAt = SystemClock.uptimeMillis()
        createAppLayout(savedInstanceState)
    }

    private fun loadConfiguration(): Boolean {
        return try {
            val json = assets.open("build-config.json")
                .bufferedReader()
                .use { it.readText() }

            val config = JSONObject(json)

            // URL ডিকোড করা হচ্ছে (যদি Base64 এ থাকে)
            val rawUrl = config.optString("website_url")
            websiteUrl = try {
                String(Base64.decode(rawUrl, Base64.DEFAULT))
            } catch (e: Exception) {
                rawUrl // যদি প্লেইন টেক্সট থাকে, তবে সেটাই ব্যবহার হবে
            }

            appName = config.optString("app_name", "Web2APK")

            val features = config.optJSONObject("features")
            backButtonEnabled = features?.optBoolean("back_button", true) ?: true
            fullscreenEnabled = features?.optBoolean("fullscreen", false) ?: false
            downloadEnabled = features?.optBoolean("download", true) ?: true

            val uri = Uri.parse(websiteUrl)

            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                "Invalid HTTPS website URL"
            }

            true

        } catch (e: Exception) {
            Toast.makeText(this, "Config missing or invalid", Toast.LENGTH_LONG).show()
            finish()
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAppLayout(savedInstanceState: Bundle?) {

        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        webView = WebView(this)
        webView.visibility = View.INVISIBLE

        setupWebView()

        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        loadingView = CleanLoadingView(this)
        root.addView(loadingView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        if (savedInstanceState != null) {
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) {
                webView.loadUrl(websiteUrl)
            }
        } else {
            webView.loadUrl(websiteUrl)
        }

        handler.postDelayed(hideSplashRunnable, minimumSplashTime)
    }

    private fun removeSplashIfReady() {
        if (splashRemoved || isFinishing || isDestroyed) return

        val elapsed = SystemClock.uptimeMillis() - splashStartedAt
        if (!pageLoaded || elapsed < minimumSplashTime) {
            val remaining = (minimumSplashTime - elapsed).coerceAtLeast(100L)
            handler.removeCallbacks(hideSplashRunnable)
            handler.postDelayed(hideSplashRunnable, remaining)
            return
        }

        splashRemoved = true
        webView.visibility = View.VISIBLE
        root.removeView(loadingView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = true
        }

        CookieManager.getInstance().setAcceptCookie(true)

        // জাভাস্ক্রিপ্ট ইন্টারফেস (যাতে কাস্টম এরর পেজের রিট্রাই বাটন কাজ করে)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun retry() {
                runOnUiThread {
                    webView.visibility = View.INVISIBLE
                    loadingView.visibility = View.VISIBLE
                    splashStartedAt = SystemClock.uptimeMillis()
                    splashRemoved = false
                    pageLoaded = false
                    handler.postDelayed(hideSplashRunnable, minimumSplashTime)
                    webView.loadUrl(websiteUrl)
                }
            }
        }, "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    else -> true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // যদি কাস্টম এরর পেজ লোড হয়, তবে স্প্ল্যাশ সরাবে না
                if (url != "about:blank") {
                    pageLoaded = true
                    removeSplashIfReady()
                }
            }

            // ইন্টারনেট না থাকলে বা ওয়েবসাইট লোড না হলে এই ফাংশনটি কল হয়
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showCustomErrorPage()
                }
            }
        }

        webView.webChromeClient = WebChromeClient()

        if (downloadEnabled) setupDownload()
    }

    // কাস্টম এরর পেজ ডিজাইন (অ্যাপের মতো দেখতে)
    private fun showCustomErrorPage() {
        pageLoaded = true
        removeSplashIfReady() // স্প্ল্যাশ সরিয়ে ফেলবে

        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        margin: 0;
                        background-color: #ffffff;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        text-align: center;
                    }
                    .icon { font-size: 70px; margin-bottom: 15px; }
                    h2 { color: #183f35; margin-bottom: 10px; font-weight: 600; }
                    p { color: #666666; font-size: 15px; padding: 0 30px; margin-bottom: 30px; line-height: 1.5; }
                    .btn {
                        background-color: #183f35;
                        color: white;
                        border: none;
                        padding: 14px 35px;
                        border-radius: 30px;
                        font-size: 16px;
                        font-weight: bold;
                        cursor: pointer;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.1);
                    }
                    .btn:active { background-color: #0f2a22; }
                </style>
            </head>
            <body>
                <div class="icon">📡</div>
                <h2>No Internet Connection</h2>
                <p>Please check your internet connection and try again to continue using the app.</p>
                <button class="btn" onclick="AndroidApp.retry()">Retry</button>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
    }

    private fun setupDownload() {
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val uri = Uri.parse(url)
                if (uri.scheme != "https" && uri.scheme != "http") {
                    Toast.makeText(this, "Not supported", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val request = DownloadManager.Request(uri)
                request.setTitle(fileName)
                request.setDescription("Downloading from $appName")
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) request.addRequestHeader("Cookie", cookie)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun enableFullscreen() {
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (backButtonEnabled && ::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideSplashRunnable)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private class CleanLoadingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val startTime = SystemClock.uptimeMillis()
        private val primaryColor = Color.parseColor("#183f35")

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val time = (SystemClock.uptimeMillis() - startTime) / 1000f
            val density = resources.displayMetrics.density

            canvas.drawColor(Color.WHITE)

            val cx = w / 2f
            val cy = h / 2f
            val radius = 35f * density

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = primaryColor
            paint.alpha = 40 
            canvas.drawCircle(cx, cy, radius, paint)

            paint.alpha = 255
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(bounds, time * 360f, 90f, false, paint)

            postInvalidateOnAnimation()
        }
    }
}
