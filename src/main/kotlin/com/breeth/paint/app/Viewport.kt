package com.breeth.paint.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.floor

/**
 * Owns the single screen <-> pixel transform (impl doc §15.1) so every tool,
 * preview and hit-test agree.
 *
 * - [scale] is the display zoom factor; integer stops keep pixels crisp at
 *   >100% (spec §2.2), fractional stops allow zooming out.
 * - The image is centered in the component; [userScroll] pans it once it grows
 *   larger than the viewport.
 * - [pan] is the resolved top-left origin in screen px, recomputed each frame
 *   from the component size + scale + scroll via [resolvePan].
 *
 * [scale] and [userScroll] are snapshot state so the toolbar label and the
 * canvas redraw react to zoom/scroll changes.
 */
class Viewport {
    var scale: Float by mutableStateOf(1f)
    var userScroll: Offset by mutableStateOf(Offset.Zero)
    var pan: Offset = Offset.Zero

    /** screen -> pixel; floor so a whole on-screen block maps to one pixel. */
    fun toPixel(screen: Offset): IntOffset = IntOffset(
        x = floor((screen.x - pan.x) / scale).toInt(),
        y = floor((screen.y - pan.y) / scale).toInt(),
    )

    /** pixel -> screen origin of that pixel's block (for previews). */
    fun toScreen(px: Int, py: Int): Offset =
        Offset(px * scale + pan.x, py * scale + pan.y)

    fun inBounds(p: IntOffset, w: Int, h: Int): Boolean =
        p.x in 0 until w && p.y in 0 until h

    /** Resolve the centered (and scrolled, if oversized) top-left origin. */
    fun resolvePan(componentW: Float, componentH: Float, w: Int, h: Int): Offset {
        val dstW = w * scale
        val dstH = h * scale
        return Offset(axisOrigin(componentW, dstW, userScroll.x), axisOrigin(componentH, dstH, userScroll.y))
    }

    /** Clamp accumulated scroll to the valid range for the current size/scale. */
    fun clampScroll(componentW: Float, componentH: Float, w: Int, h: Int) {
        val mx = maxScroll(componentW, w * scale)
        val my = maxScroll(componentH, h * scale)
        userScroll = Offset(userScroll.x.coerceIn(-mx, mx), userScroll.y.coerceIn(-my, my))
    }

    fun zoomIn() { scale = STOPS.firstOrNull { it > scale + EPS } ?: scale }
    fun zoomOut() { scale = STOPS.lastOrNull { it < scale - EPS } ?: scale }
    fun reset() { scale = 1f; userScroll = Offset.Zero }

    /**
     * Zoom one stop in/out while keeping the canvas pixel under [cursor] fixed on
     * screen (impl doc §15.1). Requires [pan] to already be resolved for the
     * current frame. Centers when the canvas fits (resolvePan ignores scroll).
     */
    fun zoomAround(cursor: Offset, into: Boolean, componentW: Float, componentH: Float, w: Int, h: Int) {
        val panOld = pan
        val scaleOld = scale
        if (into) zoomIn() else zoomOut()
        if (scale == scaleOld) return
        // Canvas pixel currently under the cursor (fractional).
        val pixelX = (cursor.x - panOld.x) / scaleOld
        val pixelY = (cursor.y - panOld.y) / scaleOld
        // Solve for the scroll that maps that pixel back under the cursor.
        userScroll = Offset(
            (cursor.x - pixelX * scale) - (componentW - w * scale) / 2f,
            (cursor.y - pixelY * scale) - (componentH - h * scale) / 2f,
        )
        clampScroll(componentW, componentH, w, h)
    }

    val canZoomIn: Boolean get() = scale < STOPS.last() - EPS
    val canZoomOut: Boolean get() = scale > STOPS.first() + EPS

    private fun axisOrigin(comp: Float, dst: Float, scroll: Float): Float {
        if (dst <= comp) return (comp - dst) / 2f          // fits: center
        val maxS = (dst - comp) / 2f
        return (comp - dst) / 2f + scroll.coerceIn(-maxS, maxS)
    }

    private fun maxScroll(comp: Float, dst: Float): Float =
        if (dst <= comp) 0f else (dst - comp) / 2f

    companion object {
        /** Discrete zoom stops (25% … 1000%). */
        val STOPS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 2f, 3f, 4f, 6f, 8f, 10f)
        private const val EPS = 0.001f
    }
}
