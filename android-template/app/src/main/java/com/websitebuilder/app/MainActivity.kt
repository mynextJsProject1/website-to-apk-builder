
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
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingView: LotteryLoadingView

    private var appName = "Web2APK"
    private var websiteUrl = ""
    private var backButtonEnabled = true
    private var fullscreenEnabled = false
    private var downloadEnabled = true

    private var splashStartedAt = 0L
    private var pageLoaded = false
    private var splashRemoved = false

    private val handler = Handler(Looper.getMainLooper())

    private val minimumSplashTime = 3500L

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
        root.setBackgroundColor(Color.BLACK)

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

        loadingView = LotteryLoadingView(this)

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

    private class LotteryLoadingView(
        context: Context
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val gold = Color.rgb(218, 170, 65)
        private val lightGold = Color.rgb(255, 220, 130)
        private val darkGold = Color.rgb(115, 77, 22)

        private val startTime = SystemClock.uptimeMillis()

        private val balls = listOf(
            Ball(0.13f, 0.20f, 23f, 1.0f, 8),
            Ball(0.82f, 0.17f, 17f, 1.4f, 21),
            Ball(0.22f, 0.73f, 19f, 1.2f, 15),
            Ball(0.78f, 0.77f, 25f, 0.9f, 33),
            Ball(0.50f, 0.10f, 13f, 1.7f, 11),
            Ball(0.92f, 0.48f, 16f, 1.1f, 6),
            Ball(0.08f, 0.49f, 14f, 1.5f, 27)
        )

        private data class Ball(
            val x: Float,
            val y: Float,
            val radius: Float,
            val speed: Float,
            val number: Int
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()

            if (w <= 0f || h <= 0f) return

            val time = (SystemClock.uptimeMillis() - startTime) / 1000f

            canvas.drawColor(Color.rgb(5, 7, 6))

            drawDecorations(canvas, w, h, time)
            drawCenterSpinner(canvas, w, h, time)

            postInvalidateOnAnimation()
        }

        private fun drawDecorations(
            canvas: Canvas,
            w: Float,
            h: Float,
            time: Float
        ) {

            for (ball in balls) {

                val x = w * ball.x +
                    sin(time * ball.speed) * 18f

                val y = h * ball.y +
                    cos(time * ball.speed * 0.8f) * 24f

                val r = ball.radius *
                    resources.displayMetrics.density

                paint.color = gold
                paint.alpha = 48
                paint.style = Paint.Style.FILL

                canvas.drawCircle(x, y, r, paint)

                paint.color = lightGold
                paint.alpha = 52
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f

                canvas.drawCircle(x, y, r, paint)

                paint.style = Paint.Style.FILL
                paint.color = lightGold
                paint.alpha = 65
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = r * 0.85f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD

                canvas.drawText(
                    ball.number.toString(),
                    x,
                    y + r * 0.30f,
                    paint
                )
            }

            drawTicket(
                canvas,
                w * 0.16f + sin(time * 0.6f) * 16f,
                h * 0.37f + cos(time * 0.7f) * 18f,
                time * 12f
            )

            drawTicket(
                canvas,
                w * 0.82f + cos(time * 0.5f) * 14f,
                h * 0.60f + sin(time * 0.8f) * 16f,
                -time * 10f
            )

            for (i in 0 until 14) {
                val x = ((i * 79f + time * 13f) % w)
                val y = ((i * 113f + time * 19f) % h)

                paint.color = lightGold
                paint.alpha = 35
                paint.style = Paint.Style.FILL

                canvas.drawCircle(
                    x,
                    y,
                    2f * resources.displayMetrics.density,
                    paint
                )
            }
        }

        private fun drawTicket(
            canvas: Canvas,
            x: Float,
            y: Float,
            rotation: Float
        ) {

            val density = resources.displayMetrics.density

            val rect = RectF(
                x - 32f * density,
                y - 17f * density,
                x + 32f * density,
                y + 17f * density
            )

            canvas.save()
            canvas.rotate(rotation, x, y)

            paint.color = gold
            paint.alpha = 38
            paint.style = Paint.Style.FILL

            canvas.drawRoundRect(
                rect,
                5f * density,
                5f * density,
                paint
            )

            paint.color = lightGold
            paint.alpha = 55
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * density

            canvas.drawRoundRect(
                rect,
                5f * density,
                5f * density,
                paint
            )

            paint.style = Paint.Style.FILL
            paint.color = lightGold
            paint.alpha = 65
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f * density
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD

            canvas.drawText("LOTTO", x, y + 4f * density, paint)

            canvas.restore()
        }

        private fun drawCenterSpinner(
            canvas: Canvas,
            w: Float,
            h: Float,
            time: Float
        ) {

            val density = resources.displayMetrics.density
            val cx = w / 2f
            val cy = h / 2f

            val radius =  thirtyFive(density)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f * density
            paint.strokeCap = Paint.Cap.ROUND

            paint.color = darkGold
            paint.alpha = 100

            canvas.drawCircle(
                cx,
                cy,
                radius,
                paint
            )

            val bounds = RectF(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius
            )

            paint.color = lightGold
            paint.alpha = 230

            canvas.drawArc(
                bounds,
                time * 180f,
                105f,
                false,
                paint
            )

            paint.color = gold
            paint.alpha = 180

            canvas.drawArc(
                bounds,
                -time * 220f + 180f,
                65f,
                false,
                paint
            )

            paint.style = Paint.Style.FILL
            paint.color = lightGold
            paint.alpha = 230

            canvas.drawCircle(
                cx,
                cy,
                4f * density,
                paint
            )
        }

        private fun thirtyFive(density: Float): Float {
            return 35f * density
        }
    }
}
