package com.example.musicfy.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.musicfy.R
import com.example.musicfy.constants.RitmeDisplayNameKey
import com.example.musicfy.constants.RitmeServerUrlKey
import com.example.musicfy.constants.UsernameKey
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.RitmeLinks
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.component.SettingsGroup
import com.example.musicfy.ui.component.SettingsGroupStyle
import com.example.musicfy.ui.component.SettingsItem
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.component.SheetPillButton
import com.example.musicfy.ui.component.SheetTextField
import com.example.musicfy.ui.component.SubSettingsScaffold
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryText
import com.example.musicfy.utils.rememberPreference

/**
 * Ritme settings (spec 3.5): the server URL for self-hosting, with "Restore default", and the
 * display name. Laid out like the other settings pages (one grouped SettingsGroup, then a
 * bodySmall note as on Sync). Each value is edited in a PopupSheet laid out like the start/join
 * sheet (a SheetLabel, a SheetTextField, an inline error, the SheetPillButton). A server URL is
 * only saved when the socket can open it, so a typo never reaches the next Ritme.
 */
@Composable
fun RitmeSettingsScreen(navController: NavController) {
    val (serverUrl, setServerUrl) = rememberPreference(RitmeServerUrlKey, "")
    val (displayName, setDisplayName) = rememberPreference(RitmeDisplayNameKey, "")
    val (username) = rememberPreference(UsernameKey, "")
    val defaultUrl = RitmeConfig.DEFAULT_SERVER_URL
    val shownUrl = RitmeLinks.normalizeServerUrl(serverUrl) ?: defaultUrl
    val shownName = displayName.ifBlank { username }

    val popup = LocalZoomOutOverlayState.current
    val serverUrlLabel = stringResource(R.string.ritme_server_url)
    val invalidUrl = stringResource(R.string.ritme_server_url_invalid)
    val nameLabel = stringResource(R.string.ritme_display_name)
    val save = stringResource(R.string.ritme_save)

    SubSettingsScaffold(
        title = stringResource(R.string.ritme_name),
        onBack = { navController.navigateUp() },
    ) {
        SettingsGroup(
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text(serverUrlLabel) },
                    descriptionText = shownUrl,
                    icon = painterResource(R.drawable.link),
                    iconShape = CircleShape,
                    onClick = {
                        popup.showRitmeFieldSheet(
                            field = RitmeField(
                                label = serverUrlLabel,
                                initial = shownUrl,
                                placeholder = defaultUrl,
                                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                save = save,
                                parse = { RitmeLinks.serverUrlToStore(it, defaultUrl) },
                                error = invalidUrl,
                            ),
                            onSave = setServerUrl,
                        )
                    },
                ),
                SettingsItem(
                    title = { Text(stringResource(R.string.ritme_server_url_reset)) },
                    descriptionText = defaultUrl,
                    icon = painterResource(R.drawable.restore),
                    iconShape = CircleShape,
                    enabled = serverUrl.isNotBlank(),
                    onClick = { setServerUrl("") },
                ),
                SettingsItem(
                    title = { Text(nameLabel) },
                    descriptionText = shownName.ifBlank { null },
                    icon = painterResource(R.drawable.person),
                    iconShape = CircleShape,
                    onClick = {
                        popup.showRitmeFieldSheet(
                            field = RitmeField(
                                label = nameLabel,
                                initial = shownName,
                                placeholder = nameLabel,
                                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                                save = save,
                                maxLength = RitmeLinks.MAX_NAME_LENGTH,
                                // only blank is refused, and the button already says so by dimming
                                parse = RitmeLinks::cleanDisplayName,
                            ),
                            onSave = setDisplayName,
                        )
                    },
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ritme_settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(140.dp))
    }
}

/** One editable Ritme setting: [parse] gives what to store, or null while the text is not valid. */
private class RitmeField(
    val label: String,
    val initial: String,
    val placeholder: String,
    val keyboard: KeyboardOptions,
    val save: String,
    val parse: (String) -> String?,
    /** Shown under the field while non-blank text does not parse; null to only dim the button. */
    val error: String? = null,
    val maxLength: Int = Int.MAX_VALUE,
)

/** The sheet's text, shared by its content and its pinned button (composed apart). Main thread only. */
@Stable
private class RitmeFieldForm(initial: String) {
    var text by mutableStateOf(initial)
}

// One at a time: a quick second tap on a row must not stack a copy. Main thread only.
private var fieldSheetOpen = false

/**
 * Edits one setting in a PopupSheet laid out like the start/join sheet. Neither the button nor
 * the keyboard's Done saves text that does not parse.
 */
private fun PopupSheetState.showRitmeFieldSheet(field: RitmeField, onSave: (String) -> Unit) {
    if (fieldSheetOpen) return
    fieldSheetOpen = true
    val form = RitmeFieldForm(field.initial.take(field.maxLength))
    var handle: PopupSheetHandle? = null
    fun submit() {
        val value = field.parse(form.text) ?: return
        onSave(value)
        handle?.dismiss()
    }
    handle = showWithHandle(
        buttonBar = {
            SheetPillButton(
                text = field.save,
                enabled = field.parse(form.text) != null,
                onClick = ::submit,
            )
        },
        onClosed = { fieldSheetOpen = false },
    ) {
        val invalid = field.error != null && form.text.isNotBlank() && field.parse(form.text) == null
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            SheetLabel(field.label)
            Spacer(Modifier.height(8.dp))
            SheetTextField(
                value = form.text,
                onValueChange = { form.text = it.take(field.maxLength) },
                placeholder = field.placeholder,
                keyboardOptions = field.keyboard,
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            if (invalid) {
                Spacer(Modifier.height(12.dp))
                RitmeSecondaryText(
                    text = field.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
