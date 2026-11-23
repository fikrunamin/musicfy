package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicfy.R
import com.example.musicfy.ui.component.AppSwitch
import com.example.musicfy.ui.component.SheetIconBadge
import com.example.musicfy.ui.player.menu.MenuRowSurface

// Text sizes mirrored from the screens Ritme copies; those keep them inline or private.
/** QueueScreen's QueueSongRow title: 15 sp on 19, SemiBold. */
internal val RitmeRowTitleSize = 15.sp
internal val RitmeRowTitleLineHeight = 19.sp
/** QueueSongRow's artist line, QueueSectionHeader's subtitle and SheetHeader's second line: 13 sp on 17. */
internal val RitmeSecondarySize = 13.sp
internal val RitmeSecondaryLineHeight = 17.sp
/** The alpha of all of those secondary lines. */
internal const val RitmeSecondaryAlpha = 0.6f

/** A secondary line (13 sp, 60 % content colour), as under QueueSectionHeader and SheetHeader titles. */
@Composable
internal fun RitmeSecondaryText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current.copy(alpha = RitmeSecondaryAlpha),
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        fontSize = RitmeSecondarySize,
        lineHeight = RitmeSecondaryLineHeight,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Material3MenuGroup's row (Menu.kt, Material3MenuItemRow: 16 dp corners on MenuRowSurface, 14 dp
 * sides, a 22 dp white disc holding a 13 dp dark glyph, 12 dp gap, 14 sp SemiBold title on
 * bodyLarge's 24 sp line) with an AppSwitch at the end, as a real switch: the whole row toggles
 * and announces its on/off state. The 32 dp switch is taller than the 24 dp title line, so the
 * vertical padding drops from 14 to 10 dp and the row stays exactly as tall as its neighbours.
 */
@Composable
internal fun RitmeMenuSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.35f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MenuRowSurface)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.9f * contentAlpha)),
        ) {
            Box(modifier = Modifier.size(13.dp)) {
                CompositionLocalProvider(LocalContentColor provides Color.Black.copy(alpha = 0.75f)) { icon() }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = contentAlpha),
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // Display only: the row is the toggle, so the switch adds no second semantics node.
        AppSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Same size as a queue row's cover (QueueScreen's RowArtSize). */
internal val RitmeAvatarSize: Dp = 44.dp

/**
 * A member's initial in a circle, styled like the playlist header's creator avatar when it has no
 * picture (PlaylistDetail's CreatorAvatar: a faint white disc with a white rim). Ritme members have
 * no pictures, so the initial is all there is. The host wears the app's crown glyph in the sheets'
 * own icon badge (SheetIconBadge) at the bottom-right.
 */
@Composable
internal fun RitmeAvatar(name: String, isHost: Boolean, modifier: Modifier = Modifier) {
    // The ambient content colour (white on the sheets), not a hard-coded white.
    val ink = LocalContentColor.current
    Box(modifier = modifier.size(RitmeAvatarSize)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RitmeAvatarSize)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.28f))
                .border(1.dp, ink.copy(alpha = 0.7f), CircleShape),
        ) {
            Text(
                text = initialOf(name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (isHost) {
            SheetIconBadge(
                icon = R.drawable.crown,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp),
            )
        }
    }
}
