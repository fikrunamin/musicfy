package com.example.musicfy.ui.screens.ritme.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.musicfy.ritme.browser.RateLimiter
import com.example.musicfy.ritme.media.BrowserAddress
import com.example.musicfy.ritme.media.Navigation
import com.example.musicfy.ritme.media.PageMediaScript
import com.example.musicfy.ritme.media.PageReports
import com.example.musicfy.ui.screens.settings.importsync.chromeUserAgent
import com.example.musicfy.ui.screens.settings.importsync.hideAppIdentity

/** What the Ritme browser's WebView tells its screen. [onRequest] arrives on a WebView thread; the rest on the main thread. */
internal interface RitmeWebEvents {
    fun onPageStarted(url: String)
    fun onHistoryChanged(url: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onProgress(percent: Int)
    fun onRequest(url: String, method: String, headers: Map<String, String>)
    fun onPageReport(message: String, mainFrame: Boolean, origin: String)
    fun onProtectedMedia()
    fun onLocalAddressRefused()
    fun onExternalApp(url: String)
}

/** The Ritme browser's own WebView profile (U11): its cookies and storage never mix with the app's other web views. */
/** The page script throttles itself well below this; extra messages from one origin are dropped. */
private const val MAX_MESSAGES_PER_SECOND = 10

internal const val RITME_BROWSER_PROFILE = "ritme-browser"

internal fun ritmeProfileSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

/**
 * The browser's WebView (spec 4.7): its own profile where the device's WebView has profiles, JS on,
 * no file or content access, Safe Browsing on, one window (popups load here or are blocked),
 * downloads ignored, no app name header, the Chrome user agent. Detection: every request is shown
 * to [events] and never changed (shouldInterceptRequest always returns null, D9), and the page
 * script reports through a web message listener (D10; never addJavascriptInterface, which reaches
 * every frame without saying which).
 */
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
internal fun createRitmeWebView(context: Context, events: RitmeWebEvents): WebView {
    val view = WebView(context)
    // First of all: a profile can only be set before the view is used.
    if (ritmeProfileSupported()) {
        ProfileStore.getInstance().getOrCreateProfile(RITME_BROWSER_PROFILE)
        WebViewCompat.setProfile(view, RITME_BROWSER_PROFILE)
    }
    // A WebView without LayoutParams lays pages out at zero height (see CaptureWebView).
    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    with(view.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        safeBrowsingEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = false
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        userAgentString = chromeUserAgent(userAgentString)
    }
    view.hideAppIdentity()
    view.setDownloadListener { _, _, _, _, _ -> }
    val documentStart = installPageScript(view, events)
    view.webViewClient = RitmeWebClient(events, documentStart)
    view.webChromeClient = RitmeChromeClient(events)
    return view
}

/**
 * The page script and its listener. Every origin may post (rule "*": the rules cannot say "any
 * http(s) origin"), but only http(s) origins are listened to and the message is parsed as
 * untrusted. True when the script runs at document start in every frame.
 */
@SuppressLint("RequiresFeature")
private fun installPageScript(view: WebView, events: RitmeWebEvents): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
    val limiter = RateLimiter(limit = MAX_MESSAGES_PER_SECOND)
    WebViewCompat.addWebMessageListener(view, PageMediaScript.BRIDGE_NAME, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
        val scheme = sourceOrigin.scheme?.lowercase()
        val data = message.data
        if ((scheme == "https" || scheme == "http") && data != null && data.length <= PageReports.MAX_MESSAGE_CHARS &&
            limiter.allow(sourceOrigin.toString(), SystemClock.elapsedRealtime())
        ) {
            events.onPageReport(data, isMainFrame, sourceOrigin.toString())
        }
    }
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
    WebViewCompat.addDocumentStartJavaScript(view, PageMediaScript.source, setOf("*"))
    return true
}

private class RitmeWebClient(private val events: RitmeWebEvents, private val documentStart: Boolean) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        events.onRequest(request.url.toString(), request.method, request.requestHeaders.orEmpty())
        return null // never rewrite the page (spec D9)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (BrowserAddress.navigation(url, request.hasGesture())) {
            Navigation.ALLOW -> false
            Navigation.REFUSE_LOCAL -> {
                events.onLocalAddressRefused()
                true
            }
            Navigation.ASK_EXTERNAL -> {
                if (request.isForMainFrame) events.onExternalApp(url)
                true
            }
            Navigation.BLOCK -> true
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        url?.let(events::onPageStarted)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        // Without document-start scripts the page script goes in once the page is there; the
        // events it listens for from then on still count.
        if (!documentStart && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            view.evaluateJavascript(PageMediaScript.source, null)
        }
        url?.let { events.onHistoryChanged(it, view.canGoBack(), view.canGoForward()) }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        url?.let { events.onHistoryChanged(it, view.canGoBack(), view.canGoForward()) }
    }
}

private class RitmeChromeClient(private val events: RitmeWebEvents) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        events.onProgress(newProgress)
    }

    /** Nothing is granted. Protected media (DRM) marks the page's finds as protected (spec 4.7 table). */
    override fun onPermissionRequest(request: PermissionRequest) {
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) events.onProtectedMedia()
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onShowFileChooser(view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean = false
}

/**
 * Service workers fetch for their pages outside the WebView's client: their requests are shown to
 * [events] too (detection layer 1), and never changed. Returns how to stop.
 */
@SuppressLint("RequiresFeature")
internal fun watchServiceWorkers(events: RitmeWebEvents): () -> Unit {
    val controller: ServiceWorkerController = if (ritmeProfileSupported()) {
        ProfileStore.getInstance().getOrCreateProfile(RITME_BROWSER_PROFILE).serviceWorkerController
    } else {
        ServiceWorkerController.getInstance()
    }
    controller.setServiceWorkerClient(object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            events.onRequest(request.url.toString(), request.method, request.requestHeaders.orEmpty())
            return null
        }
    })
    return { controller.setServiceWorkerClient(object : ServiceWorkerClient() {}) }
}

/**
 * "Hapus data browser Ritme": the profile's cookies and storage, and this view's cache, history and
 * form data. Only with a profile of its own (Decision 13): the default profile also holds the
 * app's other sign-ins.
 */
@SuppressLint("RequiresFeature")
internal fun clearRitmeBrowserData(view: WebView) {
    if (!ritmeProfileSupported()) return
    val profile = ProfileStore.getInstance().getOrCreateProfile(RITME_BROWSER_PROFILE)
    profile.cookieManager.removeAllCookies(null)
    profile.cookieManager.flush()
    profile.webStorage.deleteAllData()
    view.clearCache(true)
    view.clearHistory()
    view.clearFormData()
}
