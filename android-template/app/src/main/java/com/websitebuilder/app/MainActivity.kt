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
import android.view.MotionEvent
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
    private val minimumSplashTime = 3100L // ৩.১ সেকেন্ড

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

        // ইন্টারনেট না থাকলে স্প্ল্যাশ সরাবো না
        if (!isNetworkAvailable()) {
            loadingView.showLostInternet()
            handler.removeCallbacks(hideSplashRunnable)
            handler.postDelayed(hideSplashRunnable, 500L)
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

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun retry() {
                runOnUiThread {
                    webView.visibility = View.INVISIBLE
                    loadingView.visibility = View.VISIBLE
                    loadingView.reset()
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

        // ইন্টারনেট না থাকলে স্প্ল্যাশেই Lost Internet দেখাবো
        if (!isNetworkAvailable()) {
            loadingView.showLostInternet()
            return
        }

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

    // রিয়েল-টাইম ইন্টারনেট চেক
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
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

    // নতুন আপডেট করা লোডিং এনিমেশন ক্লাস
    private inner class CleanLoadingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startTime = SystemClock.uptimeMillis()

        private val primaryColor = Color.parseColor("#183f35")

        // ধাপগুলোর সময় (মিলিসেকেন্ডে)
        private val phase1End = 2000L  // Connecting Lotto Server (0-2s)
        private val phase2End = 2600L  // Checking Authentication (2-2.6s)
        private val phase3End = 3100L  // Success (2.6-3.1s)

        private var lostInternetMode = false
        private var retryButtonRect = RectF()

        init {
            textPaint.color = Color.WHITE
            textPaint.textSize = 16f * resources.displayMetrics.density
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        fun reset() {
            startTime = SystemClock.uptimeMillis()
            lostInternetMode = false
            invalidate()
        }

        fun showLostInternet() {
            if (lostInternetMode) return
            lostInternetMode = true
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val elapsed = SystemClock.uptimeMillis() - startTime
            val time = elapsed / 1000f
            val density = resources.displayMetrics.density

            canvas.drawColor(primaryColor)

            val cx = w / 2f
            val cy = h / 2f
            val radius = 35f * density

            // Lost Internet মোড
            if (lostInternetMode) {
                drawLostInternet(canvas, cx, cy, radius, density)
                return
            }

            // স্পিনার
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            paint.alpha = 80
            canvas.drawCircle(cx, cy, radius, paint)

            paint.alpha = 255
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            // Success ধাপে পুরো বৃত্ত দেখাবো
            if (elapsed >= phase2End) {
                canvas.drawCircle(cx, cy, radius, paint)
                // সবুজ চেকমার্ক
                val successPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 5f * density
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val checkPath = android.graphics.Path().apply {
                    moveTo(cx - radius * 0.4f, cy)
                    lineTo(cx - radius * 0.05f, cy + radius * 0.35f)
                    lineTo(cx + radius * 0.45f, cy - radius * 0.3f)
                }
                canvas.drawPath(checkPath, successPaint)
            } else {
                canvas.drawArc(bounds, time * 360f, 90f, false, paint)
            }

            // টেক্সট ধাপ
            val text: String = when {
                elapsed < phase1End -> "Connecting Lotto Server.."
                elapsed < phase2End -> "Checking Authentication"
                else -> "Success"
            }
            canvas.drawText(text, cx, cy + radius + (30f * density), textPaint)

            postInvalidateOnAnimation()
        }

        private fun drawLostInternet(canvas: Canvas, cx: Float, cy: Float, radius: Float, density: Float) {
            // 📡 আইকন (ইমোজি না এঁকে সিম্পল সিগন্যাল বার)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE

            // সিগন্যাল বার (৪টি, শেষটা ফাঁকা = নো সিগন্যাল)
            val barW = 6f * density
            val gap = 5f * density
            val totalW = 4 * barW + 3 * gap
            var bx = cx - totalW / 2f
            val baseY = cy + radius * 0.3f
            for (i in 0..3) {
                val barH = (12f + i * 8f) * density
                paint.alpha = if (i < 3) 255 else 60
                canvas.drawRoundRect(
                    RectF(bx, baseY - barH, bx + barW, baseY),
                    2f * density, 2f * density, paint
                )
                bx += barW + gap
            }
            paint.alpha = 255

            // টেক্সট
            textPaint.textSize = 22f * density
            canvas.drawText("Lost Internet", cx, cy + radius + 50f * density, textPaint)

            textPaint.textSize = 13f * density
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("Please check your connection", cx, cy + radius + 75f * density, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            // রিট্রাই বাটন
            val btnW = 160f * density
            val btnH = 44f * density
            val btnTop = cy + radius + 95f * density
            retryButtonRect.set(cx - btnW / 2f, btnTop, cx + btnW / 2f, btnTop + btnH)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawRoundRect(retryButtonRect, btnH / 2f, btnH / 2f, paint)

            textPaint.color = primaryColor
            textPaint.textSize = 15f * density
            canvas.drawText("Try Again", cx, btnTop + btnH / 2f + 5f * density, textPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 16f * density
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (lostInternetMode && event.action == MotionEvent.ACTION_UP) {
                if (retryButtonRect.contains(event.x, event.y)) {
                    // রিট্রাই করি
                    lostInternetMode = false
                    reset()
                    if (::webView.isInitialized) {
                        webView.visibility = View.INVISIBLE
                        splashRemoved = false
                        pageLoaded = false
                        splashStartedAt = SystemClock.uptimeMillis()
                        handler.removeCallbacks(hideSplashRunnable)
                        handler.postDelayed(hideSplashRunnable, minimumSplashTime)
                        webView.loadUrl(websiteUrl)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
