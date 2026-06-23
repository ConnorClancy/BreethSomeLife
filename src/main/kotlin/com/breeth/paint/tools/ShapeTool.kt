package com.breeth.paint.tools

import androidx.compose.ui.unit.IntOffset
import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas
import com.breeth.paint.tools.stroke.rasterizeEllipse
import com.breeth.paint.tools.stroke.rasterizeRect
import kotlin.math.abs
import kotlin.math.max

/**
 * Rectangle / ellipse (spec §4.7): click-drag a bounding box with a live
 * preview. Outline = primary, fill = secondary, per the current [AppState.fillMode].
 * Shift constrains to a square / circle.
 */
class ShapeTool(private val kind: ShapeKind) : PreviewTool() {
    override fun rasterize(canvas: PixelCanvas, start: IntOffset, end: IntOffset, app: AppState, e: ToolEvent) {
        val l = minOf(start.x, end.x)
        val t = minOf(start.y, end.y)
        val r = maxOf(start.x, end.x)
        val b = maxOf(start.y, end.y)
        val radius = app.brushSize / 2f
        // Always hard-edged: no anti-aliased half-alpha pixels (the AA option is brush-only).
        when (kind) {
            ShapeKind.RECTANGLE ->
                rasterizeRect(canvas, l, t, r, b, app.fillMode, app.primary, app.secondary, radius, antialias = false)
            ShapeKind.ELLIPSE ->
                rasterizeEllipse(canvas, l, t, r, b, app.fillMode, app.primary, app.secondary, radius, antialias = false)
        }
    }

    override fun constrain(start: IntOffset, end: IntOffset, e: ToolEvent): IntOffset {
        if (!e.shift) return end
        val dx = end.x - start.x
        val dy = end.y - start.y
        val m = max(abs(dx), abs(dy))
        return IntOffset(start.x + isign(dx) * m, start.y + isign(dy) * m)
    }
}
