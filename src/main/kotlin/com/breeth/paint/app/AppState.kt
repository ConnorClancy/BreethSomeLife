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
 * Scope note: build-order steps 1–4. Frames, the FrameManager, selection and
 * clipboard (§4 diagram, spec §6/§8) are deferred — for now there is a single
 * [canvas]. Undo/redo follows §15.3 so each stroke/shape collapses to one step.
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
    }

    // --- Document (single canvas for now) ---------------------------------
    val canvas: PixelCanvas = PixelCanvas(CanvasDefaults.WIDTH, CanvasDefaults.HEIGHT).also {
        // Transparent mode: unpainted area is alpha-0 (checkerboard shows through).
        // In opaque mode it would instead be filled with BACKGROUND (impl doc §3.7).
        it.fill(if (CanvasDefaults.TRANSPARENT_MODE) Colors.TRANSPARENT else CanvasDefaults.BACKGROUND)
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
    private val undoStack = ArrayDeque<IntArray>()
    private val redoStack = ArrayDeque<IntArray>()
    private var pendingUndo: IntArray? = null
    private var gestureTool: Tool? = null    // captured at press so it's stable across the gesture

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun bump() { version++ }

    /** Gesture dispatch: snapshot-before, mutate, push-once (§15.3). */
    fun onPress(e: ToolEvent) {
        val tool = toolInstance()
        gestureTool = tool
        // Non-mutating tools (eyedropper) take no undo snapshot (§15.3).
        pendingUndo = if (tool.mutates) canvas.copyOfPixels() else null
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
        pendingUndo?.let { snapshot ->
            if (!snapshot.contentEquals(canvas.pixels)) {
                undoStack.addLast(snapshot)
                if (undoStack.size > CanvasDefaults.HISTORY) undoStack.removeFirst()
                redoStack.clear()
            }
        }
        pendingUndo = null
        gestureTool = null
        if (tool?.mutates == true) bump()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(canvas.copyOfPixels())
        canvas.pixels = prev
        bump()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(canvas.copyOfPixels())
        canvas.pixels = next
        bump()
    }
}
