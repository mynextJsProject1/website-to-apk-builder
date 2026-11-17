package com.websitebuilder.app

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
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
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

    private val minimumSplashTime = 2500L // লোডিং টাইম ২.৫ সেকেন্ড

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

            websiteUrl = config.optString("website_url")
            appName = config.optString("app_name", "Web2APK")

            val features = config.optJSONObject("features")

            backButtonEnabled =
                features?.optBoolean("back_button", true) ?: true

            fullscreenEnabled =
                features?.optBoolean("fullscreen", false) ?: false

            downloadEnabled =
                features?.optBoolean("download", true) ?: true

            val uri = Uri.parse(websiteUrl)

            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                "Invalid HTTPS website URL"
            }

            true

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "App configuration missing or invalid",
                Toast.LENGTH_LONG
            ).show()

            finish()
            false
        }
    }

    private fun createAppLayout(savedInstanceState: Bundle?) {

        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        webView = WebView(this)
        webView.visibility = View.INVISIBLE

        setupWebView()

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // নতুন ক্লিন এনিমেশন ভিউ
        loadingView = CleanLoadingView(this)

        root.addView(
            loadingView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)

        if (savedInstanceState != null) {
            val restored = webView.restoreState(savedInstanceState)

            if (restored == null) {
                webView.loadUrl(websiteUrl)
            }
        } else {
            webView.loadUrl(websiteUrl)
        }

        handler.postDelayed(
            hideSplashRunnable,
            minimumSplashTime
        )
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

    private fun setupWebView() {

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = true
            builtInZoomControls = false
            displayZoomControls = false
        }

        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                val uri = request?.url ?: return true

                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    else -> true
                }
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)

                pageLoaded = true
                removeSplashIfReady()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    pageLoaded = true
                    removeSplashIfReady()
                }
            }
        }

        webView.webChromeClient = WebChromeClient()

        if (downloadEnabled) {
            setupDownload()
        }
    }

    private fun setupDownload() {

        webView.setDownloadListener(
            DownloadListener {
                url, userAgent, contentDisposition, mimeType, _ ->

                try {
                    val uri = Uri.parse(url)

                    if (uri.scheme != "https" &&
                        uri.scheme != "http"
                    ) {
                        Toast.makeText(
                            this,
                            "This download type is not supported",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@DownloadListener
                    }

                    val fileName = URLUtil.guessFileName(
                        url,
                        contentDisposition,
                        mimeType
                    ).replace(Regex("[^a-zA-Z0-9._-]"), "_")

                    val request = DownloadManager.Request(uri)

                    request.setTitle(fileName)
                    request.setDescription("Downloading from $appName")
                    request.setMimeType(mimeType)
                    request.addRequestHeader("User-Agent", userAgent)

                    val cookie = CookieManager.getInstance()
                        .getCookie(url)

                    if (!cookie.isNullOrEmpty()) {
                        request.addRequestHeader("Cookie", cookie)
                    }

                    request.setNotificationVisibility(
                        DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )

                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )

                    val manager = getSystemService(
                        Context.DOWNLOAD_SERVICE
                    ) as DownloadManager

                    manager.enqueue(request)

                    Toast.makeText(
                        this,
                        "Download started",
                        Toast.LENGTH_SHORT
                    ).show()

                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Download failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun enableFullscreen() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (backButtonEnabled &&
            ::webView.isInitialized &&
            webView.canGoBack()
        ) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideSplashRunnable)

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }

    // নতুন কাস্টম এনিমেশন ক্লাস (কালার: #183f35)
    private class CleanLoadingView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val startTime = SystemClock.uptimeMillis()
        
        // আপনার দেওয়া কালার কোড
        private val primaryColor = Color.parseColor("#183f35")

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()

            if (w <= 0f || h <= 0f) return

            val time = (SystemClock.uptimeMillis() - startTime) / 1000f
            val density = resources.displayMetrics.density

            // ব্যাকগ্রাউন্ড কালার সাদা
            canvas.drawColor(Color.WHITE)

            val cx = w / 2f
            val cy = h / 2f
            val radius = 35f * density

            // ১. হালকা রঙের রিং (ট্র্যাক)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = primaryColor
            paint.alpha = 40 
            
            canvas.drawCircle(cx, cy, radius, paint)

            // ২. ঘোরানো এনিমেশন (স্পিনার)
            paint.alpha = 255 // ফুল কালার
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            
            // ৩৬০ ডিগ্রি ঘোরার জন্য
            canvas.drawArc(bounds, time * 360f, 90f, false, paint)

            // এনিমেশন চলমান রাখার জন্য রিভাইভ করা
            postInvalidateOnAnimation()
        }
    }
}
