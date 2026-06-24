package com.breeth.paint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeth.paint.app.AppState
import com.breeth.paint.tools.FillMode
import com.breeth.paint.tools.ToolType
import kotlin.math.roundToInt

/**
 * Top controls panel (build steps 3–4): tool selection, brush size, AA toggle,
 * shape fill-mode, fill tolerance, zoom, undo/redo, and the color picker.
 * Laid out in a FlowRow so groups wrap as the window narrows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsPanel(app: AppState, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        FlowRow(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolButtons(app)
            BrushControls(app)
            // Tool-specific options only appear for the relevant tool.
            if (app.activeTool == ToolType.RECTANGLE || app.activeTool == ToolType.ELLIPSE) {
                ShapeOptions(app)
            }
            if (app.activeTool == ToolType.FILL) {
                FillToleranceControl(app)
            }
            ZoomControls(app)
            HistoryButtons(app)
            ColorPicker(app)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolButtons(app: AppState) {
    Column {
        GroupLabel("Tool")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 4) {
            ToolType.entries.forEach { tool ->
                Chip(
                    label = tool.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = app.activeTool == tool,
                    onClick = { app.activeTool = tool },
                )
            }
        }
    }
}

@Composable
private fun BrushControls(app: AppState) {
    Column {
        GroupLabel("Size: ${app.brushSize}px")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = app.brushSize.toFloat(),
                onValueChange = { app.brushSize = it.toInt().coerceIn(1, 32) },
                valueRange = 1f..32f,
                modifier = Modifier.width(140.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = app.brushAntialias, onCheckedChange = { app.brushAntialias = it })
                Text("AA", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ShapeOptions(app: AppState) {
    Column {
        GroupLabel("Shape fill")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FillMode.entries.forEach { mode ->
                Chip(
                    label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = app.fillMode == mode,
                    onClick = { app.fillMode = mode },
                )
            }
        }
    }
}

@Composable
private fun FillToleranceControl(app: AppState) {
    Column {
        GroupLabel("Tolerance: ${app.fillTolerance}")
        Slider(
            value = app.fillTolerance.toFloat(),
            onValueChange = { app.fillTolerance = it.roundToInt().coerceIn(0, 255) },
            valueRange = 0f..255f,
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun ZoomControls(app: AppState) {
    val percent = (app.viewport.scale * 100).roundToInt()
    Column {
        GroupLabel("Zoom")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { app.viewport.zoomOut() },
                enabled = app.viewport.canZoomOut,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text("−") }
            Text("$percent%", fontSize = 12.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.Center)
            OutlinedButton(
                onClick = { app.viewport.zoomIn() },
                enabled = app.viewport.canZoomIn,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text("+") }
            OutlinedButton(
                onClick = { app.viewport.reset() },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text("100%") }
        }
    }
}

@Composable
private fun HistoryButtons(app: AppState) {
    Column {
        GroupLabel("History")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(
                onClick = { app.undo() },
                enabled = app.canUndo,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text("Undo") }
            FilledTonalButton(
                onClick = { app.redo() },
                enabled = app.canRedo,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text("Redo") }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(label) }
    }
}
