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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import com.breeth.paint.ui.TabBar

fun main() = application {
    val app = remember { AppState() }
    var showResize by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(size = DpSize(1140.dp, 840.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "BreethPaint",
        // Frame keys (spec §8.2/§8.3). Suppressed while the resize dialog is open
        // or the hex field has focus, so digits typed there don't switch frames.
        // Ctrl/Alt combos fall through to the menu accelerators.
        onKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && !e.isCtrlPressed && !e.isAltPressed &&
                !showResize && !app.textFieldFocused
            ) {
                val digit = digitKey(e.key)
                when {
                    digit == 0 -> { app.activateFrame(0); true }                 // 0 → base
                    digit != null -> { app.gotoOrCreateFrame(digit); true }      // 1–9 → frame
                    e.isShiftPressed && e.key == Key.O -> { app.toggleOnionSkin(); true }
                    else -> false
                }
            } else {
                false
            }
        },
    ) {
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
                // Only base is resizable; the resize propagates to all frames (§8.4 override).
                Item("Resize…", enabled = app.onBase, onClick = { showResize = true })
                CheckboxItem(
                    "Transparent Background",
                    checked = app.transparent,
                    onCheckedChange = { app.toggleTransparency() },
                )
            }
            Menu("Frame", mnemonic = 'R') {
                CheckboxItem(
                    "Onion Skin",
                    checked = app.onionSkin,
                    onCheckedChange = { app.toggleOnionSkin() },
                )
                Item("Delete Current Frame", enabled = app.canDeleteActive, onClick = { app.deleteFrame(app.activeFrameIndex) })
            }
        }

        MaterialTheme {
            Surface {
                Column(Modifier.fillMaxSize()) {
                    ControlsPanel(app)
                    TabBar(app)
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

/** Map a top-row number key to its digit (0–9), or null. */
private fun digitKey(key: Key): Int? = when (key) {
    Key.Zero -> 0
    Key.One -> 1
    Key.Two -> 2
    Key.Three -> 3
    Key.Four -> 4
    Key.Five -> 5
    Key.Six -> 6
    Key.Seven -> 7
    Key.Eight -> 8
    Key.Nine -> 9
    else -> null
}
