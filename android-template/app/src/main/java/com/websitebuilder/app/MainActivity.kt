package com.websitebuilder.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private var appName = "Web2APK"
    private var websiteUrl = ""
    private var backButtonEnabled = true
    private var fullscreenEnabled = false
    private var downloadEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        if (savedInstanceState != null) {
            openWebsite(savedInstanceState)
            return
        }

        try {
            val splashView = ImageView(this)

            val splashImage = assets.open("splash.png")
                .use { android.graphics.BitmapFactory.decodeStream(it) }

            splashView.setImageBitmap(splashImage)
            splashView.scaleType = ImageView.ScaleType.FIT_CENTER
            splashView.setBackgroundColor(android.graphics.Color.BLACK)

            setContentView(splashView)

            splashView.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    openWebsite(null)
                }
            }, 2000)

        } catch (e: Exception) {
            openWebsite(null)
        }
    }

    private fun openWebsite(savedInstanceState: Bundle?) {

        webView = WebView(this)
        setContentView(webView)

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(websiteUrl)
        }
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
