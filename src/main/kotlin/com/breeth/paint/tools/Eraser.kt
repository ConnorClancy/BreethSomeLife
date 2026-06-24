package com.breeth.paint.tools

import com.breeth.paint.app.AppState
import com.breeth.paint.model.Colors
import com.breeth.paint.model.PixelCanvas

/**
 * Eraser (spec §4.3): removes foreground by punching pixels to fully transparent
 * (alpha = 0). The background layer (checkerboard when transparent, else the
 * solid background color) shows through — so erasing is non-destructive and
 * consistent in both modes. Hard-edged, same path interpolation as the pencil.
 */
class Eraser : FreehandTool() {
    override fun stamp(canvas: PixelCanvas, x: Int, y: Int, app: AppState, e: ToolEvent) {
        disc(canvas, x, y, app, Colors.TRANSPARENT, antialias = false, replace = true)
    }
}
