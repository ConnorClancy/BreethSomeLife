package com.breeth.paint.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.breeth.paint.model.PixelCanvas
import java.awt.image.BufferedImage

/**
 * Bridges the packed-ARGB [PixelCanvas] buffer to a Compose [ImageBitmap]
 * (impl doc §3.2, §11).
 *
 * `BufferedImage.TYPE_INT_ARGB` stores pixels as non-premultiplied `0xAARRGGBB`
 * ints — exactly our packing — so `setRGB` is a bulk array copy with no
 * per-pixel conversion.
 *
 * #6: the backing [BufferedImage] is **reused** across frames (reallocated only
 * when the canvas size changes), so a live stroke no longer allocates a fresh
 * `w*h` int buffer on every pointer-move — eliminating that per-frame GC churn.
 * The final Compose upload still happens each frame; the dirty-rect GPU
 * sub-upload (§15.4) remains a later optimization.
 */
class CanvasImageCache {
    private var buffer: BufferedImage? = null

    fun render(canvas: PixelCanvas): ImageBitmap {
        val w = canvas.width
        val h = canvas.height
        val img = buffer?.takeIf { it.width == w && it.height == h }
            ?: BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { buffer = it }
        img.setRGB(0, 0, w, h, canvas.pixels, 0, w)
        return img.toComposeImageBitmap()
    }
}

/**
 * One-off conversion of a canvas to an [ImageBitmap] — used for the onion-skin
 * snapshot (spec §8.3), which is captured once at toggle time (not per frame),
 * so it doesn't need the reusing cache.
 */
fun PixelCanvas.toImageBitmap(): ImageBitmap {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, pixels, 0, width)
    return img.toComposeImageBitmap()
}
