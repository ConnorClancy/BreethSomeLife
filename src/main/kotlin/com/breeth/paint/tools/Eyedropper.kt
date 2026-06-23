package com.breeth.paint.tools

import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas

/**
 * Eyedropper / color picker (spec §4.5): clicking a pixel sets the active color
 * to that pixel's color — left → primary, right → secondary. Does not change
 * pixels, so it takes no undo step ([mutates] = false).
 */
class Eyedropper : Tool {
    override val mutates: Boolean get() = false

    override fun onPress(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        if (!canvas.inBounds(e.pixel.x, e.pixel.y)) return
        val picked = canvas.get(e.pixel.x, e.pixel.y)
        if (e.button == MouseButton.SECONDARY) app.secondary = picked else app.primary = picked
    }

    override fun onDrag(canvas: PixelCanvas, e: ToolEvent, app: AppState) {}
    override fun onRelease(canvas: PixelCanvas, e: ToolEvent, app: AppState) {}
}
