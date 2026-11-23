package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.musicfy.R
import com.example.musicfy.ui.component.SheetIconBadge

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
