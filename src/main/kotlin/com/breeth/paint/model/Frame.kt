package com.breeth.paint.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One frame tab (spec §8.1): an independent canvas with its own pixel buffer,
 * transparency setting, background color, and undo/redo history. [label] is
 * "base" or "1".."9".
 *
 * Background model (see AppState): the buffer is always the foreground-with-alpha;
 * [transparent] only chooses how it's shown/exported, never mutates pixels.
 */
class Frame(
    label: String,
    val canvas: PixelCanvas,
    transparent: Boolean,
    backgroundColor: Int,
) {
    var label: String by mutableStateOf(label)
    var transparent: Boolean by mutableStateOf(transparent)
    var backgroundColor: Int by mutableStateOf(backgroundColor)

    /** Bumped after every buffer mutation so the view re-uploads & redraws (§3.2). */
    var version: Int by mutableStateOf(0)
        private set

    // --- Undo / redo (per frame, impl doc §3.4, §15.3; spec §8.1) ----------

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

    var canUndo: Boolean by mutableStateOf(false)
        private set
    var canRedo: Boolean by mutableStateOf(false)
        private set

    fun bump() { version++ }

    private fun syncHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

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
        if (undoStack.size > HISTORY) undoStack.removeFirst()
        redoStack.clear()
        syncHistoryFlags()
    }

    /** Gesture boundary (§15.3): snapshot on press for mutating tools. */
    fun beginGesture(mutates: Boolean) {
        pendingUndo = if (mutates) snapshot() else null
    }

    /** Push the gesture's undo entry only if pixels actually changed (review #1). */
    fun commitGesture() {
        pendingUndo?.let { before ->
            if (!before.pixels.contentEquals(canvas.pixels)) pushUndo(before)
        }
        pendingUndo = null
    }

    /** Run a whole-buffer op (clear/resize/toggle) as a single undo step. */
    private fun edit(block: () -> Unit) {
        val before = snapshot()
        block()
        pushUndo(before)
        bump()
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

    /** Clear the foreground to empty; the background layer shows behind it. */
    fun clearCanvas() = edit { canvas.fill(Colors.TRANSPARENT) }

    /** Resize/crop, anchored top-left; new area is empty foreground. */
    fun resizeTo(newW: Int, newH: Int) = edit { canvas.resizeTo(newW, newH, Colors.TRANSPARENT) }

    /** Toggle the background on/off (non-destructive); pixels untouched. */
    fun toggleTransparency() = edit { transparent = !transparent }

    companion object {
        const val HISTORY = 20   // spec §6: minimum 20 undo steps
    }
}
