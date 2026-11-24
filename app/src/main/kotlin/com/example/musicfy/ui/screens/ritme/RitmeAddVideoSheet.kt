package com.example.musicfy.ui.screens.ritme

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.musicfy.R
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ritme.session.RitmeUiState
import com.example.musicfy.ritme.video.AddVideoProblem
import com.example.musicfy.ritme.video.AddVideoState
import com.example.musicfy.ritme.video.RitmeAddVideoModel
import com.example.musicfy.ritme.video.RitmeVideoLookup
import com.example.musicfy.ritme.video.VideoChoice
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.component.SheetPillButton
import com.example.musicfy.ui.component.SheetTextField
import com.example.musicfy.utils.makeTimeString
import kotlinx.coroutines.delay

/**
 * What the "Add video" sheet holds, shared by its content and its pinned buttons (the button bar
 * is composed apart from the content, like RitmeEntryForm). [roomMode] is fixed when the sheet
 * opens: in a room it adds to the room, outside one it starts a room (Decision 12); a start-mode
 * sheet whose device joins a room meanwhile adds to that room instead. Main thread only.
 */
@Stable
internal class RitmeAddVideoForm(initialText: String?, val roomMode: Boolean) {
    var text by mutableStateOf(initialText.orEmpty())
    var state by mutableStateOf<AddVideoState>(AddVideoState.Empty)
        private set
    /** A URL entry's title (AddVideoState.Preview.editableTitle), the file name until edited. */
    var title by mutableStateOf("")
    /** Bumped by the keyboard's Search key: look the text up now, without waiting. */
    var searches by mutableIntStateOf(0)
        private set

    // A shared link is looked up at once; typing waits for a pause ([DEBOUNCE_MS]).
    private var immediate = initialText != null
    // The text (and search) whose answer is on screen, so coming back into composition does not
    // redo it (and replace a chosen result's preview with the results again). Null while a lookup
    // runs: one cancelled mid-flight (an edit undone) leaves "Searching…" up, so it must run again.
    private var resolved: Pair<String, Int>? = null

    fun show(next: AddVideoState) {
        state = next
        if (next is AddVideoState.Preview && next.editableTitle) title = next.entry.title
    }

    /** A second share while the sheet is open: its text replaces the field's, looked up at once. */
    fun fill(text: String) {
        immediate = true
        this.text = text
    }

    fun searchNow() {
        immediate = true
        searches++
    }

    /** A file's length could not be read: the pinned button tries again ([searchNow]). */
    val canRetry: Boolean get() = (state as? AddVideoState.Problem)?.kind == AddVideoProblem.LENGTH_UNREADABLE

    /** What the buttons send; null (and no buttons) without a preview. */
    fun entry(): NewEntry? = RitmeAddVideoModel.entryOf(state, title)

    /**
     * Never in a room this device may not add to (the host turned adding off): nothing to tap then.
     * Outside a room only in start mode (a room-mode sheet whose room is gone closes itself).
     */
    fun canSend(session: RitmeUiState): Boolean {
        if (!session.inRoom) return !roomMode
        val room = session.room ?: return false
        return RitmePermissions.canAdd(room, session.memberId)
    }

    suspend fun resolve(model: RitmeAddVideoModel) {
        val key = text to searches
        if (key == resolved) return
        if (!immediate) delay(DEBOUNCE_MS)
        immediate = false
        val query = text
        resolved = null
        if (query.isBlank()) {
            show(AddVideoState.Empty)
        } else {
            show(AddVideoState.Loading)
            show(model.resolve(query))
        }
        resolved = key
    }

    companion object {
        const val DEBOUNCE_MS = 600L

        @StringRes
        fun problemText(problem: AddVideoProblem): Int = when (problem) {
            AddVideoProblem.PAGE -> R.string.ritme_add_video_page
            AddVideoProblem.UNSAFE -> R.string.ritme_add_video_unsafe
            AddVideoProblem.UNRECOGNIZED -> R.string.ritme_add_video_unrecognized
            AddVideoProblem.NOT_FOUND -> R.string.ritme_add_video_not_found
            AddVideoProblem.NO_RESULTS -> R.string.ritme_add_video_no_results
            AddVideoProblem.LENGTH_UNREADABLE -> R.string.ritme_add_video_length_unreadable
            AddVideoProblem.PROTECTED -> R.string.ritme_add_video_protected
            AddVideoProblem.NEEDS_LOGIN -> R.string.ritme_add_video_needs_login
        }
    }
}

/**
 * The sheet's pinned buttons, the sheets' pills (SheetPillButton). After a file whose length could
 * not be read: "Try again" alone (RitmeAddVideoForm.canRetry). In a room (also one joined after
 * a start-mode sheet opened): "Add" and "Play next", side by side; outside one: "Start Ritme with
 * this video", which leads to the start sheet (Decision 12), or adds to a room joined since this
 * last composed (RitmeController.startOrAddEntries). Not shown at all without a preview, or while
 * adding is locked: a disabled pill would still take taps (the AppSwitch lesson) and nothing must
 * look tappable.
 */
@Composable
internal fun RitmeAddVideoButtons(ritme: RitmeUi, form: RitmeAddVideoForm, onDone: () -> Unit) {
    val session by ritme.session.state.collectAsState()
    if (!form.canSend(session)) return
    if (form.canRetry) {
        // Like the start sheet's button after a failed attempt: the pinned pill says "Try again".
        SheetPillButton(text = stringResource(R.string.ritme_retry), onClick = form::searchNow)
        return
    }
    val entry = form.entry() ?: return
    if (session.inRoom) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetPillButton(
                text = stringResource(R.string.ritme_add),
                onClick = {
                    ritme.controller.addEntries(listOf(entry), playNext = false)
                    onDone()
                },
                modifier = Modifier.weight(1f),
            )
            SheetPillButton(
                text = stringResource(R.string.ritme_play_next_button),
                onClick = {
                    ritme.controller.addEntries(listOf(entry), playNext = true)
                    onDone()
                },
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        SheetPillButton(
            text = stringResource(R.string.ritme_start_with_video),
            onClick = {
                // The start sheet goes in underneath this one while it leaves (PopupSheetState).
                onDone()
                ritme.controller.startOrAddEntries(listOf(entry))
            },
        )
    }
}

/**
 * "Add video" (spec 4.6), laid out like the start/join sheet (a SheetLabel, 8 dp, a SheetTextField):
 * one field for a link or words. A link becomes a preview row (a file link also gets an optional
 * title), words become results (YouTube's general search) to pick from; rows are the room sheet's
 * queue rows. Problems show inline, like the start sheet's errors. Nothing is added from here:
 * only the pinned buttons add.
 */
@Composable
internal fun RitmeAddVideoContent(ritme: RitmeUi, form: RitmeAddVideoForm, onRoomGone: () -> Unit) {
    val session by ritme.session.state.collectAsState()
    val model = remember { RitmeAddVideoModel(RitmeVideoLookup(ritme.controller.webOrigin())) }
    val roomGone by rememberUpdatedState(onRoomGone)
    val keyboard = LocalSoftwareKeyboardController.current

    // Opened to add to a room that has since ended (or was left): nothing left to add to.
    val gone = form.roomMode && !session.inRoom
    LaunchedEffect(gone) {
        if (gone) roomGone()
    }
    LaunchedEffect(form.text, form.searches) { form.resolve(model) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SheetLabel(stringResource(R.string.ritme_add_video))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = form.text,
            onValueChange = { form.text = it },
            placeholder = stringResource(R.string.ritme_add_video_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                form.searchNow()
            }),
        )
        when (val state = form.state) {
            AddVideoState.Empty -> Unit
            AddVideoState.Loading -> {
                Spacer(Modifier.height(12.dp))
                RitmeSecondaryText(
                    text = stringResource(R.string.ritme_add_video_searching),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is AddVideoState.Preview -> {
                Spacer(Modifier.height(12.dp))
                val entry = state.entry
                val isUrl = entry.kind == MediaKind.URL
                AddVideoRow(
                    title = if (state.editableTitle) RitmeAddVideoModel.entryOf(state, form.title)?.title ?: entry.title else entry.title,
                    // "domain · 1:30" or "domain · LIVE": what the room will do with it (0 = live).
                    subtitle = videoSubtitle(entry.artist, entry.durationMs.takeIf { it > 0 }?.let { (it / 1000).toInt().coerceAtLeast(1) }),
                    // A URL entry's picture is never loaded (spec 4.7): it has none anyway.
                    thumbnailUrl = if (isUrl) null else entry.thumbnailUrl,
                    isUrl = isUrl,
                    onClickLabel = null,
                    onClick = null,
                )
                if (state.editableTitle) {
                    Spacer(Modifier.height(18.dp))
                    SheetLabel(stringResource(R.string.ritme_add_video_title_optional))
                    Spacer(Modifier.height(8.dp))
                    SheetTextField(
                        value = form.title,
                        onValueChange = { form.title = it.take(RitmeEntries.MAX_TEXT) },
                        // What a blank title becomes: the site's domain.
                        placeholder = entry.artist,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    )
                }
            }
            is AddVideoState.Results -> {
                Spacer(Modifier.height(12.dp))
                val count = pluralStringResource(R.plurals.ritme_add_video_results, state.items.size, state.items.size)
                val chooseLabel = stringResource(R.string.ritme_add_video_choose)
                // Replaces "Searching…": its polite live region says how many were found (not every row).
                Column(
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = count
                    },
                ) {
                    state.items.forEach { choice ->
                        AddVideoRow(
                            title = choice.title,
                            subtitle = videoSubtitle(choice.channel, choice.durationSeconds.takeUnless { choice.live }),
                            thumbnailUrl = choice.thumbnailUrl,
                            isUrl = false,
                            onClickLabel = chooseLabel,
                            onClick = {
                                keyboard?.hide()
                                form.show(model.choose(choice))
                            },
                        )
                    }
                }
            }
            is AddVideoState.Problem -> {
                Spacer(Modifier.height(12.dp))
                RitmeSecondaryText(
                    text = stringResource(RitmeAddVideoForm.problemText(state.kind)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        // The host turned adding off while this was open: the buttons are gone, and this says why.
        if (session.inRoom && !form.canSend(session)) {
            Spacer(Modifier.height(12.dp))
            RitmeSecondaryText(
                text = stringResource(R.string.ritme_toast_add_video_locked),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** "Channel · 3:33" (or "domain · 3:33"), or "Channel · LIVE" for a live stream ([seconds] null). */
@Composable
private fun videoSubtitle(channel: String, seconds: Int?): String {
    val length = if (seconds == null) stringResource(R.string.ritme_tag_live) else makeTimeString(seconds * 1000L)
    return if (channel.isBlank()) length else stringResource(R.string.ritme_tagged, channel, length)
}

/**
 * The room sheet's video row (EntryRow): 60 dp, 16:9 art at 44 dp high with 8 dp corners on a 10 %
 * wash, the web_link glyph for a URL, 15 sp SemiBold title over a secondary line; tappable rows get
 * the ripple in the 14 dp highlight shape. No trailing actions.
 */
@Composable
private fun AddVideoRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    isUrl: Boolean,
    onClickLabel: String?,
    onClick: (() -> Unit)?,
) {
    val ink = LocalContentColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(RowHighlightShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = ink),
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(start = RowArtStart),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = RowVideoArtWidth, height = RowArtSize),
        ) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RowArtShape)
                    .background(ink.copy(alpha = 0.10f)),
            )
            if (isUrl) {
                Icon(
                    painter = painterResource(R.drawable.web_link),
                    contentDescription = null,
                    tint = ink.copy(alpha = 0.5f),
                    modifier = Modifier.size(ActionIconSize),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = title,
                fontSize = RitmeRowTitleSize,
                lineHeight = RitmeRowTitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RitmeSecondaryText(subtitle, maxLines = 1)
        }
    }
}
