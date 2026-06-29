package com.breeth.paint.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.breeth.paint.app.AppState
import com.breeth.paint.tools.MouseButton
import com.breeth.paint.tools.ToolEvent
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Renders the canvas and routes pointer input to the active tool (impl doc
 * §3.2, §15.1–15.2).
 *
 * Draw order (bottom → top): transparency checkerboard (display only, spec
 * §2.3) → the canvas ImageBitmap (nearest-neighbor scaled, spec §2.2).
 * Onion-skin / floating-region / preview passes are added in later steps.
 *
 * Performance: both passes are clipped to the visible canvas rect so per-frame
 * cost is bounded by the viewport, not by zoom. The checkerboard is a single
 * tiled-shader draw (fixed 8px cells, screen-anchored → static like MS Paint),
 * and the bitmap pass draws only the visible source sub-rectangle rather than
 * scaling the whole image to a huge off-screen destination.
 */
@Composable
fun CanvasView(app: AppState, modifier: Modifier = Modifier) {
    // Re-upload the buffer whenever a mutation bumps the version (§3.2) or the
    // active frame changes (different frames can share a version number). The
    // cache reuses a single long-lived BufferedImage across frames.
    val imageCache = remember { CanvasImageCache() }
    val image = remember(app.activeFrameIndex, app.version) { imageCache.render(app.canvas) }

    // Built once: a 2x2-cell tile repeated by the shader (screen-anchored).
    val checkerBrush = remember {
        ShaderBrush(ImageShader(checkerTile(), TileMode.Repeated, TileMode.Repeated))
    }

    val w = app.canvas.width
    val h = app.canvas.height

    Canvas(
        modifier = modifier.pointerInput(app) {
            awaitPointerEventScope {
                var button: MouseButton? = null
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val mods = event.keyboardModifiers
                    val compW = size.width.toFloat()
                    val compH = size.height.toFloat()
                    // Read canvas dims live so hit-testing follows a resize.
                    val cw = app.canvas.width
                    val ch = app.canvas.height
                    // Keep the transform in sync with hit-testing (§15.1).
                    app.viewport.pan = app.viewport.resolvePan(compW, compH, cw, ch)
                    when (event.type) {
                        PointerEventType.Scroll -> {
                            val d = change.scrollDelta
                            if (mods.isCtrlPressed) {
                                if (d.y != 0f) {
                                    app.viewport.zoomAround(change.position, into = d.y < 0, compW, compH, cw, ch)
                                }
                            } else {
                                val delta = if (mods.isShiftPressed) {
                                    Offset(-d.y * SCROLL_STEP, 0f)
                                } else {
                                    Offset(-d.x * SCROLL_STEP, -d.y * SCROLL_STEP)
                                }
                                app.viewport.userScroll += delta
                            }
                            app.viewport.clampScroll(compW, compH, cw, ch)
                            change.consume()
                        }

                        PointerEventType.Press -> {
                            button = if (event.buttons.isSecondaryPressed) {
                                MouseButton.SECONDARY
                            } else {
                                MouseButton.PRIMARY
                            }
                            app.onPress(app.toolEvent(change.position, button!!, mods))
                            change.consume()
                        }

                        PointerEventType.Move -> {
                            button?.let {
                                app.onDrag(app.toolEvent(change.position, it, mods))
                                change.consume()
                            }
                        }

                        PointerEventType.Release -> {
                            button?.let {
                                app.onRelease(app.toolEvent(change.position, it, mods))
                                change.consume()
                            }
                            button = null
                        }
                    }
                }
            }
        },
    ) {
        val pan = app.viewport.resolvePan(size.width, size.height, w, h)
        app.viewport.pan = pan
        val scale = app.viewport.scale

        // Visible canvas rect on screen = canvas display rect ∩ component bounds.
        val left = maxOf(pan.x, 0f)
        val top = maxOf(pan.y, 0f)
        val right = minOf(pan.x + w * scale, size.width)
        val bottom = minOf(pan.y + h * scale, size.height)
        if (right <= left || bottom <= top) return@Canvas

        // Background layer behind the image (display only; never baked into pixels):
        // transparent → checkerboard (spec §2.3), opaque → the solid background color.
        // Both read app.transparent / backgroundColor here so toggling repaints reactively.
        clipRect(left, top, right, bottom) {
            val origin = Offset(left, top)
            val sizePx = Size(right - left, bottom - top)
            if (app.transparent) {
                drawRect(checkerBrush, topLeft = origin, size = sizePx)
            } else {
                drawRect(Color(app.backgroundColor), topLeft = origin, size = sizePx)
            }
        }

        // Bitmap: draw only the visible source sub-rectangle, scaled. Bounds the
        // destination to the viewport at any zoom (no giant off-screen raster).
        val srcL = floor((left - pan.x) / scale).toInt().coerceIn(0, w)
        val srcT = floor((top - pan.y) / scale).toInt().coerceIn(0, h)
        val srcR = ceil((right - pan.x) / scale).toInt().coerceIn(0, w)
        val srcB = ceil((bottom - pan.y) / scale).toInt().coerceIn(0, h)
        if (srcR > srcL && srcB > srcT) {
            drawImage(
                image = image,
                srcOffset = IntOffset(srcL, srcT),
                srcSize = IntSize(srcR - srcL, srcB - srcT),
                dstOffset = IntOffset((pan.x + srcL * scale).roundToInt(), (pan.y + srcT * scale).roundToInt()),
                dstSize = IntSize(((srcR - srcL) * scale).roundToInt(), ((srcB - srcT) * scale).roundToInt()),
                filterQuality = FilterQuality.None,   // crisp pixels at >100% zoom (spec §2.2)
            )
        }

        // Onion skin (spec §8.3): the snapshotted previous frame, drawn on top of
        // the current canvas at 30% alpha, aligned to the same pixel grid. Display
        // only — never written to any buffer. Mirrors the main pass's visible
        // sub-rectangle math so cost stays bounded by the viewport, not the zoom
        // level — no whole-snapshot scale + clip. The onion matches the canvas
        // size under the equal-size invariant; coerce defensively in case it doesn't.
        val onion = app.onionImage
        if (app.onionSkin && onion != null) {
            val oL = srcL.coerceIn(0, onion.width)
            val oT = srcT.coerceIn(0, onion.height)
            val oR = srcR.coerceIn(0, onion.width)
            val oB = srcB.coerceIn(0, onion.height)
            if (oR > oL && oB > oT) {
                drawImage(
                    image = onion,
                    srcOffset = IntOffset(oL, oT),
                    srcSize = IntSize(oR - oL, oB - oT),
                    dstOffset = IntOffset((pan.x + oL * scale).roundToInt(), (pan.y + oT * scale).roundToInt()),
                    dstSize = IntSize(((oR - oL) * scale).roundToInt(), ((oB - oT) * scale).roundToInt()),
                    alpha = 0.30f,
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}

private const val SCROLL_STEP = 60f

/** Map a screen position + modifiers to a tool event in canvas pixel coords. */
private fun AppState.toolEvent(
    screen: Offset,
    button: MouseButton,
    mods: PointerKeyboardModifiers,
): ToolEvent = ToolEvent(
    pixel = viewport.toPixel(screen),
    button = button,
    shift = mods.isShiftPressed,
    ctrl = mods.isCtrlPressed,
    alt = mods.isAltPressed,
)

private const val CHECKER_CELL = 8
private val CHECKER_LIGHT = 0xFFFFFFFF.toInt()
private val CHECKER_DARK = 0xFFC8C8C8.toInt()

/** A 2x2-cell checker tile (16x16 px) for the repeating shader brush. */
private fun checkerTile(): ImageBitmap {
    val n = CHECKER_CELL * 2
    val px = IntArray(n * n)
    for (y in 0 until n) {
        for (x in 0 until n) {
            val dark = ((x / CHECKER_CELL) + (y / CHECKER_CELL)) % 2 == 1
            px[y * n + x] = if (dark) CHECKER_DARK else CHECKER_LIGHT
        }
    }
    val img = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, n, n, px, 0, n)
    return img.toComposeImageBitmap()
}
