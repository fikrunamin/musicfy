package com.example.musicfy.ui.screens.ritme.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.musicfy.LocalPlayerAwareWindowInsets
import com.example.musicfy.R
import com.example.musicfy.ritme.browser.RitmeBrowserModel
import com.example.musicfy.ritme.browser.RitmeProbes
import com.example.musicfy.ritme.media.AddressInput
import com.example.musicfy.ritme.media.AddressRefusal
import com.example.musicfy.ritme.media.BrowserAddress
import com.example.musicfy.ritme.media.ExternalTarget
import com.example.musicfy.ritme.video.RitmeFileLength
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.Material3MenuGroup
import com.example.musicfy.ui.component.Material3MenuItemData
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.component.SheetPillButton
import com.example.musicfy.ui.component.SheetTextField
import com.example.musicfy.ui.player.menu.MenuRowSurface
import com.example.musicfy.ui.screens.ritme.RitmeChip
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryText
import com.example.musicfy.ui.screens.ritme.rememberRitme

/** The browser's route (spec 4.7), reachable only from Ritme's sheets. `url`: typed text, resolved like the address bar. */
const val RitmeBrowserRoutePattern = "ritme/browser?url={url}"

fun ritmeBrowserRoute(url: String? = null): String =
    if (url.isNullOrBlank()) "ritme/browser" else "ritme/browser?url=" + Uri.encode(url)

/**
 * The Ritme browser (spec 4.7, user decision 2026-11-23): a header like the sign-in pages'
 * (WebLoginHeader) holding back, the address field (URL, bare domain or DuckDuckGo keywords; shows
 * the page's address, editable and copyable), reload and a ⋯ menu; then the page. What it finds
 * goes to [RitmeBrowserModel]. Opened in a room it adds to that room and closes when the room is
 * gone; opened outside one (start mode) it starts a room, like "Tambah video".
 */
@Composable
fun RitmeBrowserScreen(navController: NavController, initialText: String?) {
    val ritme = rememberRitme()
    val context = LocalContext.current
    val popup = LocalZoomOutOverlayState.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session by ritme.session.state.collectAsState()
    val roomMode = remember { ritme.session.state.value.inRoom }
    val browser = remember {
        val webOrigin = ritme.controller.webOrigin()
        RitmeBrowserModel(
            probe = { url, pageUrl -> RitmeProbes.probe(url, pageUrl, webOrigin) },
            fileLength = { url, referrer -> RitmeFileLength.read(url, referrer) },
            scope = scope,
        )
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(initialText.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var canForward by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<AddressRefusal?>(null) }
    // A refusal that triggers a load of its own (start link, POST to local): that load keeps the message once.
    var keepProblemOnce by remember { mutableStateOf(false) }
    // The ⋯ menu and the external-app confirmation: dismissed with the browser, never left behind it.
    val sheets = remember { mutableListOf<PopupSheetHandle?>() }
    // The found list (its exit takes the confirmation along): a new page closes them.
    val foundSheet = remember { arrayOfNulls<PopupSheetHandle>(1) }
    val entry = remember { navController.currentBackStackEntry }
    fun leave() {
        if (navController.currentBackStackEntry == entry) navController.navigateUp()
    }

    // Opened to add to a room that has since ended (or was left): nothing left to add to.
    val roomGone = roomMode && !session.inRoom
    LaunchedEffect(roomGone) {
        if (roomGone) leave()
    }

    fun open(text: String) {
        when (val input = BrowserAddress.resolve(text)) {
            is AddressInput.Open -> {
                problem = null
                keyboard?.hide()
                focusManager.clearFocus()
                webView?.loadUrl(input.url)
            }
            is AddressInput.Refused -> problem = input.reason
        }
    }

    fun goBack() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else leave()
    }

    val events = remember {
        object : RitmeWebEvents {
            override fun onPageStarted(url: String) {
                browser.pageStarted(url)
                foundSheet[0]?.dismiss()
                foundSheet[0] = null
                if (keepProblemOnce) keepProblemOnce = false else problem = null
                pageUrl = url
                if (!editing) address = url
            }

            override fun onHistoryChanged(url: String, canGoBack: Boolean, canGoForward: Boolean) {
                if (url != pageUrl) browser.urlChanged(url)
                pageUrl = url
                canForward = canGoForward
                if (!editing) address = url
            }

            override fun onProgress(percent: Int) {
                progress = percent
            }

            override fun onRequest(url: String, method: String, headers: Map<String, String>) =
                browser.onRequest(url, method, headers)

            override fun onPageReport(message: String, mainFrame: Boolean, origin: String) = browser.onReport(message, mainFrame, origin)

            override fun onProtectedMedia() = browser.onProtectedMedia()

            override fun onLocalAddressRefused(reloads: Boolean) {
                problem = AddressRefusal.LOCAL
                keepProblemOnce = reloads
            }

            override fun onExternalApp(url: String) {
                sheets += popup.showRitmeExternalAppSheet(context, url)
            }

            override fun onRendererGone() {
                webView = null
                Toast.makeText(context, R.string.ritme_browser_crashed, Toast.LENGTH_SHORT).show()
                leave()
            }
        }
    }

    DisposableEffect(lifecycle) {
        val stopWatching = watchServiceWorkers(events)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stopWatching()
            sheets.forEach { it?.dismiss() }
            sheets.clear()
            webView?.let { view ->
                view.stopLoading()
                view.onPause()
                destroySoon(view)
            }
            webView = null
        }
    }

    BackHandler { goBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)),
    ) {
        RitmeBrowserHeader(
            address = address,
            onAddressChange = {
                address = it
                problem = null
            },
            onEditingChange = { focused ->
                editing = focused
                if (!focused && pageUrl.isNotEmpty()) address = pageUrl
            },
            onGo = { open(address) },
            onBack = { goBack() },
            onReload = { webView?.reload() },
            onMenu = {
                keyboard?.hide()
                sheets += popup.showRitmeBrowserMenu(
                    canGoForward = canForward,
                    canClear = ritmeProfileSupported(),
                    onForward = { webView?.goForward() },
                    onCopy = { copyLink(context, pageUrl) },
                    onOpenElsewhere = { openElsewhere(context, pageUrl) },
                    onClear = {
                        webView?.let(::clearRitmeBrowserData)
                        Toast.makeText(context, R.string.ritme_browser_data_cleared, Toast.LENGTH_SHORT).show()
                    },
                    onClose = { leave() },
                )
            },
        )
        problem?.let { reason ->
            RitmeSecondaryText(
                text = stringResource(addressProblemText(reason)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(modifier = Modifier.height(2.dp).fillMaxWidth()) {
            if (progress < 100) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    createRitmeWebView(viewContext, events).also { view ->
                        RitmeProbes.userAgent = view.settings.userAgentString
                        webView = view
                        when (val start = initialText?.let(BrowserAddress::resolve)) {
                            is AddressInput.Open -> view.loadUrl(start.url)
                            is AddressInput.Refused -> {
                                problem = start.reason
                                keepProblemOnce = true
                                view.loadUrl(BrowserAddress.START_URL)
                            }
                            null -> view.loadUrl(BrowserAddress.START_URL)
                        }
                    }
                },
            )
            val findings by browser.findings.collectAsState()
            val probes by browser.probes.collectAsState()
            val offers = browser.offers(findings, probes)
            // The Ritme pill's chip on the floating pill's solid fill (MainActivity's RitmePill above the mini player).
            if (offers > 0 || findings.blobOnly) {
                RitmeChip(
                    text = if (offers > 0) {
                        pluralStringResource(R.plurals.ritme_browser_found, offers, offers)
                    } else {
                        stringResource(R.string.ritme_browser_blob_pill)
                    },
                    containerColor = MenuRowSurface,
                    icon = R.drawable.play,
                    onClick = {
                        keyboard?.hide()
                        val found = popup.showRitmeFoundSheet(
                            ritme = ritme,
                            browser = browser,
                            roomMode = roomMode,
                            // Never a URL entry: the existing YOUTUBE_VIDEO path with its preview.
                            onYouTube = { id -> ritme.controller.openAddVideo("https://www.youtube.com/watch?v=$id") },
                            // Start mode: the start sheet takes over; the browser's job is done.
                            onStarted = { navController.navigateUp() },
                        )
                        foundSheet[0] = found
                        sheets += found
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}

/** WebLoginHeader's row (8 × 4 dp, white IconButtons) with the address field in place of the title. */
@Composable
private fun RitmeBrowserHeader(
    address: String,
    onAddressChange: (String) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.ritme_browser_back), tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        SheetTextField(
            value = address,
            onValueChange = onAddressChange,
            placeholder = stringResource(R.string.ritme_browser_address_hint),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onEditingChange(it.isFocused) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
        )
        IconButton(onClick = onReload) {
            Icon(painterResource(R.drawable.refresh), contentDescription = stringResource(R.string.ritme_browser_reload), tint = Color.White)
        }
        IconButton(onClick = onMenu) {
            Icon(painterResource(R.drawable.more_vert), contentDescription = stringResource(R.string.ritme_browser_menu), tint = Color.White)
        }
    }
}

@StringRes
private fun addressProblemText(reason: AddressRefusal): Int = when (reason) {
    AddressRefusal.EMPTY -> R.string.ritme_browser_refused_empty
    AddressRefusal.SCHEME -> R.string.ritme_browser_refused_scheme
    AddressRefusal.LOCAL -> R.string.ritme_browser_refused_local
    AddressRefusal.CREDENTIALS -> R.string.ritme_browser_refused_credentials
    AddressRefusal.MALFORMED -> R.string.ritme_browser_refused_malformed
}

/** The ⋯ menu: the same menu rows as the room sheet (Material3MenuGroup). Forward is dimmed with nothing ahead. */
private fun PopupSheetState.showRitmeBrowserMenu(
    canGoForward: Boolean,
    canClear: Boolean,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onOpenElsewhere: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
): PopupSheetHandle? {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle {
        fun then(action: () -> Unit): () -> Unit = {
            handle?.dismiss()
            action()
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Material3MenuGroup(
                items = listOfNotNull(
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.arrow_forward), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_forward)) },
                        onClick = if (canGoForward) then(onForward) else null,
                    ),
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.content_copy), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_copy_link)) },
                        onClick = then(onCopy),
                    ),
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.globe), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_open_elsewhere)) },
                        onClick = then(onOpenElsewhere),
                    ),
                    if (canClear) {
                        Material3MenuItemData(
                            icon = { Icon(painterResource(R.drawable.delete), contentDescription = null) },
                            title = { Text(stringResource(R.string.ritme_browser_clear_data)) },
                            onClick = then(onClear),
                        )
                    } else {
                        null
                    },
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_close)) },
                        onClick = then(onClose),
                    ),
                ),
            )
        }
    }
    return handle
}

/** A page asked for another app (`intent:`, `market:`, …) after a tap: ask first (spec 4.7). */
private fun PopupSheetState.showRitmeExternalAppSheet(context: Context, url: String): PopupSheetHandle? {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetPillButton(
                    text = stringResource(R.string.ritme_cancel),
                    onClick = { handle?.dismiss() },
                    modifier = Modifier.weight(1f),
                )
                SheetPillButton(
                    text = stringResource(R.string.ritme_browser_open_app),
                    onClick = {
                        handle?.dismiss()
                        openExternal(context, url)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            SheetLabel(stringResource(R.string.ritme_browser_open_app_title))
            Spacer(Modifier.height(8.dp))
            RitmeSecondaryText(stringResource(R.string.ritme_browser_open_app_body))
            Spacer(Modifier.height(8.dp))
            // Where it goes: the page's words alone are not enough to decide.
            RitmeSecondaryText(externalTarget(url))
        }
    }
    return handle
}

private fun parseExternal(url: String): Intent? = runCatching {
    if (url.startsWith("intent:", ignoreCase = true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, url.toUri())
}.getOrNull()

/** What the confirmation shows: the app's package for an `intent:` link that names one, else the scheme (and host) it opens. */
private fun externalTarget(url: String): String = ExternalTarget.describe(url)

private val NEVER_EXTERNAL_SCHEMES = setOf("file", "content", "javascript")

/** Never to a named component or selector, browsable apps only. A package may stay (a Play Store fallback); the sheet showed it. */
private fun openExternal(context: Context, url: String) {
    val intent = parseExternal(url) ?: return
    if (intent.data?.scheme?.lowercase() in NEVER_EXTERNAL_SCHEMES) return
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun copyLink(context: Context, url: String) {
    if (url.isEmpty()) return
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(url, url))
    // Android 13+ confirms a copy itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.ritme_browser_link_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The page in the user's own browser, through the chooser (so Musicfy's own link handling is not picked by itself). */
private fun openElsewhere(context: Context, url: String) {
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return
    val view = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Lets Compose detach the view first; destroying an attached WebView logs errors (as CaptureWebView). */
private fun destroySoon(view: WebView) {
    Handler(Looper.getMainLooper()).postDelayed({ view.destroy() }, 500)
}
