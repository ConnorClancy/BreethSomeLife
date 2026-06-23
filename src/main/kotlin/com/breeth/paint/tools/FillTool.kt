package com.breeth.paint.tools

import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas
import com.breeth.paint.tools.stroke.floodFill

/**
 * Fill bucket (spec §4.4): flood-fills the contiguous same-colored region at the
 * click point with the active color, within [AppState.fillTolerance].
 */
class FillTool : Tool {
    override fun onPress(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        if (!canvas.inBounds(e.pixel.x, e.pixel.y)) return
        floodFill(canvas, e.pixel.x, e.pixel.y, app.colorFor(e.button), app.fillTolerance)
    }

    override fun onDrag(canvas: PixelCanvas, e: ToolEvent, app: AppState) {}
    override fun onRelease(canvas: PixelCanvas, e: ToolEvent, app: AppState) {}
}
