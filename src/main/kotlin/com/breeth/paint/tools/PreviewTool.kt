package com.breeth.paint.tools

import androidx.compose.ui.unit.IntOffset
import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas

/**
 * Base for tools that show a live preview and commit on release (impl doc §3.3:
 * line, shapes). Implemented by snapshot-restore: on press we back up the
 * pre-gesture buffer; each drag restores it and re-rasterizes from the press
 * point to the current point, so the preview is pixel-accurate (it reuses the
 * exact commit rasterization). Release leaves the final rasterization in place.
 *
 * Undo is still one step: AppState snapshots on press and pushes on release
 * (§15.3); this backup is only for redrawing the preview, not for undo.
 */
abstract class PreviewTool : Tool {
    private var start: IntOffset? = null
    private var backup: IntArray? = null

    /** Draw the shape/line from [start] to [end] into the (already restored) buffer. */
    protected abstract fun rasterize(canvas: PixelCanvas, start: IntOffset, end: IntOffset, app: AppState, e: ToolEvent)

    /** Optional modifier constraint (e.g. Shift → square / 45°). Default: no constraint. */
    protected open fun constrain(start: IntOffset, end: IntOffset, e: ToolEvent): IntOffset = end

    override fun onPress(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        start = e.pixel
        backup = canvas.copyOfPixels()
        rasterize(canvas, e.pixel, e.pixel, app, e)
    }

    override fun onDrag(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        val s = start ?: return
        val b = backup ?: return
        System.arraycopy(b, 0, canvas.pixels, 0, b.size)   // restore pre-gesture state
        rasterize(canvas, s, constrain(s, e.pixel, e), app, e)
    }

    override fun onRelease(canvas: PixelCanvas, e: ToolEvent, app: AppState) {
        val s = start
        val b = backup
        if (s != null && b != null) {
            System.arraycopy(b, 0, canvas.pixels, 0, b.size)
            rasterize(canvas, s, constrain(s, e.pixel, e), app, e)
        }
        start = null
        backup = null
    }
}

/** Integer sign helper for modifier constraints. */
internal fun isign(n: Int): Int = if (n > 0) 1 else if (n < 0) -1 else 0
