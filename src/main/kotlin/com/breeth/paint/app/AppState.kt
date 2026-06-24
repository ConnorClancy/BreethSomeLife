package com.breeth.paint.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.breeth.paint.model.Colors
import com.breeth.paint.model.Frame
import com.breeth.paint.model.PixelCanvas
import com.breeth.paint.render.toImageBitmap
import com.breeth.paint.tools.Brush
import com.breeth.paint.tools.Eraser
import com.breeth.paint.tools.Eyedropper
import com.breeth.paint.tools.FillMode
import com.breeth.paint.tools.FillTool
import com.breeth.paint.tools.LineTool
import com.breeth.paint.tools.Pencil
import com.breeth.paint.tools.ShapeKind
import com.breeth.paint.tools.ShapeTool
import com.breeth.paint.tools.Tool
import com.breeth.paint.tools.ToolEvent
import com.breeth.paint.tools.ToolType
import kotlin.math.min

/**
 * The Store (impl doc §4): single source of UI/tool state.
 *
 * Holds the sprite frame tabs (spec §8); per-frame state (pixels, transparency,
 * undo/redo) lives on [Frame], while tool options and the viewport are global.
 * Frame management (create/navigate/size-lock/delete, §8.2/§8.4/§8.5) and the
 * onion-skin overlay (§8.3) are consolidated here rather than in a separate
 * FrameManager, since they all operate over [frames]/[activeFrameIndex].
 *
 * Still deferred: selection / clipboard (spec §6) and file I/O (§7, §8.6).
 */
class AppState {
    object CanvasDefaults {
        const val WIDTH = 800
        const val HEIGHT = 600
        val BACKGROUND = Colors.WHITE
        const val TRANSPARENT_MODE = true      // see Frame / background model
        const val MAX_SIZE = 4096              // spec §2.1 practical upper bound
    }

    // --- Frame tabs (spec §8.1) -------------------------------------------
    val frames = mutableStateListOf(newBaseFrame())
    var activeFrameIndex: Int by mutableStateOf(0)
        private set

    val active: Frame get() = frames[activeFrameIndex]

    // Delegating accessors so the UI/tools keep reading app.canvas/transparent/…
    // while the data lives on the active frame.
    val canvas: PixelCanvas get() = active.canvas
    val transparent: Boolean get() = active.transparent
    val backgroundColor: Int get() = active.backgroundColor
    val version: Int get() = active.version
    val canUndo: Boolean get() = active.canUndo
    val canRedo: Boolean get() = active.canRedo
    val onBase: Boolean get() = active.label == BASE
    val canDeleteActive: Boolean get() = !onBase
    val numberedFrameCount: Int get() = frames.count { it.label != BASE }

    /** True while a text field (e.g. the hex color input) has focus, so number
     *  keys are typed instead of triggering frame navigation. */
    var textFieldFocused: Boolean by mutableStateOf(false)

    // --- Tools & options (global) -----------------------------------------
    var activeTool: ToolType by mutableStateOf(ToolType.PENCIL)
    var primary: Int by mutableStateOf(Colors.BLACK)
    var secondary: Int by mutableStateOf(Colors.WHITE)
    var brushSize: Int by mutableStateOf(3)
    var brushAntialias: Boolean by mutableStateOf(true)
    var fillMode: FillMode by mutableStateOf(FillMode.OUTLINE)
    var fillTolerance: Int by mutableStateOf(0)

    val viewport = Viewport()

    // --- Onion skin (spec §8.3) -------------------------------------------
    var onionSkin: Boolean by mutableStateOf(false)
        private set
    var onionImage: ImageBitmap? by mutableStateOf(null)
        private set

    /** Locked size shared by all numbered frames (spec §8.4); null until the first is made. */
    private var lockedW = 0
    private var lockedH = 0
    private val hasLock: Boolean get() = lockedW > 0

    private val pencil = Pencil()
    private val brush = Brush()
    private val eraser = Eraser()
    private val fill = FillTool()
    private val eyedropper = Eyedropper()
    private val line = LineTool()
    private val rectangle = ShapeTool(ShapeKind.RECTANGLE)
    private val ellipse = ShapeTool(ShapeKind.ELLIPSE)

    private var gestureTool: Tool? = null
    private var gestureFrame: Frame? = null

    fun toolInstance(): Tool = when (activeTool) {
        ToolType.PENCIL -> pencil
        ToolType.BRUSH -> brush
        ToolType.ERASER -> eraser
        ToolType.FILL -> fill
        ToolType.EYEDROPPER -> eyedropper
        ToolType.LINE -> line
        ToolType.RECTANGLE -> rectangle
        ToolType.ELLIPSE -> ellipse
    }

    // --- Gesture dispatch (operates on the active frame, §15.3) -----------
    fun onPress(e: ToolEvent) {
        val tool = toolInstance()
        val frame = active
        gestureTool = tool
        gestureFrame = frame
        frame.beginGesture(tool.mutates)
        tool.onPress(frame.canvas, e, this)
        if (tool.mutates) frame.bump()
    }

    fun onDrag(e: ToolEvent) {
        val tool = gestureTool ?: return
        val frame = gestureFrame ?: return
        tool.onDrag(frame.canvas, e, this)
        if (tool.mutates) frame.bump()
    }

    fun onRelease(e: ToolEvent) {
        val tool = gestureTool
        val frame = gestureFrame
        if (tool != null && frame != null) {
            tool.onRelease(frame.canvas, e, this)
            frame.commitGesture()
            if (tool.mutates) frame.bump()
        }
        gestureTool = null
        gestureFrame = null
    }

    fun undo() = active.undo()
    fun redo() = active.redo()

    // --- Canvas operations (delegate to the active frame) -----------------
    fun clearCanvas() = active.clearCanvas()
    fun toggleTransparency() = active.toggleTransparency()

    /**
     * Resize (spec §2.4, §3.6). Only `base` is resizable (the UI greys it out on
     * numbered frames); the resize **propagates to every frame** so they stay the
     * same size (and aligned for onion-skin), cropping right/bottom as needed.
     * (Deviates from spec §8.4's "base exempt" by request.)
     */
    fun resizeCanvas(newW: Int, newH: Int) {
        if (!onBase) return
        val w = newW.coerceIn(1, CanvasDefaults.MAX_SIZE)
        val h = newH.coerceIn(1, CanvasDefaults.MAX_SIZE)
        if (w == active.canvas.width && h == active.canvas.height) return
        for (f in frames) f.resizeTo(w, h)
        if (hasLock) { lockedW = w; lockedH = h }   // keep numbered-frame lock in sync
    }

    /** New document: discard all tabs, start a fresh `base`, reset zoom & lock (spec §2.4, §3.7). */
    fun newDocument() {
        frames.clear()
        frames.add(newBaseFrame())
        activeFrameIndex = 0
        lockedW = 0
        lockedH = 0
        clearOnion()
        viewport.reset()
    }

    // --- Frame management (spec §8.2, §8.4, §8.5) -------------------------

    /** Activate an existing tab by index. */
    fun activateFrame(index: Int) {
        if (index in frames.indices && index != activeFrameIndex) {
            activeFrameIndex = index
            clearOnion()    // a stale previous-frame snapshot would no longer apply
        }
    }

    /** Number key: navigate to tab `n`, creating it (as a snapshot of the nearest lower tab) if absent (§8.2). */
    fun gotoOrCreateFrame(n: Int) {
        if (n !in 1..9) return
        val existing = frames.indexOfFirst { it.label == n.toString() }
        if (existing >= 0) {
            activateFrame(existing)
            return
        }
        val source = highestFrameBelow(n)
        // First numbered frame establishes the locked size from base's size now (§8.4).
        val w = if (hasLock) lockedW else source.canvas.width
        val h = if (hasLock) lockedH else source.canvas.height
        val frame = Frame(
            label = n.toString(),
            canvas = PixelCanvas(w, h, copyPixels(source.canvas, w, h)),
            transparent = source.transparent,
            backgroundColor = source.backgroundColor,
        )
        var insertAt = frames.indexOfFirst { key(it.label) > n }
        if (insertAt < 0) insertAt = frames.size
        frames.add(insertAt, frame)
        if (!hasLock) { lockedW = w; lockedH = h }
        activeFrameIndex = insertAt
        clearOnion()
    }

    /** Delete a numbered tab and renumber higher tabs down by one (§8.5). `base` can't be deleted. */
    fun deleteFrame(index: Int) {
        val frame = frames.getOrNull(index) ?: return
        if (frame.label == BASE) return
        val keepActive = active
        frames.removeAt(index)
        var n = 1
        for (f in frames) if (f.label != BASE) f.label = (n++).toString()
        if (frames.none { it.label != BASE }) { lockedW = 0; lockedH = 0 }   // lock released when none remain
        activeFrameIndex = if (frame === keepActive) {
            index.coerceAtMost(frames.size - 1)
        } else {
            frames.indexOf(keepActive)
        }
        clearOnion()
    }

    /** Highest existing tab whose index is `< n` (base counts as 0); always at least `base` (§8.2). */
    private fun highestFrameBelow(n: Int): Frame =
        frames.filter { key(it.label) < n }.maxByOrNull { key(it.label) } ?: frames.first()

    private fun key(label: String): Int = if (label == BASE) 0 else label.toInt()

    /** Copy `source` pixels into a w×h block, anchored top-left (crop/extend transparent). */
    private fun copyPixels(source: PixelCanvas, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h) { Colors.TRANSPARENT }
        val copyW = min(source.width, w)
        val copyH = min(source.height, h)
        for (y in 0 until copyH) {
            System.arraycopy(source.pixels, y * source.width, dst, y * w, copyW)
        }
        return dst
    }

    // --- Onion skin (spec §8.3) -------------------------------------------

    /** Toggle the previous-frame overlay (no-op on `base`). Snapshots at toggle time. */
    fun toggleOnionSkin() {
        if (onBase) return
        if (onionSkin) {
            clearOnion()
        } else {
            onionImage = highestFrameBelow(key(active.label)).canvas.toImageBitmap()
            onionSkin = true
        }
    }

    private fun clearOnion() {
        onionSkin = false
        onionImage = null
    }

    private fun newBaseFrame(): Frame = Frame(
        label = BASE,
        canvas = PixelCanvas(CanvasDefaults.WIDTH, CanvasDefaults.HEIGHT).also { it.fill(Colors.TRANSPARENT) },
        transparent = CanvasDefaults.TRANSPARENT_MODE,
        backgroundColor = CanvasDefaults.BACKGROUND,
    )

    private companion object {
        const val BASE = "base"
    }
}
