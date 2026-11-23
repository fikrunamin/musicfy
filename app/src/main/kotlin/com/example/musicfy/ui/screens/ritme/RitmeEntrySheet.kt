package com.example.musicfy.ui.screens.ritme

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.musicfy.R
import com.example.musicfy.constants.RitmeDisplayNameKey
import com.example.musicfy.constants.UsernameKey
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.RitmeLinks
import com.example.musicfy.ritme.RitmeTexts
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.ritme.session.RitmeUiState
import com.example.musicfy.ui.component.Material3MenuGroup
import com.example.musicfy.ui.component.Material3MenuItemData
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.component.SheetPillButton
import com.example.musicfy.ui.component.SheetTextField
import com.example.musicfy.utils.rememberPreference

/**
 * What the start/join sheet holds, shared by its content and its pinned button (the button bar
 * is composed apart from the content, like Create playlist's PlaylistFormState). Main thread only.
 */
@Stable
internal class RitmeEntryForm(initialCode: String?, val startWith: List<MediaMetadata?>?) {
    var name by mutableStateOf("")
    /** Set once the name was filled in from the saved display name (or the profile name). */
    var nameSeeded by mutableStateOf(false)
    var code by mutableStateOf(initialCode.orEmpty())
    var joining by mutableStateOf(initialCode != null)

    private val cleanName: String? get() = RitmeLinks.cleanDisplayName(name)
    private val cleanCode: String? get() = RitmeLinks.normalizeCode(code)

    fun canSubmit(phase: RitmePhase): Boolean =
        phase != RitmePhase.CONNECTING && cleanName != null && (!joining || cleanCode != null)

    @StringRes
    fun buttonLabel(state: RitmeUiState): Int = when {
        state.phase == RitmePhase.CONNECTING -> R.string.ritme_connecting
        errorOf(state) != null -> R.string.ritme_retry
        joining -> R.string.ritme_join
        else -> R.string.ritme_start
    }

    fun submit(ritme: RitmeUi, saveName: (String) -> Unit) {
        val name = cleanName ?: return
        if (!canSubmit(ritme.session.state.value.phase)) return
        saveName(name)
        if (joining) {
            cleanCode?.let { ritme.controller.joinRoom(it, name) }
        } else {
            ritme.controller.startRoom(name, startWith)
        }
    }

    companion object {
        /** The inline error after a failed attempt (spec 4.1), or null. */
        @StringRes
        fun errorOf(state: RitmeUiState): Int? =
            if (state.phase == RitmePhase.ENDED) RitmeTexts.entryError(state.ended) else null
    }
}

/**
 * The start/join sheet's pinned button: the sheets' pill (SheetPillButton), reading "Connecting…"
 * while it works, the way Create playlist's reads "Creating…".
 */
@Composable
internal fun RitmeEntryButton(ritme: RitmeUi, form: RitmeEntryForm) {
    val state by ritme.session.state.collectAsState()
    val (_, saveName) = rememberPreference(RitmeDisplayNameKey, "")
    SheetPillButton(
        text = stringResource(form.buttonLabel(state)),
        enabled = form.canSubmit(state.phase),
        onClick = { form.submit(ritme, saveName) },
        // "Connecting…" / "Try again" are announced as the label changes.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Start a Ritme (with the current queue, or the form's songs) or join one by code (spec 3.2),
 * laid out like the playlist form (PlaylistForm: a SheetLabel, 8 dp, a SheetTextField, 18 dp to
 * the next). The display name is asked once and remembered. Errors show inline and the button
 * turns into "Try again" (spec 4.1); the local queue is untouched until the server welcomes us.
 */
@Composable
internal fun RitmeEntryContent(ritme: RitmeUi, form: RitmeEntryForm, onJoined: () -> Unit) {
    val state by ritme.session.state.collectAsState()
    val (savedName, saveName) = rememberPreference(RitmeDisplayNameKey, "")
    val (username) = rememberPreference(UsernameKey, "")
    val joined by rememberUpdatedState(onJoined)

    LaunchedEffect(Unit) {
        if (!form.nameSeeded) {
            form.name = savedName.ifBlank { username }.take(RitmeLinks.MAX_NAME_LENGTH)
            form.nameSeeded = true
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == RitmePhase.IN_ROOM) joined()
    }

    val busy = state.phase == RitmePhase.CONNECTING
    val error = RitmeEntryForm.errorOf(state)
    val done = KeyboardActions(onDone = { form.submit(ritme, saveName) })

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SheetLabel(stringResource(if (form.joining) R.string.ritme_join_title else R.string.ritme_name))
        RitmeSecondaryText(stringResource(if (form.startWith != null && !form.joining) R.string.ritme_start_with_this else R.string.ritme_start_desc))
        Spacer(Modifier.height(22.dp))
        SheetLabel(stringResource(R.string.ritme_display_name))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = form.name,
            onValueChange = { form.name = it.take(RitmeLinks.MAX_NAME_LENGTH) },
            placeholder = stringResource(R.string.ritme_display_name),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = done,
        )
        if (form.joining) {
            Spacer(Modifier.height(18.dp))
            SheetLabel(stringResource(R.string.ritme_code_label))
            Spacer(Modifier.height(8.dp))
            SheetTextField(
                value = form.code,
                onValueChange = { input ->
                    form.code = input.uppercase().filter { it.isLetterOrDigit() }.take(RitmeLinks.CODE_LENGTH)
                },
                placeholder = stringResource(R.string.ritme_code_label),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = done,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            RitmeSecondaryText(
                text = stringResource(error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (form.startWith == null) {
            Spacer(Modifier.height(18.dp))
            // Switches between starting and joining; the same menu row as the song and player menus.
            Material3MenuGroup(
                items = listOf(
                    Material3MenuItemData(
                        icon = {
                            Icon(
                                painter = painterResource(if (form.joining) R.drawable.group else R.drawable.group_add),
                                contentDescription = null,
                            )
                        },
                        title = { Text(stringResource(if (form.joining) R.string.ritme_start else R.string.ritme_join_title)) },
                        onClick = if (busy) null else ({ form.joining = !form.joining }),
                    ),
                ),
            )
        }
    }
}
