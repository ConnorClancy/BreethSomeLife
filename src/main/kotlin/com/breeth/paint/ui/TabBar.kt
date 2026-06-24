package com.breeth.paint.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeth.paint.app.AppState

/**
 * Frame tab bar (spec §8.1): `base`, `1`–`9` in order. Click a tab to activate
 * it; numbered tabs show an "✕" to delete (spec §8.5). Includes the onion-skin
 * toggle (spec §8.3) and a hint for the keyboard shortcuts.
 */
@Composable
fun TabBar(app: AppState, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            app.frames.forEachIndexed { index, frame ->
                FrameTab(
                    label = frame.label,
                    selected = index == app.activeFrameIndex,
                    deletable = frame.label != "base",
                    onClick = { app.activateFrame(index) },
                    onDelete = { app.deleteFrame(index) },
                )
            }

            Spacer(Modifier.width(12.dp))

            FilledTonalButton(
                onClick = { app.toggleOnionSkin() },
                enabled = !app.onBase,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(if (app.onionSkin) "Onion: on" else "Onion: off")
            }

            Spacer(Modifier.width(12.dp))
            Text(
                "0–9 switch/create frames · Shift+O onion skin",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FrameTab(
    label: String,
    selected: Boolean,
    deletable: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Row(
            Modifier.clickable(onClick = onClick)
                .padding(start = 12.dp, end = if (deletable) 4.dp else 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = fg, fontSize = 13.sp)
            if (deletable) {
                Text(
                    "✕",
                    color = fg,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}
