package com.example.musicfy.ui.screens.ritme.browser

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.musicfy.R
import com.example.musicfy.ritme.browser.BrowserNote
import com.example.musicfy.ritme.browser.FoundItem
import com.example.musicfy.ritme.browser.ProbeState
import com.example.musicfy.ritme.browser.RitmeBrowserEntries
import com.example.musicfy.ritme.browser.RitmeBrowserModel
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.ExpiryHints
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.component.SheetPillButton
import com.example.musicfy.ui.component.SheetTextField
import com.example.musicfy.ui.screens.ritme.AddVideoRow
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryText
import com.example.musicfy.ui.screens.ritme.RitmeUi
import com.example.musicfy.ui.screens.ritme.canAddVideos
import com.example.musicfy.utils.makeTimeString

/** One found list at a time: tapping the pill twice must not stack copies. Main thread only. */
private var foundSheetOpen = false

/**
 * The found list (spec 4.7 step 5): the page's YouTube video (if any) first, then every find with
 * a 16:9 placeholder (no picture is loaded from the page), its domain, type, length or LIVE,
 * height and the check's verdict. The checks start when the list opens. A row opens the
 * mandatory confirmation; the YouTube row opens "Tambah video" with the watch link.
 */
internal fun PopupSheetState.showRitmeFoundSheet(
    ritme: RitmeUi,
    browser: RitmeBrowserModel,
    roomMode: Boolean,
    onYouTube: (videoId: String) -> Unit,
    onStarted: () -> Unit,
): PopupSheetHandle? {
    if (foundSheetOpen) return null
    foundSheetOpen = true
    var handle: PopupSheetHandle? = null
    var confirm: PopupSheetHandle? = null
    handle = showWithHandle(onClosed = {
        foundSheetOpen = false
        confirm?.dismiss()
    }) {
        RitmeFoundContent(
            browser = browser,
            onYouTube = { id ->
                handle?.dismiss()
                onYouTube(id)
            },
            onChoose = { url ->
                confirm = showRitmeConfirmSheet(
                    ritme = ritme,
                    browser = browser,
                    url = url,
                    roomMode = roomMode,
                    onAdded = { handle?.dismiss() },
                    onStarted = {
                        handle?.dismiss()
                        onStarted()
                    },
                )
            },
        )
    }
    return handle
}

@Composable
private fun RitmeFoundContent(browser: RitmeBrowserModel, onYouTube: (String) -> Unit, onChoose: (String) -> Unit) {
    val findings by browser.findings.collectAsState()
    val probes by browser.probes.collectAsState()
    // Spec 4.7 step 4: checked when the list opens; finds that arrive while it is open are checked too.
    LaunchedEffect(findings) { browser.probeAll() }
    val items = remember(findings, probes) { browser.items(findings, probes) }
    val chooseLabel = stringResource(R.string.ritme_add_video_choose)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SheetLabel(stringResource(R.string.ritme_browser_found_title))
        Spacer(Modifier.height(8.dp))
        findings.youTubeVideoId?.let { id ->
            AddVideoRow(
                title = stringResource(R.string.ritme_browser_youtube_row),
                subtitle = "YouTube",
                thumbnailUrl = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                isUrl = false,
                onClickLabel = chooseLabel,
                onClick = { onYouTube(id) },
            )
        }
        items.forEach { item ->
            AddVideoRow(
                title = RitmeBrowserEntries.rowTitle(item.candidate, findings),
                subtitle = foundSubtitle(item),
                // Never a picture from the page (spec 4.7, R8): the web_link placeholder.
                thumbnailUrl = null,
                isUrl = true,
                onClickLabel = chooseLabel,
                onClick = { onChoose(item.candidate.url) },
            )
        }
        if (items.isEmpty() && findings.youTubeVideoId == null) {
            RitmeSecondaryText(
                text = stringResource(if (findings.blobOnly) R.string.ritme_browser_blob_only else R.string.ritme_browser_none),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** "domain · HLS · 12:34 · 720p · Hanya di aplikasi": where from, what it is, and what the check said. */
@Composable
private fun foundSubtitle(item: FoundItem): String {
    val candidate = item.candidate
    val verdict = (item.state as? ProbeState.Done)?.verdict
    val parts = mutableListOf<String>()
    RitmeBrowserEntries.domainOf(candidate.url).takeIf { it.isNotEmpty() }?.let(parts::add)
    RitmeBrowserEntries.typeLabel(candidate.url, verdict?.streamType ?: candidate.streamType)?.let(parts::add)
    val live = verdict?.live ?: candidate.pageLive
    val length = verdict?.durationMs ?: candidate.pageDurationMs
    if (live) {
        parts += stringResource(R.string.ritme_tag_live)
    } else if (length != null && length > 0) {
        parts += makeTimeString(length)
    }
    (verdict?.height ?: candidate.height.takeIf { it > 0 })?.let { parts += "${it}p" }
    val status = when {
        item.state is ProbeState.Checking -> R.string.ritme_browser_checking
        verdict != null -> badgeText(verdict)
        else -> null
    }
    if (status != null) parts += stringResource(status)
    return parts.joinToString(" · ")
}

@StringRes
private fun badgeText(verdict: ProbeVerdict): Int? = when (verdict.refusal) {
    Refusal.DRM -> R.string.ritme_browser_badge_drm
    Refusal.NEEDS_LOGIN -> R.string.ritme_browser_badge_login
    Refusal.FAILED -> R.string.ritme_browser_badge_failed
    Refusal.LENGTH_UNKNOWN -> R.string.ritme_browser_badge_length
    Refusal.NOT_MEDIA -> null
    null -> when {
        verdict.needsReferrer -> R.string.ritme_browser_badge_app_only
        !verdict.webPlayable -> R.string.ritme_browser_badge_not_web
        else -> null
    }
}

@StringRes
private fun refusalText(refusal: Refusal): Int = when (refusal) {
    Refusal.DRM -> R.string.ritme_add_video_protected
    Refusal.NEEDS_LOGIN -> R.string.ritme_add_video_needs_login
    Refusal.FAILED -> R.string.ritme_browser_refusal_failed
    Refusal.LENGTH_UNKNOWN -> R.string.ritme_add_video_length_unreadable
    Refusal.NOT_MEDIA -> R.string.ritme_browser_refusal_not_media
}

/** The confirmation's editable title, shared by its content and its pinned buttons. */
@Stable
private class RitmeConfirmForm(initialTitle: String) {
    var title by mutableStateOf(initialTitle)

    /** One-shot: a second tap while the sheet leaves must not add again. Main thread only. */
    var sent = false
}

/**
 * The confirmation (spec 4.7 step 6, mandatory): the title (editable, default og:title →
 * document.title → file name), the source (og:site_name or the domain) sent as the artist, the
 * check's verdict, and the warnings, "Link ini akan terlihat oleh semua anggota room." always.
 * Adding happens only from its buttons.
 */
private fun PopupSheetState.showRitmeConfirmSheet(
    ritme: RitmeUi,
    browser: RitmeBrowserModel,
    url: String,
    roomMode: Boolean,
    onAdded: () -> Unit,
    onStarted: () -> Unit,
): PopupSheetHandle? {
    val findings = browser.findings.value
    val candidate = findings.candidates.firstOrNull { it.url == url } ?: return null
    val form = RitmeConfirmForm(RitmeBrowserEntries.defaultTitle(candidate, findings))
    val source = RitmeBrowserEntries.source(findings, candidate)
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            RitmeConfirmButtons(
                ritme = ritme,
                browser = browser,
                candidate = candidate,
                form = form,
                source = source,
                roomMode = roomMode,
                onAdded = {
                    handle?.dismiss()
                    onAdded()
                },
                onStarted = {
                    handle?.dismiss()
                    onStarted()
                },
            )
        },
    ) {
        RitmeConfirmContent(browser = browser, candidate = candidate, form = form, source = source)
    }
    return handle
}

@Composable
private fun RitmeConfirmContent(browser: RitmeBrowserModel, candidate: Candidate, form: RitmeConfirmForm, source: String) {
    val probes by browser.probes.collectAsState()
    val pageFindings by browser.findings.collectAsState()
    // Read through the model so a page's DRM request counts (controller decision 1).
    val verdict = remember(probes, pageFindings) { browser.verdictOf(candidate.url) }
    val keyboard = LocalSoftwareKeyboardController.current
    val now = remember { System.currentTimeMillis() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SheetLabel(stringResource(R.string.ritme_browser_confirm_title))
        Spacer(Modifier.height(12.dp))
        AddVideoRow(
            title = form.title.ifBlank { source },
            subtitle = foundSubtitle(FoundItem(candidate, verdict?.let { ProbeState.Done(it) } ?: probes[candidate.url])),
            thumbnailUrl = null,
            isUrl = true,
            onClickLabel = null,
            onClick = null,
        )
        Spacer(Modifier.height(18.dp))
        SheetLabel(stringResource(R.string.ritme_browser_title_label))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = form.title,
            onValueChange = { form.title = it.take(RitmeEntries.MAX_TEXT) },
            // What a blank title becomes: the source.
            placeholder = source,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )
        Spacer(Modifier.height(12.dp))
        RitmeSecondaryText(stringResource(R.string.ritme_browser_source, source))
        Spacer(Modifier.height(6.dp))
        RitmeSecondaryText(stringResource(R.string.ritme_browser_visible_to_room))
        when {
            verdict == null -> NoteLine(stringResource(R.string.ritme_browser_checking))
            verdict.refusal != null -> NoteLine(stringResource(refusalText(requireNotNull(verdict.refusal))), error = true)
            else -> RitmeBrowserEntries.notes(candidate.url, verdict, now).forEach { NoteLine(noteText(it, candidate.url, now)) }
        }
    }
}

@Composable
private fun NoteLine(text: String, error: Boolean = false) {
    Spacer(Modifier.height(6.dp))
    val modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (error) {
        RitmeSecondaryText(text = text, color = MaterialTheme.colorScheme.error, modifier = modifier)
    } else {
        RitmeSecondaryText(text = text, modifier = modifier)
    }
}

@Composable
private fun noteText(note: BrowserNote, url: String, nowMs: Long): String = when (note) {
    BrowserNote.SENSITIVE -> stringResource(R.string.ritme_browser_note_sensitive)
    BrowserNote.EXPIRES -> {
        val minutes = (ExpiryHints.of(url, nowMs).expiresInMinutes ?: 1L).coerceIn(1L, 100_000L).toInt()
        pluralStringResource(R.plurals.ritme_browser_note_expires, minutes, minutes)
    }
    BrowserNote.EXPIRED -> stringResource(R.string.ritme_browser_note_expired)
    BrowserNote.IP_LOCKED -> stringResource(R.string.ritme_browser_note_ip)
    BrowserNote.APP_ONLY -> stringResource(R.string.ritme_browser_note_app_only)
    BrowserNote.NOT_ON_WEB -> stringResource(R.string.ritme_browser_note_not_web)
    BrowserNote.KEY_MAY_NEED_LOGIN -> stringResource(R.string.ritme_browser_note_key)
}

/**
 * The confirmation's pinned buttons, the sheets' pills. Not shown while the check runs (nothing to
 * tap yet) or where this device may not add. A failed check or an unreadable length: "Coba lagi".
 * DRM or a sign-in (U12): the add pills stay, dimmed, and the sheet says why. In a room "Tambah"
 * and "Putar berikutnya"; outside one "Mulai Ritme dengan video ini" (start mode, like "Tambah video").
 */
@Composable
private fun RitmeConfirmButtons(
    ritme: RitmeUi,
    browser: RitmeBrowserModel,
    candidate: Candidate,
    form: RitmeConfirmForm,
    source: String,
    roomMode: Boolean,
    onAdded: () -> Unit,
    onStarted: () -> Unit,
) {
    val session by ritme.session.state.collectAsState()
    val probes by browser.probes.collectAsState()
    val pageFindings by browser.findings.collectAsState()
    if (!canAddVideos(session, roomMode)) return
    val state = probes[candidate.url]
    val verdict = remember(probes, pageFindings) { browser.verdictOf(candidate.url) }
    if (verdict == null) {
        // Not checked yet (opened before the list's checks reached it): check it now.
        if (state == null) LaunchedEffect(candidate.url) { browser.retry(candidate.url) }
        return
    }
    if (verdict.refusal == Refusal.FAILED || verdict.refusal == Refusal.LENGTH_UNKNOWN) {
        SheetPillButton(text = stringResource(R.string.ritme_retry), onClick = { browser.retry(candidate.url) })
        return
    }
    // The referrer is the page now, not the one this sheet opened on: read at the tap.
    fun entryNow() =
        RitmeBrowserEntries.entryOf(candidate, verdict, form.title, source, browser.findings.value.pageUrl)
    fun send(block: (NewEntry) -> Unit) {
        if (form.sent) return
        val entry = entryNow() ?: return
        form.sent = true
        block(entry)
    }
    val enabled = entryNow() != null
    if (session.inRoom) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetPillButton(
                text = stringResource(R.string.ritme_add),
                enabled = enabled,
                onClick = {
                    send {
                        ritme.controller.addEntries(listOf(it), playNext = false)
                        onAdded()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            SheetPillButton(
                text = stringResource(R.string.ritme_play_next_button),
                enabled = enabled,
                onClick = {
                    send {
                        ritme.controller.addEntries(listOf(it), playNext = true)
                        onAdded()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        SheetPillButton(
            text = stringResource(R.string.ritme_start_with_video),
            enabled = enabled,
            onClick = {
                send {
                    // The start sheet goes in underneath while these leave (PopupSheetState).
                    onStarted()
                    ritme.controller.startOrAddEntries(listOf(it))
                }
            },
        )
    }
}
