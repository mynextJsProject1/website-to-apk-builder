package com.websitebuilder.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private var waitingForNetwork = false

    private val handler = Handler(Looper.getMainLooper())
    private val minimumSplashTime = 3600L // ৩.৬ সেকেন্ড (2 + 1 + 0.6)

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

            val rawUrl = config.optString("website_url")
            websiteUrl = try {
                String(Base64.decode(rawUrl, Base64.DEFAULT))
            } catch (e: Exception) {
                rawUrl
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
        root.setBackgroundColor(Color.parseColor("#183f35"))

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

        // প্রথমে ইন্টারনেট চেক
        if (!isNetworkAvailable()) {
            waitingForNetwork = true
            showInternetErrorInLoading()
            return
        }

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

    // ইন্টারনেট আছে কিনা রিয়েল-টাইম চেক
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // লোডিং ভিউতে ইন্টারনেট এরর দেখানো (Try Again সহ)
    private fun showInternetErrorInLoading() {
        loadingView.showNetworkError { 
            // Try Again ক্লিক হলে আবার চেক
            if (isNetworkAvailable()) {
                waitingForNetwork = false
                loadingView.showNormalLoading()
                splashStartedAt = SystemClock.uptimeMillis()
                pageLoaded = false
                splashRemoved = false
                webView.loadUrl(websiteUrl)
                handler.postDelayed(hideSplashRunnable, minimumSplashTime)
            } else {
                Toast.makeText(this, "Still no internet connection", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ 
                    if (!splashRemoved) showInternetErrorInLoading() 
                }, 1500)
            }
        }
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
            // ওয়েবসাইটের আপডেট সবসময় নতুন করে লোড করার জন্য ক্যাশে মোড আপডেট করা হলো
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        // অ্যাপ ওপেন হওয়ার সময় ক্যাশে এবং হিস্ট্রি ক্লিয়ার করা হচ্ছে
        webView.clearCache(true)
        webView.clearHistory()

        CookieManager.getInstance().setAcceptCookie(true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun retry() {
                runOnUiThread {
                    if (isNetworkAvailable()) {
                        webView.visibility = View.INVISIBLE
                        loadingView.visibility = View.VISIBLE
                        loadingView.showNormalLoading()
                        splashStartedAt = SystemClock.uptimeMillis()
                        splashRemoved = false
                        pageLoaded = false
                        waitingForNetwork = false
                        // রিট্রাই করার সময়ও ক্যাশে ক্লিয়ার করা হচ্ছে
                        webView.clearCache(true)
                        handler.postDelayed(hideSplashRunnable, minimumSplashTime)
                        webView.loadUrl(websiteUrl)
                    } else {
                        Toast.makeText(this@MainActivity, "No internet connection", Toast.LENGTH_SHORT).show()
                        showCustomErrorPage()
                    }
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
                if (url != "about:blank") {
                    pageLoaded = true
                    removeSplashIfReady()
                }
            }

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

    private fun showCustomErrorPage() {
        pageLoaded = true
        removeSplashIfReady()

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
                    h2 { color: #183f35; margin-bottom: 10px; font-weight: bold; font-size: 24px; }
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
                <h2>Lost Internet</h2>
                <p>Your internet connection is currently offline. Please check your Wi-Fi or mobile data connection and try again.</p>
                <button class="btn" onclick="AndroidApp.retry()">Try Again</button>
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

    // আপডেট করা লোডিং এনিমেশন ক্লাস - ৩ ধাপে টেক্সট পরিবর্তন
    private class CleanLoadingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startTime = SystemClock.uptimeMillis()
        
        private val primaryColor = Color.parseColor("#183f35")
        
        // নেটওয়ার্ক এরর স্টেট
        private var showError = false
        private var errorStartTime = 0L
        private var onRetryClick: (() -> Unit)? = null
        private val errorButtonRect = RectF()

        init {
            textPaint.color = Color.WHITE
            textPaint.textSize = 16f * resources.displayMetrics.density
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            
            errorPaint.color = Color.WHITE
            errorPaint.textAlign = Paint.Align.CENTER
            errorPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        fun showNetworkError(onRetry: () -> Unit) {
            showError = true
            errorStartTime = SystemClock.uptimeMillis()
            onRetryClick = onRetry
            invalidate()
        }

        fun showNormalLoading() {
            showError = false
            onRetryClick = null
            startTime = SystemClock.uptimeMillis()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val density = resources.displayMetrics.density
            val cx = w / 2f
            val cy = h / 2f

            // ব্যাকগ্রাউন্ড
            canvas.drawColor(primaryColor)

            if (showError) {
                drawNetworkError(canvas, cx, cy, density)
            } else {
                drawNormalLoading(canvas, cx, cy, density)
            }
        }

        private fun drawNormalLoading(canvas: Canvas, cx: Float, cy: Float, density: Float) {
            val time = (SystemClock.uptimeMillis() - startTime) / 1000f
            val radius = 35f * density

            // স্পিনার
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            paint.alpha = 80
            canvas.drawCircle(cx, cy, radius, paint)

            paint.alpha = 255
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(bounds, time * 360f, 90f, false, paint)

            // টেক্সট পরিবর্তন: 
            // 0 - 2 সেকেন্ড: "Connecting Lotto Server.."
            // 2 - 3 সেকেন্ড: "Checking Authentication"
            // 3 - 3.6 সেকেন্ড: "Authentication Verified"
            val text: String
            val textColor: Int
            when {
                time < 2.0f -> {
                    text = "Connecting Lotto Server.."
                    textColor = Color.WHITE
                }
                time < 3.0f -> {
                    text = "Checking Authentication"
                    textColor = Color.WHITE
                }
                else -> {
                    text = "Authentication Verified"
                    textColor = Color.parseColor("#7CFC00") // সবুজ চেকমার্ক কালার
                }
            }
            
            textPaint.color = textColor
            canvas.drawText(text, cx, cy + radius + (30f * density), textPaint)

            postInvalidateOnAnimation()
        }

        private fun drawNetworkError(canvas: Canvas, cx: Float, cy: Float, density: Float) {
            val time = (SystemClock.uptimeMillis() - errorStartTime) / 1000f
            
            // 📡 আইকন (ইমোজি)
            errorPaint.textSize = 70f * density
            errorPaint.color = Color.WHITE
            canvas.drawText("📡", cx, cy - 80f * density, errorPaint)

            // "Lost Internet" টেক্সট
            errorPaint.textSize = 24f * density
            errorPaint.color = Color.WHITE
            canvas.drawText("Lost Internet", cx, cy - 20f * density, errorPaint)

            // বর্ণনা
            errorPaint.textSize = 14f * density
            errorPaint.color = Color.parseColor("#CCCCCC")
            val desc1 = "Your internet connection is offline."
            val desc2 = "Please check your connection and try again."
            canvas.drawText(desc1, cx, cy + 20f * density, errorPaint)
            canvas.drawText(desc2, cx, cy + 45f * density, errorPaint)

            // Try Again বাটন
            val btnWidth = 160f * density
            val btnHeight = 48f * density
            val btnLeft = cx - btnWidth / 2
            val btnTop = cy + 80f * density
            errorButtonRect.set(btnLeft, btnTop, btnLeft + btnWidth, btnTop + btnHeight)

            // বাটন ব্যাকগ্রাউন্ড (পালসিং ইফেক্ট)
            val pulse = (0.85f + 0.15f * kotlin.math.sin(time * 4f).toFloat())
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.alpha = (255 * pulse).toInt()
            val cornerRadius = btnHeight / 2
            canvas.drawRoundRect(errorButtonRect, cornerRadius, cornerRadius, paint)

            // বাটন টেক্সট
            errorPaint.textSize = 16f * density
            errorPaint.color = primaryColor
            val textY = btnTop + btnHeight / 2 - (errorPaint.descent() + errorPaint.ascent()) / 2
            canvas.drawText("Try Again", cx, textY, errorPaint)

            postInvalidateOnAnimation()
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (showError && event.action == android.view.MotionEvent.ACTION_UP) {
                if (errorButtonRect.contains(event.x, event.y)) {
                    onRetryClick?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
