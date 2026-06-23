package com.breeth.paint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.breeth.paint.app.AppState
import com.breeth.paint.render.CanvasView
import com.breeth.paint.ui.ControlsPanel

fun main() = application {
    val app = remember { AppState() }
    val windowState = rememberWindowState(size = DpSize(1140.dp, 840.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "BreethPaint",
        onKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed) {
                when (e.key) {
                    Key.Z -> { if (e.isShiftPressed) app.redo() else app.undo(); true }
                    Key.Y -> { app.redo(); true }
                    else -> false
                }
            } else {
                false
            }
        },
    ) {
        MaterialTheme {
            Surface {
                Column(Modifier.fillMaxSize()) {
                    ControlsPanel(app)
                    // Neutral backdrop so the white canvas + checkerboard stand out.
                    Surface(
                        Modifier.weight(1f).fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(Modifier.fillMaxSize().padding(8.dp)) {
                            CanvasView(app, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
