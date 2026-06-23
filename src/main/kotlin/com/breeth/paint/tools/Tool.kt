package com.breeth.paint.tools

import androidx.compose.ui.unit.IntOffset
import com.breeth.paint.app.AppState
import com.breeth.paint.model.PixelCanvas

/** Left / right mouse button, per spec §3 (primary / secondary color). */
enum class MouseButton { PRIMARY, SECONDARY }

/** The selectable tools. */
enum class ToolType { PENCIL, BRUSH, ERASER, FILL, EYEDROPPER, LINE, RECTANGLE, ELLIPSE }

/** Shape/line fill mode (spec §4.7, §5): outline = primary, fill = secondary. */
enum class FillMode { OUTLINE, FILLED, BOTH }

/** Which shape the shape tool draws (spec §4.7). */
enum class ShapeKind { RECTANGLE, ELLIPSE }

/**
 * A pointer sample already mapped to canvas pixel coords via the Viewport
 * (impl doc §15.2). Tools never see screen pixels or Compose/AWT types.
 *
 * `pixel` may be outside the canvas bounds (clip-tested by the canvas write
 * methods); it is still delivered so drag math (interpolation) stays correct.
 */
data class ToolEvent(
    val pixel: IntOffset,
    val button: MouseButton,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
)

/**
 * A tool mutates the active [PixelCanvas] in response to a gesture
 * (impl doc §15.2). Undo snapshotting / version bumps are handled by the
 * dispatch layer (AppState.beginStroke/endStroke, §15.3), not here.
 */
interface Tool {
    fun onPress(canvas: PixelCanvas, e: ToolEvent, app: AppState)
    fun onDrag(canvas: PixelCanvas, e: ToolEvent, app: AppState)
    fun onRelease(canvas: PixelCanvas, e: ToolEvent, app: AppState)

    /**
     * Whether this tool changes pixels. Non-mutating tools (e.g. eyedropper)
     * are dispatched without taking an undo snapshot, so they don't create a
     * phantom undo step (§15.3).
     */
    val mutates: Boolean get() = true

    /** Color for this gesture: primary on left, secondary on right (spec §3). */
    fun AppState.colorFor(button: MouseButton): Int =
        if (button == MouseButton.SECONDARY) secondary else primary
}
