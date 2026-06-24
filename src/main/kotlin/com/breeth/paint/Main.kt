package com.breeth.paint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.breeth.paint.app.AppState
import com.breeth.paint.render.CanvasView
import com.breeth.paint.ui.ControlsPanel
import com.breeth.paint.ui.ResizeDialog

fun main() = application {
    val app = remember { AppState() }
    val windowState = rememberWindowState(size = DpSize(1140.dp, 840.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "BreethPaint",
    ) {
        var showResize by remember { mutableStateOf(false) }

        MenuBar {
            Menu("File", mnemonic = 'F') {
                Item("New", shortcut = KeyShortcut(Key.N, ctrl = true), onClick = { app.newDocument() })
            }
            Menu("Edit", mnemonic = 'E') {
                Item("Undo", shortcut = KeyShortcut(Key.Z, ctrl = true), enabled = app.canUndo, onClick = { app.undo() })
                Item("Redo", shortcut = KeyShortcut(Key.Z, ctrl = true, shift = true), enabled = app.canRedo, onClick = { app.redo() })
                Item("Clear Canvas", onClick = { app.clearCanvas() })
            }
            Menu("Image", mnemonic = 'I') {
                Item("Resize…", onClick = { showResize = true })
                CheckboxItem(
                    "Transparent Background",
                    checked = app.transparent,
                    onCheckedChange = { app.toggleTransparency() },
                )
            }
        }

        MaterialTheme {
            Surface {
                Column(Modifier.fillMaxSize()) {
                    ControlsPanel(app)
                    // Neutral backdrop so the canvas + checkerboard stand out.
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
            if (showResize) ResizeDialog(app, onDismiss = { showResize = false })
        }
    }
}
