package top.rootu.lampa.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.MainActivity
import top.rootu.lampa.R
import top.rootu.lampa.helpers.Helpers.printLog
import top.rootu.lampa.helpers.getAppVersion
import top.rootu.lampa.helpers.getNetworkErrorString
import top.rootu.lampa.helpers.isAttachedToWindowCompat


// https://developer.android.com/develop/ui/views/layout/webapps/webview#kotlin
class SysView(override val mainActivity: MainActivity, override val viewResId: Int) : Browser {
    private var browser: WebView? = null
    override var isDestroyed = false
    val TAG = "WEBVIEW"

    @SuppressLint("SetJavaScriptEnabled")
    override fun initialize() {
        browser = mainActivity.findViewById(viewResId)
        browser?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // No Scrollbars at ALL
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isScrollContainer = false
        }
        setFocus()
        val settings = browser?.settings
        settings?.apply {
            javaScriptEnabled = true
            builtInZoomControls = false
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            setNeedInitialFocus(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings?.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings?.mediaPlaybackRequiresUserGesture = false
        }

        browser?.webViewClient = object : WebViewClientCompat() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                mainActivity.onBrowserPageFinished(view, url)
            }

            // https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20java.lang.String)
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                printLog("shouldOverrideUrlLoading(view, url) view $view url $url")
                url?.let {
                    if (it.startsWith("tg://")) {
                        // Handle Telegram link
                        if (isTelegramAppInstalled()) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            mainActivity.startActivity(intent)
                        } else {
                            App.toast("Telegram app is not installed. Get in on Google Play.")
                            redirectToTelegramPlayStore()
                        }
                        return true // Indicate that the URL has been handled
                    }
                }
                return false // Load the URL in the WebView for other links
                // this will fail for non-http(s) links like lampa:// intent:// etc
                //return super.shouldOverrideUrlLoading(view, url)
            }

            // https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20android.webkit.WebResourceRequest)
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                printLog("shouldOverrideUrlLoading(view, request) view $view request $request")
                if (request.url.scheme.equals("tg", true)) {
                    if (isTelegramAppInstalled()) {
                        val intent = Intent(Intent.ACTION_VIEW, request.url)
                        mainActivity.startActivity(intent)
                    } else {
                        App.toast("Telegram app is not installed. Get in on Google Play.")
                        redirectToTelegramPlayStore()
                    }
                    return true // Indicate that the URL has been handled
                }
                return false // Load the URL in the WebView for other links
                // this will fail for non-http(s) links like lampa:// intent:// etc
                //return super.shouldOverrideUrlLoading(view, request)
            }

            private fun isTelegramAppInstalled(): Boolean {
                return try {
                    // Check if the Telegram app is installed by querying its package name
                    mainActivity.packageManager.getPackageInfo(
                        "org.telegram.messenger",
                        PackageManager.GET_ACTIVITIES
                    )
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    // Telegram app is not installed
                    false
                }
            }

            private fun redirectToTelegramPlayStore() {
                // Open the Telegram app page on the Play Store
                val playStoreIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=org.telegram.messenger")
                )
                try {
                    if ((getAppVersion(mainActivity, "com.android.vending")?.versionNumber
                            ?: 0L) > 0L
                    ) mainActivity.startActivity(playStoreIntent)
                } catch (_: ActivityNotFoundException) {
                }
            }

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun onReceivedError( // Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceErrorCompat
            ) {
                if (WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE
                    ) &&
                    WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION
                    )
                ) {
                    printLog("ERROR ${error.errorCode} ${error.description} on load ${request.url}")
                    if (request.url.toString().trimEnd('/')
                            .equals(MainActivity.LAMPA_URL, true)
                    ) {
                        view.loadUrl("about:blank")
                        // net::ERR_INTERNET_DISCONNECTED [-2]
                        // net::ERR_NAME_NOT_RESOLVED [-2]
                        // net::ERR_TIMED_OUT [-8]
                        // ...
                        val reason = view.context.getNetworkErrorString(error.description.toString())
                        val msg = "${
                            view.context.getString(R.string.download_failed_message)
                        } ${MainActivity.LAMPA_URL} – $reason"
                        if (error.description == "net::ERR_INTERNET_DISCONNECTED") {
                            val htmlData =
                                "<html><body style=\"margin:0;padding:0;overflow:hidden;\"><div style=\"display:table;width:100%;height:100vh;overflow:hidden;\"><div align=\"center\" style=\"display:table-cell;vertical-align:middle;\"><svg width=\"120\" height=\"120\" style=\"overflow:visible;enable-background:new 0 0 120 120\" viewBox=\"0 0 32 32\" width=\"32\" xml:space=\"preserve\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><g><g id=\"Error_1_\"><g id=\"Error\"><circle cx=\"16\" cy=\"16\" id=\"BG\" r=\"16\" style=\"fill:#D72828;\"/><path d=\"M14.5,25h3v-3h-3V25z M14.5,6v13h3V6H14.5z\" id=\"Exclamatory_x5F_Sign\" style=\"fill:#E6E6E6;\"/></g></g></g></svg><br/><br/><p style=\"color:#E6E6E6;\">${
                                    view.context.getString(
                                        R.string.net_error_internet_disconnected
                                    )
                                }</p></div></div></body>"
                            view.loadDataWithBaseURL(null, htmlData, "text/html", "UTF-8", null)
                            view.invalidate()
                        } else
                            mainActivity.showUrlInputDialog(msg)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError( // Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                printLog("ERROR $errorCode $description on load $failingUrl")
                if (failingUrl.toString().trimEnd('/').equals(MainActivity.LAMPA_URL, true)) {
                    view?.loadUrl("about:blank")
                    val reason = App.context.getNetworkErrorString(description.toString())
                    val msg =
                        "${App.context.getString(R.string.download_failed_message)} ${MainActivity.LAMPA_URL} – $reason"
                    if (description == "net::ERR_INTERNET_DISCONNECTED") {
                        val htmlData =
                            "<html><body style=\"margin:0;padding:0;overflow:hidden;\"><div style=\"display:table;width:100%;height:100vh;overflow:hidden;\"><div align=\"center\" style=\"display:table-cell;vertical-align:middle;\"><svg width=\"120\" height=\"120\" style=\"overflow:visible;enable-background:new 0 0 120 120\" viewBox=\"0 0 32 32\" width=\"32\" xml:space=\"preserve\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><g><g id=\"Error_1_\"><g id=\"Error\"><circle cx=\"16\" cy=\"16\" id=\"BG\" r=\"16\" style=\"fill:#D72828;\"/><path d=\"M14.5,25h3v-3h-3V25z M14.5,6v13h3V6H14.5z\" id=\"Exclamatory_x5F_Sign\" style=\"fill:#E6E6E6;\"/></g></g></g></svg><br/><br/><p style=\"color:#E6E6E6;\">${
                                App.context.getString(
                                    R.string.net_error_internet_disconnected
                                )
                            }</p></div></div></body>"
                        view?.loadDataWithBaseURL(null, htmlData, "text/html", "UTF-8", null)
                        view?.invalidate()
                    } else
                        mainActivity.showUrlInputDialog(msg)
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                printLog("Ignore SSL error: $error")
                handler?.proceed() // Ignore SSL certificate errors
            }
        }

        if (BuildConfig.DEBUG)
            browser?.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    Log.d("$TAG CONSOLE", consoleMessage.message())
                    return true
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    if (message != null) {
                        App.toast(message)
                    }
                    return false
                }
            }

        mainActivity.onBrowserInitCompleted()
    }

    override fun setUserAgentString(ua: String?) {
        browser?.settings?.userAgentString = ua
    }

    override fun getUserAgentString(): String? {
        return browser?.settings?.userAgentString
    }

    @SuppressLint("AddJavascriptInterface", "JavascriptInterface")
    override fun addJavascriptInterface(jsObject: Any, name: String) {
        browser?.addJavascriptInterface(jsObject, name)
    }

    override fun loadUrl(url: String) {
        browser?.loadUrl(url)
    }

    override fun pauseTimers() {
        browser?.pauseTimers()
    }

    override fun resumeTimers() {
        browser?.resumeTimers()
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun evaluateJavascript(script: String, resultCallback: (String) -> Unit) {
        browser?.evaluateJavascript(script, resultCallback)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        browser?.clearCache(includeDiskFiles)
    }

    override fun destroy() {
        browser?.let {
            // Remove from parent if attached
            if (it.isAttachedToWindowCompat())
                (it.parent as? ViewGroup)?.removeView(it)
        }
        browser?.stopLoading()
        browser?.destroy()
        isDestroyed = true
    }

    override fun setBackgroundColor(color: Int) {
        browser?.setBackgroundColor(color)
    }

    override fun canGoBack(): Boolean {
        return browser?.canGoBack() == true
    }

    override fun goBack() {
        browser?.goBack()
    }

    override fun setFocus() {
        browser?.requestFocus(View.FOCUS_DOWN)
    }

    override fun getView(): View? {
        return browser
    }

}