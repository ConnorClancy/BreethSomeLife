package com.breeth.paint.tools

import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas

/**
 * Brush (spec §4.2): like the pencil but with variable thickness and optional
 * soft / anti-aliased edges. Composited via blend so fractional-coverage edge
 * pixels combine correctly with the canvas (impl doc §3.5 / §14.4).
 */
class Brush : FreehandTool() {
    override fun stamp(canvas: PixelCanvas, x: Int, y: Int, app: AppState, e: ToolEvent) {
        disc(canvas, x, y, app, app.colorFor(e.button), antialias = app.brushAntialias, replace = false)
    }
}
