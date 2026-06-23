package com.breeth.paint.tools.stroke

import com.breeth.paint.model.Colors
import com.breeth.paint.model.PixelCanvas
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Stroke interpolation + stamping helpers (impl doc §3.3, §3.5, §14.4).
 *
 * Freehand tools call [line] to fill the gap between successive pointer
 * samples (so fast strokes leave no holes, spec §4.1), stamping a disc at each
 * interpolated pixel via [stampDisc].
 */

/** Bresenham line from (x0,y0) to (x1,y1), invoking [plot] for every cell. */
inline fun line(x0: Int, y0: Int, x1: Int, y1: Int, plot: (Int, Int) -> Unit) {
    var x = x0
    var y = y0
    val dx = abs(x1 - x0)
    val dy = -abs(y1 - y0)
    val sx = if (x0 < x1) 1 else -1
    val sy = if (y0 < y1) 1 else -1
    var err = dx + dy
    while (true) {
        plot(x, y)
        if (x == x1 && y == y1) break
        val e2 = 2 * err
        if (e2 >= dy) {
            if (x == x1) break
            err += dy
            x += sx
        }
        if (e2 <= dx) {
            if (y == y1) break
            err += dx
            y += sy
        }
    }
}

/**
 * Stamp a filled disc of radius `radius` (pixels) centered at (cx, cy).
 *
 * - `replace = true`  → hard write with [PixelCanvas.set] (pencil / eraser).
 * - `replace = false` → composite with [PixelCanvas.blend] (brush).
 * - `antialias = true` → 1px linear coverage ramp at the edge (spec §4.2);
 *   otherwise a hard 0.5 threshold (pencil hard edges, spec §4.1).
 *
 * Final source alpha = `color.alpha * coverage` (impl doc §14.4).
 */
fun stampDisc(
    canvas: PixelCanvas,
    cx: Int,
    cy: Int,
    radius: Float,
    color: Int,
    antialias: Boolean,
    replace: Boolean,
) {
    val r = ceil(radius).toInt().coerceAtLeast(0)
    val baseAlpha = Colors.alpha(color)
    for (dy in -r..r) {
        for (dx in -r..r) {
            val px = cx + dx
            val py = cy + dy
            if (!canvas.inBounds(px, py)) continue

            val dist = hypot(dx.toFloat(), dy.toFloat())
            val coverage = coverageAt(dist, radius, antialias)
            if (coverage <= 0f) continue

            if (replace && coverage >= 1f) {
                canvas.set(px, py, color)
            } else if (replace) {
                // Hard-edge tool with a fractional edge pixel: still a replace,
                // but scale alpha by coverage so AA-less tools stay crisp
                // (coverage is 0/1 when antialias = false, so this path is AA-only).
                val a = (baseAlpha * coverage).roundToInt().coerceIn(0, 255)
                canvas.set(px, py, Colors.withAlpha(color, a))
            } else {
                val a = (baseAlpha * coverage).roundToInt().coerceIn(0, 255)
                canvas.blend(px, py, Colors.withAlpha(color, a))
            }
        }
    }
}

/** 1.0 inside, 0.0 outside, a 1px linear ramp between when AA is on (§14.4). */
private fun coverageAt(dist: Float, radius: Float, antialias: Boolean): Float {
    if (!antialias) return if (dist <= radius) 1f else 0f
    return when {
        dist <= radius - 0.5f -> 1f
        dist >= radius + 0.5f -> 0f
        else -> (radius + 0.5f - dist).coerceIn(0f, 1f)
    }
}
