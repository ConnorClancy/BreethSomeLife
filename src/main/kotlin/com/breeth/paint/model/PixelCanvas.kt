package com.breeth.paint.model

/**
 * The editable bitmap surface — the source of truth for a frame's pixels
 * (impl doc §3.1). One packed ARGB int per pixel, `index = y * width + x`.
 *
 * `width`/`height`/`pixels` are `var` because resize (§3.6) reallocates the
 * buffer in place.
 */
class PixelCanvas(
    var width: Int,
    var height: Int,
    var pixels: IntArray = IntArray(width * height) { Colors.TRANSPARENT },
) {
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Hard write — replaces the pixel outright (pencil, eraser, fill). Clipped to bounds. */
    fun set(x: Int, y: Int, argb: Int) {
        if (inBounds(x, y)) pixels[y * width + x] = argb
    }

    /** Alpha-composite `src` OVER the existing pixel (soft brush edges, paste). Clipped to bounds. */
    fun blend(x: Int, y: Int, src: Int) {
        if (inBounds(x, y)) {
            val i = y * width + x
            pixels[i] = srcOver(src, pixels[i])
        }
    }

    fun fill(argb: Int) {
        pixels.fill(argb)
    }

    fun copyOfPixels(): IntArray = pixels.copyOf()
}
