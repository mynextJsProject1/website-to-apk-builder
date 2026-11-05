
package com.websitebuilder.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private lateinit var loadingOverlay: FrameLayout

    private val backgroundColor = Color.rgb(7, 17, 15)
    private val accentColor = Color.rgb(0, 231, 1)

    private var appName = "Web2APK"
    private var websiteUrl = ""
    private var backButtonEnabled = true
    private var fullscreenEnabled = false
    private var downloadEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

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

    private fun showSplashScreen(savedInstanceState: Bundle?) {
        openWebsite(savedInstanceState)
    }

    private fun openWebsite(savedInstanceState: Bundle?) {

        rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(backgroundColor)

        webView = WebView(this)
        webView.setBackgroundColor(backgroundColor)

        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        loadingOverlay = createLoadingOverlay()

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

    private fun createLoadingOverlay(): FrameLayout {

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(backgroundColor)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER

        val imageView = ImageView(this)

        try {
            val bitmap = assets.open("splash.png").use {
                android.graphics.BitmapFactory.decodeStream(it)
            }

            imageView.setImageBitmap(bitmap)

        } catch (e: Exception) {
            imageView.setImageResource(
                android.R.drawable.sym_def_app_icon
            )
        }

        imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        val imageSize = dp(150)

        content.addView(
            imageView,
            LinearLayout.LayoutParams(imageSize, imageSize)
        )

        val spinner = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleLarge
        )

        spinner.isIndeterminate = true
        spinner.indeterminateTintList =
            ColorStateList.valueOf(accentColor)

        val spinnerParams = LinearLayout.LayoutParams(
            dp(42),
            dp(42)
        )

        spinnerParams.topMargin = dp(30)

        content.addView(spinner, spinnerParams)

        val loadingText = TextView(this)

        loadingText.text = "Loading..."
        loadingText.textSize = 15f
        loadingText.setTextColor(Color.WHITE)
        loadingText.gravity = Gravity.CENTER
        loadingText.letterSpacing = 0.08f

        val textParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        textParams.topMargin = dp(18)

        content.addView(loadingText, textParams)

        val nameText = TextView(this)

        nameText.text = appName
        nameText.textSize = 13f
        nameText.setTextColor(Color.LTGRAY)
        nameText.gravity = Gravity.CENTER

        val nameParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        nameParams.topMargin = dp(10)

        content.addView(nameText, nameParams)

        overlay.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        return overlay
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

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)

                loadingOverlay.visibility = View.VISIBLE
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)

                loadingOverlay.visibility = View.GONE
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
                    loadingOverlay.visibility = View.GONE

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
                url, userAgent, contentDisposition,
                mimeType, _ ->

                try {
                    val uri = Uri.parse(url)

                    if (uri.scheme != "https" &&
                        uri.scheme != "http") {

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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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

        if (backButtonEnabled && ::webView.isInitialized &&
            webView.canGoBack()) {

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
}
