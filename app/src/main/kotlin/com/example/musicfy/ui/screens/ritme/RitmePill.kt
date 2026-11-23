package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicfy.R
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.net.ConnectionStatus

/**
 * "Ritme · N" (connected members), or "Reconnecting…" (spec 3.4). Tapping it opens the room
 * sheet. [alphaProvider] fades it with the player sheet; it is not clickable while invisible.
 *
 * Styled as the player overlay's own chip, QueueScreen's QueueSectionHeader "Clear" chip: a
 * circle-ended white-at-12 % fill, 13 sp SemiBold white at 85 %, 12 × 5 dp padding; the group glyph
 * takes the same tint.
 */
@Composable
fun RitmePill(alphaProvider: () -> Float, modifier: Modifier = Modifier) {
    if (!RitmeConfig.ENABLED) return
    val ritme = rememberRitme()
    val state by ritme.session.state.collectAsState()
    val room = state.room
    val alpha by rememberUpdatedState(alphaProvider)
    val visible by remember { derivedStateOf { alpha() > 0.05f } }
    if (!state.inRoom || room == null || !visible) return
    val text = if (state.connection == ConnectionStatus.RECONNECTING) {
        stringResource(R.string.ritme_reconnecting)
    } else {
        stringResource(R.string.ritme_pill, room.members.count { it.connected })
    }
    val content = Color.White.copy(alpha = 0.85f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha() }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button) { ritme.controller.openSheet() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Icon(painterResource(R.drawable.group), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1)
    }
}

/**
 * The open player's top-right Ritme button, outside a room: opens the start/join sheet. In a room
 * it steps aside for [RitmePill] at the top centre, which carries the member count and the
 * reconnecting state, so the player never shows two Ritme marks.
 *
 * The player's own round button ([com.example.musicfy.ui.player.PressScaleActionButton], as the
 * side button and the queue header's like at rest): 34 dp, white-at-15 % fill, white-at-85 % glyph,
 * press-scale with no ripple. [alphaProvider] fades it with the sheet and the lyrics/queue header
 * morph, the same as the pill; it is not clickable while invisible.
 */
@Composable
fun RitmeCornerButton(alphaProvider: () -> Float, modifier: Modifier = Modifier) {
    if (!RitmeConfig.ENABLED) return
    val ritme = rememberRitme()
    val state by ritme.session.state.collectAsState()
    val alpha by rememberUpdatedState(alphaProvider)
    val visible by remember { derivedStateOf { alpha() > 0.05f } }
    if (state.inRoom || !visible) return
    val label = stringResource(R.string.ritme_name)
    com.example.musicfy.ui.player.PressScaleActionButton(
        icon = R.drawable.group,
        tint = Color.White.copy(alpha = 0.85f),
        containerColor = Color.White.copy(alpha = 0.15f),
        onClick = { ritme.controller.openSheet() },
        hasShadow = false,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha() }
            .semantics {
                contentDescription = label
                role = Role.Button
            },
    )
}
