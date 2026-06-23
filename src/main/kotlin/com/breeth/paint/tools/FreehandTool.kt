package com.breeth.paint.tools

import androidx.compose.ui.unit.IntOffset
import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas
import com.breeth.paint.tools.stroke.line
import com.breeth.paint.tools.stroke.stampDisc

/**
 * Shared freehand behavior for pencil / brush / eraser (impl doc §3.3): stamp
 * on press, then interpolate a Bresenham line between consecutive samples on
 * drag so fast movement leaves no gaps (spec §4.1).
 *
 * One instance is reused across gestures; `last` is per-gesture state, which is
 * safe because only one gesture runs at a time.
 */
abstract class FreehandTool : Tool {
    private var last: IntOffset? = null

    /** Disc radius in pixels for the current state. */
    protected fun radius(app: AppState): Float = app.brushSize / 2f

    /** Stamp a single disc at the given pixel. */
    protected abstract fun stamp(canvas: PixelCanvas, x: Int, y: Int, app: AppState, e: ToolEvent)

    override fun onPress(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        stamp(canvas, e.pixel.x, e.pixel.y, app, e)
        last = e.pixel
    }

    override fun onDrag(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        val from = last
        if (from == null) {
            stamp(canvas, e.pixel.x, e.pixel.y, app, e)
        } else {
            line(from.x, from.y, e.pixel.x, e.pixel.y) { x, y ->
                stamp(canvas, x, y, app, e)
            }
        }
        last = e.pixel
    }

    override fun onRelease(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        last = null
    }

    /** Convenience used by subclasses. */
    protected fun disc(
        canvas: PixelCanvas,
        x: Int,
        y: Int,
        app: AppState,
        color: Int,
        antialias: Boolean,
        replace: Boolean,
    ) = stampDisc(canvas, x, y, radius(app), color, antialias, replace)
}
