package com.breeth.paint.tools

import androidx.compose.ui.unit.IntOffset
import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas
import com.breeth.paint.tools.stroke.strokeLine
import kotlin.math.abs
import kotlin.math.max

/**
 * Line (spec §4.6): click-drag from press to release with a live preview, using
 * the current color (left/right) and brush size. Shift snaps to 0°/45°/90°.
 */
class LineTool : PreviewTool() {
    override fun rasterize(canvas: PixelCanvas, start: IntOffset, end: IntOffset, app: AppState, e: ToolEvent) {
        // Always hard-edged: no anti-aliased half-alpha pixels (the AA option is brush-only).
        strokeLine(canvas, start.x, start.y, end.x, end.y, app.brushSize / 2f, app.colorFor(e.button), antialias = false)
    }

    override fun constrain(start: IntOffset, end: IntOffset, e: ToolEvent): IntOffset {
        if (!e.shift) return end
        val dx = end.x - start.x
        val dy = end.y - start.y
        val adx = abs(dx)
        val ady = abs(dy)
        return when {
            adx > 2 * ady -> IntOffset(end.x, start.y)                 // horizontal
            ady > 2 * adx -> IntOffset(start.x, end.y)                 // vertical
            else -> {                                                  // diagonal
                val m = max(adx, ady)
                IntOffset(start.x + isign(dx) * m, start.y + isign(dy) * m)
            }
        }
    }
}
