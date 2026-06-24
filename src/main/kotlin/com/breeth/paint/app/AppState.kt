package com.breeth.paint.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breeth.paint.model.Colors
import com.breeth.paint.model.PixelCanvas
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

/**
 * The Store (impl doc §4): single source of UI/tool state.
 *
 * Scope note: build-order steps 1–5. Frames, the FrameManager, selection and
 * clipboard (§4 diagram, spec §6/§8) are deferred — for now there is a single
 * [canvas]. Undo/redo follows §15.3 so each stroke/shape collapses to one step;
 * a [Snapshot] also captures size + transparency so resize/toggle undo cleanly.
 */
class AppState {
    object CanvasDefaults {
        const val WIDTH = 800
        const val HEIGHT = 600
        val BACKGROUND = Colors.WHITE          // opaque white; used by the opaque eraser / flatten
        // Start transparent so the checkerboard is visible per build-order step 2
        // ("CanvasView showing a checkerboard and an empty canvas"). This overrides
        // spec §2.1's default-Off; the step-5 transparency toggle will switch it.
        const val TRANSPARENT_MODE = true
        const val HISTORY = 20                 // spec §6: minimum 20 undo steps
        const val MAX_SIZE = 4096              // spec §2.1 practical upper bound
    }

    // --- Document (single canvas for now) ---------------------------------
    val canvas: PixelCanvas = PixelCanvas(CanvasDefaults.WIDTH, CanvasDefaults.HEIGHT).also {
        // Foreground starts empty (alpha 0); the background layer renders behind it.
        it.fill(Colors.TRANSPARENT)
    }
    var backgroundColor: Int by mutableStateOf(CanvasDefaults.BACKGROUND)
    var transparent: Boolean by mutableStateOf(CanvasDefaults.TRANSPARENT_MODE)

    /** Bumped after every buffer mutation so the CanvasView re-uploads & redraws (§3.2). */
    var version: Int by mutableStateOf(0)
        private set

    // --- Tools & options --------------------------------------------------
    var activeTool: ToolType by mutableStateOf(ToolType.PENCIL)
    var primary: Int by mutableStateOf(Colors.BLACK)
    var secondary: Int by mutableStateOf(Colors.WHITE)
    var brushSize: Int by mutableStateOf(3)
    var brushAntialias: Boolean by mutableStateOf(true)
    var fillMode: FillMode by mutableStateOf(FillMode.OUTLINE)   // shape/line outline+fill (spec §4.7)
    var fillTolerance: Int by mutableStateOf(0)                  // flood-fill match threshold 0–255 (§14.2)

    val viewport = Viewport()

    private val pencil = Pencil()
    private val brush = Brush()
    private val eraser = Eraser()
    private val fill = FillTool()
    private val eyedropper = Eyedropper()
    private val line = LineTool()
    private val rectangle = ShapeTool(ShapeKind.RECTANGLE)
    private val ellipse = ShapeTool(ShapeKind.ELLIPSE)

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

    // --- Undo / redo (impl doc §3.4, §15.3) -------------------------------

    /** A restorable canvas state. Captures size + transparency so resize and the
     *  transparency toggle undo correctly, not just pixel edits. */
    private class Snapshot(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val transparent: Boolean,
        val backgroundColor: Int,
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var pendingUndo: Snapshot? = null
    private var gestureTool: Tool? = null    // captured at press so it's stable across the gesture

    // Reactive so the menu items / toolbar buttons (and their shortcuts) enable
    // correctly as the stacks change — plain getters over the deques aren't observable.
    var canUndo: Boolean by mutableStateOf(false)
        private set
    var canRedo: Boolean by mutableStateOf(false)
        private set

    private fun syncHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun bump() { version++ }

    private fun snapshot(): Snapshot =
        Snapshot(canvas.width, canvas.height, canvas.copyOfPixels(), transparent, backgroundColor)

    private fun restore(s: Snapshot) {
        canvas.width = s.width
        canvas.height = s.height
        canvas.pixels = s.pixels
        transparent = s.transparent
        backgroundColor = s.backgroundColor
    }

    private fun pushUndo(before: Snapshot) {
        undoStack.addLast(before)
        if (undoStack.size > CanvasDefaults.HISTORY) undoStack.removeFirst()
        redoStack.clear()
        syncHistoryFlags()
    }

    /** Run a whole-buffer op (clear/resize/toggle) as a single undo step. */
    private fun edit(block: () -> Unit) {
        val before = snapshot()
        block()
        pushUndo(before)
        bump()
    }

    /** Gesture dispatch: snapshot-before, mutate, push-once (§15.3). */
    fun onPress(e: ToolEvent) {
        val tool = toolInstance()
        gestureTool = tool
        // Non-mutating tools (eyedropper) take no undo snapshot (§15.3).
        pendingUndo = if (tool.mutates) snapshot() else null
        tool.onPress(canvas, e, this)
        if (tool.mutates) bump()   // #13: don't re-upload an unchanged buffer
    }

    fun onDrag(e: ToolEvent) {
        val tool = gestureTool ?: return
        tool.onDrag(canvas, e, this)
        if (tool.mutates) bump()
    }

    fun onRelease(e: ToolEvent) {
        val tool = gestureTool
        tool?.onRelease(canvas, e, this)
        // #1: only record undo if pixels actually changed — no phantom entries
        // from out-of-bounds taps, fill no-ops, or transparent-over-transparent.
        pendingUndo?.let { before ->
            if (!before.pixels.contentEquals(canvas.pixels)) pushUndo(before)
        }
        pendingUndo = null
        gestureTool = null
        if (tool?.mutates == true) bump()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(prev)
        syncHistoryFlags()
        bump()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next)
        syncHistoryFlags()
        bump()
    }

    // --- Canvas operations (spec §2.4, impl doc §3.6–3.8) -----------------
    //
    // Background model: the pixel buffer is always the foreground-with-alpha.
    // The background (transparent checkerboard vs. solid `backgroundColor`) is a
    // display + export layer, never baked into pixels — so [toggleTransparency]
    // is non-destructive and export can faithfully keep or flatten alpha.
    // (This deviates from spec §3.8's destructive ON→OFF flatten by design.)

    /** New document: reset to defaults, zoom to 100%, and clear history (not undoable, spec §2.4). */
    fun newDocument() {
        backgroundColor = CanvasDefaults.BACKGROUND
        transparent = CanvasDefaults.TRANSPARENT_MODE
        canvas.resizeTo(CanvasDefaults.WIDTH, CanvasDefaults.HEIGHT, Colors.TRANSPARENT)
        undoStack.clear()
        redoStack.clear()
        pendingUndo = null
        syncHistoryFlags()
        viewport.reset()
        bump()
    }

    /** Clear the foreground to empty; the background layer shows behind it (spec §2.4). One undo step. */
    fun clearCanvas() = edit { canvas.fill(Colors.TRANSPARENT) }

    /** Resize/crop, anchored top-left; new area is empty foreground (spec §2.2, impl doc §3.6). */
    fun resizeCanvas(newW: Int, newH: Int) {
        val w = newW.coerceIn(1, CanvasDefaults.MAX_SIZE)
        val h = newH.coerceIn(1, CanvasDefaults.MAX_SIZE)
        if (w == canvas.width && h == canvas.height) return
        edit { canvas.resizeTo(w, h, Colors.TRANSPARENT) }
    }

    /** Toggle the background on/off (non-destructive); pixels are untouched. One undo step. */
    fun toggleTransparency() = edit { transparent = !transparent }
}
