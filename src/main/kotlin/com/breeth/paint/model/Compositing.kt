package com.breeth.paint.model

/**
 * Compositing primitives (impl doc §3.5).
 *
 * Pixels are packed, NON-premultiplied ARGB ints: `0xAARRGGBB`, matching
 * java.awt's `BufferedImage.TYPE_INT_ARGB` so the bitmap bridge (§11) is a
 * straight array copy.
 */
object Colors {
    const val TRANSPARENT: Int = 0x00000000
    val BLACK: Int = 0xFF000000.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    fun red(c: Int): Int = (c ushr 16) and 0xFF
    fun green(c: Int): Int = (c ushr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    /** Replace this color's alpha channel, keeping RGB. */
    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)
}

/**
 * Porter-Duff "source-over" on non-premultiplied ARGB, 0–255 integer math
 * (impl doc §3.5):
 *
 *     outA   = srcA + dstA * (1 - srcA)
 *     outRGB = (srcRGB*srcA + dstRGB*dstA*(1 - srcA)) / outA
 */
fun srcOver(src: Int, dst: Int): Int {
    val sa = (src ushr 24) and 0xFF
    if (sa == 255) return src
    if (sa == 0) return dst

    val da = (dst ushr 24) and 0xFF
    // dstA scaled by (1 - srcA), kept in 0..255 with rounding.
    val daw = da * (255 - sa) / 255
    val outA = sa + daw
    if (outA == 0) return Colors.TRANSPARENT

    fun channel(srcShift: Int, dstShift: Int): Int {
        val s = (src ushr srcShift) and 0xFF
        val d = (dst ushr dstShift) and 0xFF
        return (s * sa + d * daw + outA / 2) / outA
    }

    val r = channel(16, 16).coerceIn(0, 255)
    val g = channel(8, 8).coerceIn(0, 255)
    val b = channel(0, 0).coerceIn(0, 255)
    return (outA shl 24) or (r shl 16) or (g shl 8) or b
}
