package com.breeth.paint.tools.stroke

import com.breeth.paint.model.Colors
import com.breeth.paint.model.PixelCanvas
import com.breeth.paint.tools.FillMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rasterizers for the fill bucket, line and shape tools (spec §4.4, §4.6–4.7;
 * impl doc §14.2–14.3). All write into a [PixelCanvas] via the shared
 * compositing/stamping helpers so edges match the brush.
 */

/**
 * A thick line: a disc of `radius` stamped along the Bresenham path.
 *
 * When `antialias` is false the disc is written hard (`replace`), so edges are
 * crisp — full color or nothing, never a softened half-alpha pixel.
 */
fun strokeLine(canvas: PixelCanvas, x0: Int, y0: Int, x1: Int, y1: Int, radius: Float, color: Int, antialias: Boolean) {
    line(x0, y0, x1, y1) { x, y -> stampDisc(canvas, x, y, radius, color, antialias, replace = !antialias) }
}

/** Rectangle within bounds [l,t]..[r,b] (spec §4.7); fill = secondary, outline = primary. */
fun rasterizeRect(
    canvas: PixelCanvas, l: Int, t: Int, r: Int, b: Int,
    fillMode: FillMode, primary: Int, secondary: Int, radius: Float, antialias: Boolean,
) {
    if (fillMode == FillMode.FILLED || fillMode == FillMode.BOTH) {
        for (y in t.coerceAtLeast(0)..b.coerceAtMost(canvas.height - 1)) {
            for (x in l.coerceAtLeast(0)..r.coerceAtMost(canvas.width - 1)) {
                canvas.set(x, y, secondary)
            }
        }
    }
    if (fillMode == FillMode.OUTLINE || fillMode == FillMode.BOTH) {
        strokeLine(canvas, l, t, r, t, radius, primary, antialias)
        strokeLine(canvas, r, t, r, b, radius, primary, antialias)
        strokeLine(canvas, r, b, l, b, radius, primary, antialias)
        strokeLine(canvas, l, b, l, t, radius, primary, antialias)
    }
}

/** Ellipse inscribed in bounds [l,t]..[r,b] (spec §4.7); fill = secondary, outline = primary. */
fun rasterizeEllipse(
    canvas: PixelCanvas, l: Int, t: Int, r: Int, b: Int,
    fillMode: FillMode, primary: Int, secondary: Int, radius: Float, antialias: Boolean,
) {
    val cx = (l + r) / 2f
    val cy = (t + b) / 2f
    val rx = (r - l) / 2f
    val ry = (b - t) / 2f

    if ((fillMode == FillMode.FILLED || fillMode == FillMode.BOTH) && rx > 0f && ry > 0f) {
        for (y in t.coerceAtLeast(0)..b.coerceAtMost(canvas.height - 1)) {
            for (x in l.coerceAtLeast(0)..r.coerceAtMost(canvas.width - 1)) {
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                if (nx * nx + ny * ny <= 1f) canvas.set(x, y, secondary)
            }
        }
    }
    if (fillMode == FillMode.OUTLINE || fillMode == FillMode.BOTH) {
        val steps = ceil(PI * (rx + ry)).toInt().coerceAtLeast(16)
        for (i in 0 until steps) {
            val theta = 2.0 * PI * i / steps
            val px = (cx + rx * cos(theta)).roundToInt()
            val py = (cy + ry * sin(theta)).roundToInt()
            stampDisc(canvas, px, py, radius, primary, antialias, replace = !antialias)
        }
    }
}

/**
 * 4-connected scanline flood fill (spec §4.4, impl doc §14.2). Replaces the
 * contiguous region matching the seed color (within `tolerance`) with
 * `replacement`.
 *
 * Spans (not pixels) are pushed onto a primitive [IntArray] stack — no
 * `Integer` autoboxing and far fewer pushes than per-pixel 4-connected fill. A
 * `visited` mask keeps it correct even when `replacement` is itself within
 * `tolerance` of the target (which a pure overwrite test could not).
 */
fun floodFill(canvas: PixelCanvas, sx: Int, sy: Int, replacement: Int, tolerance: Int) {
    if (!canvas.inBounds(sx, sy)) return
    val w = canvas.width
    val h = canvas.height
    val px = canvas.pixels
    val target = px[sy * w + sx]
    if (target == replacement) return            // no-op guard (impl doc §14.2)

    val visited = BooleanArray(w * h)
    val stack = IntStack(1024)
    stack.push(sy * w + sx)

    while (stack.isNotEmpty()) {
        val seed = stack.pop()
        if (visited[seed] || !colorMatch(px[seed], target, tolerance)) continue
        val y = seed / w
        val base = y * w
        val sxIdx = seed - base

        // Extend the span left and right across matching, unvisited pixels.
        var l = sxIdx
        while (l > 0 && !visited[base + l - 1] && colorMatch(px[base + l - 1], target, tolerance)) l--
        var r = sxIdx
        while (r < w - 1 && !visited[base + r + 1] && colorMatch(px[base + r + 1], target, tolerance)) r++

        for (k in l..r) {
            px[base + k] = replacement
            visited[base + k] = true
        }
        if (y > 0) pushSpanSeeds(px, visited, target, tolerance, w, l, r, y - 1, stack)
        if (y < h - 1) pushSpanSeeds(px, visited, target, tolerance, w, l, r, y + 1, stack)
    }
}

/** Push one seed per contiguous matching run in row [ry] across columns [l]..[r]. */
private fun pushSpanSeeds(
    px: IntArray, visited: BooleanArray, target: Int, tolerance: Int,
    w: Int, l: Int, r: Int, ry: Int, stack: IntStack,
) {
    val base = ry * w
    var k = l
    while (k <= r) {
        if (visited[base + k] || !colorMatch(px[base + k], target, tolerance)) {
            k++
            continue
        }
        stack.push(base + k)                 // first cell of a run
        k++
        while (k <= r && !visited[base + k] && colorMatch(px[base + k], target, tolerance)) k++
    }
}

/** Growable primitive-int stack (avoids boxing in [floodFill]). */
private class IntStack(initialCapacity: Int) {
    private var data = IntArray(initialCapacity)
    private var size = 0
    fun isNotEmpty(): Boolean = size > 0
    fun push(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun pop(): Int = data[--size]
}

/** Per-channel max (Chebyshev) ARGB distance within tolerance (impl doc §14.2). */
private fun colorMatch(c: Int, target: Int, tolerance: Int): Boolean {
    if (tolerance <= 0) return c == target
    val da = abs(Colors.alpha(c) - Colors.alpha(target))
    val dr = abs(Colors.red(c) - Colors.red(target))
    val dg = abs(Colors.green(c) - Colors.green(target))
    val db = abs(Colors.blue(c) - Colors.blue(target))
    return maxOf(da, dr, maxOf(dg, db)) <= tolerance
}
