package com.breeth.paint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeth.paint.app.AppState
import com.breeth.paint.model.Colors
import com.breeth.paint.tools.MouseButton

/**
 * Minimal color picker (spec §3): primary/secondary swatches, a preset
 * palette, and a hex field for a custom primary color. Left-click a swatch sets
 * primary; right-click sets secondary.
 */
@Composable
fun ColorPicker(app: AppState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CurrentColors(app)
        PaletteGrid(app)
        HexField(app)
    }
}

@Composable
private fun CurrentColors(app: AppState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Colors", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LabeledSwatch("P", app.primary)
            LabeledSwatch("S", app.secondary)
        }
    }
}

@Composable
private fun LabeledSwatch(label: String, argb: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ColorBox(argb, size = 28)
        Text(label, fontSize = 10.sp)
    }
}

private val PALETTE: List<Int> = listOf(
    Colors.BLACK, 0xFF7F7F7F.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8C8C8.toInt(),
    0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(),
    0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFF8000.toInt(), 0xFF8000FF.toInt(),
    0xFF804000.toInt(), 0xFF008040.toInt(), 0xFFFFC0CB.toInt(), Colors.TRANSPARENT,
)

@Composable
private fun PaletteGrid(app: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PALETTE.chunked(8).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowColors.forEach { c ->
                    ColorBox(
                        argb = c,
                        size = 20,
                        onPick = { button ->
                            if (button == MouseButton.SECONDARY) app.secondary = c else app.primary = c
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorBox(argb: Int, size: Int, onPick: ((MouseButton) -> Unit)? = null) {
    var mod = Modifier
        .size(size.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(Color(argb))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
    if (onPick != null) {
        mod = mod.pointerInput(argb) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press) {
                        val button = if (event.buttons.isSecondaryPressed) {
                            MouseButton.SECONDARY
                        } else {
                            MouseButton.PRIMARY
                        }
                        onPick(button)
                        event.changes.firstOrNull()?.consume()
                    }
                }
            }
        }
    }
    androidx.compose.foundation.layout.Box(mod)
}

@Composable
private fun HexField(app: AppState) {
    var text by remember { mutableStateOf(toHex(app.primary)) }
    // #2: re-sync when primary changes elsewhere (eyedropper / palette swatch),
    // but leave an in-progress edit alone when it already represents the value.
    LaunchedEffect(app.primary) {
        val hex = toHex(app.primary)
        if (!text.equals(hex, ignoreCase = true)) text = hex
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            parseHex(it)?.let { argb -> app.primary = argb }
        },
        label = { Text("Hex", fontSize = 10.sp) },
        singleLine = true,
        // Report focus so number keys are typed here instead of switching frames.
        modifier = Modifier.width(120.dp).onFocusChanged { app.textFieldFocused = it.isFocused },
    )
}

private fun toHex(argb: Int): String =
    "#%02X%02X%02X".format(Colors.red(argb), Colors.green(argb), Colors.blue(argb))

private fun parseHex(input: String): Int? {
    val s = input.trim().removePrefix("#")
    if (s.length != 6) return null
    return try {
        val r = s.substring(0, 2).toInt(16)
        val g = s.substring(2, 4).toInt(16)
        val b = s.substring(4, 6).toInt(16)
        Colors.argb(255, r, g, b)
    } catch (_: NumberFormatException) {
        null
    }
}
