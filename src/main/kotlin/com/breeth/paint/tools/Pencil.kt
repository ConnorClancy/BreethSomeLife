package com.breeth.paint.tools

import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas

/**
 * Pencil (spec §4.1): hard-edged freehand, no anti-aliasing, full-alpha color
 * written via replace (impl doc §3.5).
 */
class Pencil : FreehandTool() {
    override fun stamp(canvas: PixelCanvas, x: Int, y: Int, app: AppState, e: ToolEvent) {
        disc(canvas, x, y, app, app.colorFor(e.button), antialias = false, replace = true)
    }
}
