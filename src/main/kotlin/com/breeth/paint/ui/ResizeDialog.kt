package com.breeth.paint.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeth.paint.app.AppState

/**
 * Resize dialog (spec §2.4): enter new width/height; resize is anchored
 * top-left and clamped to [AppState.CanvasDefaults.MAX_SIZE].
 */
@Composable
fun ResizeDialog(app: AppState, onDismiss: () -> Unit) {
    var widthText by remember { mutableStateOf(app.canvas.width.toString()) }
    var heightText by remember { mutableStateOf(app.canvas.height.toString()) }

    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val max = AppState.CanvasDefaults.MAX_SIZE
    val valid = width != null && height != null && width in 1..max && height in 1..max

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize canvas") },
        text = {
            Column {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Width") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Height") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "1–$max px. New area uses the background (transparent if in transparent mode).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (app.numberedFrameCount > 0) {
                    Text(
                        "⚠ This resizes all frames (base + ${app.numberedFrameCount} numbered) " +
                            "and may crop pixels outside the new size.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (width != null && height != null) {
                        app.resizeCanvas(width, height)
                        onDismiss()
                    }
                },
            ) { Text("Resize") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
