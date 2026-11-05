
package com.websitebuilder.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import kotlin.math.*

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private lateinit var loadingOverlay: FrameLayout

    private var appName = "Web2APK"
    private var websiteUrl = ""

    private var backButtonEnabled = true
    private var fullscreenEnabled = false
    private var downloadEnabled = true

    private var splashStartedAt = 0L
    private var firstPageReady = false
    private var splashDismissed = false

    private val gold = Color.rgb(212, 165, 65)
    private val dark = Color.rgb(5, 7, 6)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(dark))
        window.statusBarColor = dark
        window.navigationBarColor = dark

        if (!loadConfiguration()) return

        title = appName

        if (fullscreenEnabled) {
            enableFullscreen()
        }

        showSplashScreen(savedInstanceState)
    }

    private fun loadConfiguration(): Boolean {
        return try {

            val json = assets.open("build-config.json")
                .bufferedReader()
                .use { it.readText() }

            val config = org.json.JSONObject(json)

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

    private fun showSplashScreen(savedInstanceState: Bundle?) {
        openWebsite(savedInstanceState)
    }

    private fun openWebsite(savedInstanceState: Bundle?) {

        splashStartedAt = SystemClock.elapsedRealtime()

        rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(dark)

        webView = WebView(this)
        webView.setBackgroundColor(dark)

        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        loadingOverlay = FrameLayout(this)
        loadingOverlay.setBackgroundColor(dark)

        val animation = LotteryLoadingView(this)

        loadingOverlay.addView(
            animation,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        rootLayout.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)

        setupWebView()

        if (savedInstanceState != null) {

            val restored = webView.restoreState(savedInstanceState)

            if (restored == null) {
                webView.loadUrl(websiteUrl)
            }

        } else {
            webView.loadUrl(websiteUrl)
        }
    }

    // Minimum 3.5 seconds + actual website loading completion

    private fun finishInitialLoading() {

        if (firstPageReady) return

        firstPageReady = true

        val elapsed = SystemClock.elapsedRealtime() - splashStartedAt
        val remaining = (3500L - elapsed).coerceAtLeast(0L)

        webView.postDelayed({

            if (!isFinishing && !isDestroyed && !splashDismissed) {

                splashDismissed = true
                loadingOverlay.visibility = View.GONE
            }

        }, remaining)
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

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)

                finishInitialLoading()
            }

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

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {

                    finishInitialLoading()

                    Toast.makeText(
                        this@MainActivity,
                        "Website loading failed. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
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

                    if (uri.scheme != "https" && uri.scheme != "http") {
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
                    ).replace(
                        Regex("[^a-zA-Z0-9._-]"),
                        "_"
                    )

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
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
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

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
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

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }

    // ==========================================
    // NATIVE GOLD LOTTERY CANVAS ANIMATION
    // ==========================================

    inner class LotteryLoadingView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val random = java.util.Random(27)

        private val goldLight = Color.rgb(255, 215, 125)
        private val goldDark = Color.rgb(132, 91, 25)

        private val balls = listOf(
            Ball(0.17f, 0.15f, 0.052f, "07", 0.2f),
            Ball(0.78f, 0.18f, 0.050f, "12", 1.2f),
            Ball(0.10f, 0.49f, 0.052f, "18", 2.1f),
            Ball(0.83f, 0.49f, 0.050f, "33", 3.2f),
            Ball(0.25f, 0.79f, 0.054f, "24", 4.1f),
            Ball(0.76f, 0.82f, 0.055f, "49", 5.2f)
        )

        private data class Ball(
            val x: Float,
            val y: Float,
            val radius: Float,
            val number: String,
            val phase: Float
        )

        private data class Star(
            val x: Float,
            val y: Float,
            val size: Float,
            val phase: Float
        )

        private val stars = List(22) {
            Star(
                random.nextFloat(),
                random.nextFloat(),
                1.5f + random.nextFloat() * 3f,
                random.nextFloat() * 6.28f
            )
        }

        private val startTime = SystemClock.uptimeMillis()

        init {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()

            if (w <= 0 || h <= 0) return

            val time =
                (SystemClock.uptimeMillis() - startTime) / 1000f

            canvas.drawColor(dark)

            drawStars(canvas, w, h, time)
            drawTickets(canvas, w, h, time)
            drawBalls(canvas, w, h, time)
            drawCenterSpinner(canvas, w, h, time)

            if (visibility == VISIBLE) {
                postInvalidateOnAnimation()
            }
        }

        // Subtle moving stars: approximately 20% opacity

        private fun drawStars(
            canvas: Canvas,
            w: Float,
            h: Float,
            time: Float
        ) {

            stars.forEach { star ->

                val x = (
                    star.x + sin(time * 0.18f + star.phase) * 0.025f
                ) * w

                val y = (
                    star.y + cos(time * 0.15f + star.phase) * 0.025f
                ) * h

                val alpha = (
                    25 + 15 * (0.5f + 0.5f *
                        sin(time * 1.2f + star.phase))
                ).toInt().coerceIn(15, 40)

                paint.color = Color.argb(alpha, 255, 190, 75)
                paint.style = Paint.Style.FILL

                val path = Path()

                for (i in 0 until 10) {

                    val angle = Math.PI * i / 5.0 - Math.PI / 2

                    val radius = if (i % 2 == 0) {
                        dp(star.size)
                    } else {
                        dp(star.size * 0.4f)
                    }

                    val px = x + cos(angle).toFloat() * radius
                    val py = y + sin(angle).toFloat() * radius

                    if (i == 0) path.moveTo(px, py)
                    else path.lineTo(px, py)
                }

                path.close()
                canvas.drawPath(path, paint)
            }
        }

        // Floating golden lottery tickets

        private fun drawTickets(
            canvas: Canvas,
            w: Float,
            h: Float,
            time: Float
        ) {

            val positions = listOf(
                0.43f to 0.12f,
                0.16f to 0.68f,
                0.85f to 0.68f
            )

            positions.forEachIndexed { index, position ->

                val x = position.first * w +
                    sin(time * 0.35f + index) * dp(12f)

                val y = position.second * h +
                    cos(time * 0.30f + index) * dp(12f)

                canvas.save()
                canvas.rotate(
                    -25f + sin(time * 0.25f + index) * 7f,
                    x,
                    y
                )

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = Color.argb(42, 230, 177, 75)

                val rect = RectF(
                    x - dp(23f),
                    y - dp(11f),
                    x + dp(23f),
                    y + dp(11f)
                )

                canvas.drawRoundRect(
                    rect,
                    dp(3f),
                    dp(3f),
                    paint
                )

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(45, 255, 195, 85)

                canvas.drawCircle(
                    x,
                    y,
                    dp(4f),
                    paint
                )

                canvas.restore()
            }
        }

        // Smooth moving gold lottery balls

        private fun drawBalls(
            canvas: Canvas,
            w: Float,
            h: Float,
            time: Float
        ) {

            balls.forEach { ball ->

                val x = (
                    ball.x + sin(time * 0.30f + ball.phase) * 0.025f
                ) * w

                val y = (
                    ball.y + cos(time * 0.25f + ball.phase) * 0.025f
                ) * h

                val r = min(w, h) * ball.radius

                val centerX = x - r * 0.28f
                val centerY = y - r * 0.35f

                paint.style = Paint.Style.FILL

                paint.shader = RadialGradient(
                    centerX,
                    centerY,
                    r * 1.8f,
                    intArrayOf(
                        Color.argb(65, 255, 220, 145),
                        Color.argb(42, 190, 135, 45),
                        Color.argb(30, 65, 42, 10)
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )

                canvas.drawCircle(x, y, r, paint)
                paint.shader = null

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = Color.argb(45, 255, 203, 105)

                canvas.drawCircle(x, y, r, paint)

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(55, 255, 225, 165)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = r * 0.78f
                paint.typeface = Typeface.create(
                    "sans-serif",
                    Typeface.BOLD
                )

                val fm = paint.fontMetrics

                val baseline = y - (fm.ascent + fm.descent) / 2

                canvas.drawText(
                    ball.number,
                    x,
                    baseline,
                    paint
                )
            }
        }

        // Center circular spinning gold loading symbol

        private fun drawCenterSpinner(
            canvas: Canvas,
            w: Float,
            h: Float,
            time: Float
        ) {

            val cx = w / 2f
            val cy = h / 2f

            val radius = dp(32f)

            val rotation = (time * 180f) % 360f

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(4f)
            paint.strokeCap = Paint.Cap.ROUND

            val rect = RectF(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius
            )

            for (i in 0 until 8) {

                val start = rotation + i * 45f

                val alpha = 45 + i * 24

                paint.color = Color.argb(
                    alpha.coerceAtMost(235),
                    255,
                    190,
                    85
                )

                canvas.drawArc(
                    rect,
                    start,
                    23f,
                    false,
                    paint
                )
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(22, 255, 190, 85)

            canvas.drawCircle(
                cx,
                cy,
                dp(21f),
                paint
            )

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = Color.argb(35, 255, 210, 125)

            canvas.drawCircle(
                cx,
                cy,
                radius + dp(9f),
                paint
            )

            paint.style = Paint.Style.FILL
        }
    }
}
