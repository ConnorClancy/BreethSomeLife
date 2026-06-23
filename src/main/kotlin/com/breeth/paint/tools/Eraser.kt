package com.breeth.paint.tools

import com.breeth.paint.app.AppState
import com.breeth.paint.model.Colors
import com.breeth.paint.model.PixelCanvas

/**
 * Eraser (spec §4.3):
 * - opaque canvas → paints the frame's background color over the path.
 * - transparent canvas → punches pixels back to fully transparent (alpha = 0).
 *
 * Hard-edged replace either way, same path interpolation as the pencil.
 */
class Eraser : FreehandTool() {
    override fun stamp(canvas: PixelCanvas, x: Int, y: Int, app: AppState, e: ToolEvent) {
        val color = if (app.transparent) Colors.TRANSPARENT else app.backgroundColor
        disc(canvas, x, y, app, color, antialias = false, replace = true)
    }
}
