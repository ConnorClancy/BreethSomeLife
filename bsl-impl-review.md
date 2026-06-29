# BreethPaint — Implementation Review (steps 1–4)

Review of the implemented code against both the spec (`paint-spec.md`) and the
implementation design (`paint-impl.md`). The code is clean, well-documented, and
faithful to the design. Findings below are action items, ordered by impact. Each
notes the location and a suggested fix.

## Correctness bugs

**1. No-op gestures create phantom undo entries.** `AppState.onRelease` (`AppState.kt:110`) always pushes `pendingUndo` whenever the tool `mutates`, regardless of whether any pixel actually changed. Pressing the pencil in the empty backdrop/padding (maps out of bounds → all `stampDisc` writes are clipped away), a fill no-op (`floodFill` returns early when `target == replacement`, `ShapeRaster.kt:90`), or drawing with a fully-transparent color all push a snapshot identical to the current buffer. Result: Undo becomes enabled but "does nothing" visibly, and burns a history slot. Fix: have tools report whether they mutated (or compare `pendingUndo` to the buffer / track a dirty flag) and only push when something changed. **ADDRESSED - ADDED** — `AppState.onRelease` now pushes undo only when `!snapshot.contentEquals(canvas.pixels)`.

**2. Hex field never re-syncs with `app.primary`.** `HexField` initializes its text once with `remember { mutableStateOf(toHex(app.primary)) }` (`ColorPicker.kt:121`) and only writes *to* `app.primary`. When the eyedropper or a palette swatch changes `primary`, the hex box shows a stale value. Fix: drive the field from state, e.g. `LaunchedEffect(app.primary) { text = toHex(app.primary) }`, or make the displayed value derive from `app.primary` while keeping a local edit buffer. **ADDRESSED - ADDED** — `HexField` now has a `LaunchedEffect(app.primary)` that re-syncs the text (skipping when it already represents the same value, ignoring case).

**3. AA brush builds up / darkens along a stroke.** The brush stamps an overlapping disc at every interpolated pixel via `blend` (`Brush.kt:12` → `stampDisc` `Interpolation.kt:83-86`). Adjacent stamps overlap heavily, so AA edge pixels (partial coverage) get composited multiple times within a single drag and accumulate toward opaque — producing uneven, darker edges. It's far worse for any color with alpha < 255 (e.g. a color picked off an existing AA edge). Fix: accumulate a per-pixel coverage mask for the whole stroke (max coverage per pixel) and composite once on release, rather than blending each stamp independently.

**4. `Ctrl`+wheel zoom is not cursor-anchored.** The scroll handler just calls `zoomIn()/zoomOut()` (`CanvasView.kt:76-77`), which only change `scale`; the image stays centered. §15.1 explicitly requires keeping the pixel under the cursor fixed by adjusting `pan/userScroll` on zoom. Fix: after changing `scale`, adjust `userScroll` so the canvas pixel under the cursor maps back to the same screen point. **ADDRESSED - ADDED** — new `Viewport.zoomAround(cursor, …)` solves for the scroll that keeps the pixel under the cursor fixed; the `Ctrl`+wheel branch in `CanvasView` now calls it with `change.position`.

**5. Checkerboard is drawn unconditionally.** `CanvasView.kt:132-134` always fills the checkerboard, ignoring `app.transparent`. It's currently invisible because the canvas starts fully transparent and opaque pixels cover it, but spec §2.3 says the checkerboard is the transparent-mode indicator only. Once the transparency toggle (step 5) or opaque PNG import lands, an opaque canvas with any `alpha==0` pixels would wrongly show checker. Fix: gate the checker pass on `app.transparent` (and read it in the draw scope so it's reactive). **ADDRESSED - ADDED** (step 5) — the checkerboard pass is now wrapped in `if (app.transparent)` inside the draw scope.

## Efficiency

**6. A brand-new `ImageBitmap` is built on every `version` bump.** `remember(app.version)` (`CanvasView.kt:51`) calls `toImageBitmap()` which allocates a `BufferedImage`, does a full `setRGB` of `w*h`, then `toComposeImageBitmap()` (another full copy/upload) — on *every* mouse-move during a stroke. At 800×600 it's tolerable; it will get janky toward the 4096² ceiling and creates steady GC pressure. This is the documented "simple full re-upload," but §15.4's dirty-rect upload (reuse one long-lived bitmap, refresh only the changed sub-rect) is the intended optimization and is worth doing before larger canvases land. **ADDRESSED - ADDED** — added `CanvasImageCache`, which reuses one long-lived `BufferedImage` across frames (reallocated only on size change), eliminating the per-move `w*h` buffer allocation/GC churn. (The final Compose upload still occurs each frame; the dirty-rect GPU sub-upload remains future work.)

**7. `PreviewTool` does O(canvas) work per mouse-move, and double-copies on press.** On press it takes a full `copyOfPixels()` for its preview backup (`PreviewTool.kt:29`) — *in addition* to the full copy `AppState.onPress` already took for undo (`AppState.kt:101`). Then every drag move does a full-buffer `System.arraycopy` restore plus a full re-rasterize (`PreviewTool.kt:36-37`). Fix: restore only the previous preview's bounding (dirty) rect, and/or share the undo snapshot as the preview backup instead of copying twice.

**8. Flood fill is autoboxed and over-pushes.** `floodFill` uses `ArrayDeque<Int>` (`ShapeRaster.kt:93`), boxing every coordinate, and pushes each neighbor (up to 4× per pixel) plus an O(w·h) `visited` mask — for a 4096² fill that's millions of boxed `Integer`s. It's also not "scanline" as the comment claims (`ShapeRaster.kt:79`), just plain 4-connected. Fix: use an `IntArray`/`IntStack` or a true scanline fill (push spans, not pixels) to cut allocations and pushes dramatically. **ADDRESSED - ADDED** — `floodFill` rewritten as a real scanline fill on a primitive `IntStack` (no boxing), pushing one seed per contiguous run instead of per pixel; the `visited` mask is retained for tolerance correctness.

## Implementation misses & deviations

**9. Even brush sizes don't produce even diameters.** `radius = brushSize / 2f` (`FreehandTool.kt:21`) with a disc centered on a single pixel means size 2 → radius 1 → a 5-px plus shape, not a 2×2 block; size 4 ≈ radius 2 disc. Odd sizes are fine; even sizes feel wrong. Consider mapping size→diameter explicitly (and supporting an even-size offset center) if pixel-accurate sizes matter.

**10. Line/shape outlines hardcode `antialias = false`.** `LineTool.kt:17` and `ShapeTool.kt:26-28` always pass `antialias = false`, deviating from impl-doc §14.3 ("outline stroking respects `brushAntialias`"). The README documents this as an intentional pixel-art choice, so this is a flag-for-confirmation, not a defect — but the impl doc should be reconciled with the README so they don't disagree.

**11. Ellipse outline can have gaps / boundary mismatch.** The outline is parametric disc-stamping with `steps = ceil(PI*(rx+ry))` (`ShapeRaster.kt:68-76`), while the fill uses the implicit `nx²+ny² ≤ 1` test (`ShapeRaster.kt:64`). At thin radii the two boundaries can disagree by a pixel, and sample spacing ~1px can leave occasional diagonal gaps. A midpoint/Bresenham ellipse would be gap-free and align fill+outline exactly.

**12. Fractional-zoom hit-testing vs. draw rounding.** Hit-testing floors `(screen − pan)/scale` (`Viewport.kt:30`) while drawing rounds `dstOffset/dstSize` (`CanvasView.kt:147-148`). At the non-integer stops (25/50/75%) these can disagree by a pixel, so the painted pixel may not be the one under the cursor; nearest-neighbor downscaling also aliases. Acceptable for now, but note it — for a pixel editor you may want to restrict painting to integer zoom or align the two transforms.

**13. Non-mutating tools still bump `version`.** An eyedropper click runs `onPress/onDrag/onRelease`, each calling `bump()` (`AppState.kt:103,108,119`), triggering 2–3 full image re-uploads of an unchanged buffer. Cheap fix: skip `bump()` when `!gestureTool.mutates`. **ADDRESSED - ADDED** — `onPress/onDrag/onRelease` now call `bump()` only when the gesture tool `mutates`, so eyedropper clicks no longer re-upload the unchanged buffer.

## Minor / polish

**14. `pointerInput(app)` captures `w`/`h` once** (`CanvasView.kt:58-59`); since `app` is stable the input coroutine won't restart, so a future canvas resize would leave stale dimensions for hit-testing. Read `app.canvas.width/height` inside the loop, or key the modifier on the dimensions, before step 5 lands. **ADDRESSED - ADDED** (step 5) — the pointer loop now reads `app.canvas.width/height` on each event, so hit-testing follows a resize.

**15. Stale doc comments.** `Tool.kt:41` references `AppState.beginStroke/endStroke` which don't exist (the methods are `onPress/onDrag/onRelease`), and `floodFill`'s "scanline" comment (`ShapeRaster.kt:79`) is inaccurate. Quick cleanups.

**16. Transparent palette swatch is confusing.** `Colors.TRANSPARENT` in the palette (`ColorPicker.kt:69`) renders invisibly, and selecting it as the primary makes the pencil (`set`) effectively erase. Consider drawing a checker/"none" indicator for it, or dropping it.

**17. Default `brushSize = 3` applies to the Pencil too** (`AppState.kt:57`), so the pencil starts at 3px even though spec §4.1 frames it as single-pixel by default. Consider per-tool sizes or a 1px pencil default.

**18. Dependency pins are behind.** Kotlin `2.1.0` / Compose `1.7.3` (`libs.versions.toml`) — the impl doc (§13) said to pin the latest stable at scaffold time. Worth verifying/bumping to a current known-good pair.

Lower-impact notes: middle-click is treated as primary (`isSecondaryPressed` false, `CanvasView.kt:91`), and only `change.position` is used while coalesced `historical` samples are dropped (`CanvasView.kt:102`), so very fast strokes are slightly angular — neither is a real defect.

## Summary

The architecture (single source-of-truth pixel buffer, viewport transform,
snapshot-restore previews, one-gesture undo) is sound and matches the design doc
well. The highest-value fixes are **#1 (phantom undo)**, **#2 (hex desync)**, and
**#3 (AA brush buildup)** for correctness, and **#6–#8** for performance before
larger canvases arrive.

---

# PR #1 review — Steps 6 & 7 (frame tabs + onion-skin overlay)

Reviewed `origin/pr-1` (`ec125a2`) against its base (`952e89a`, step 5). New
`Frame.kt`, `ui/TabBar.kt`; reworked `AppState.kt` (frames + `activeFrameIndex`,
delegating accessors, per-frame undo); `Main.kt` (number-key / Shift+O handling,
Edit + Frame menus), `CanvasView.kt` (onion draw), `BitmapBridge.kt`,
`ColorPicker.kt` (focus reporting), `ResizeDialog.kt` (resize warning).

Overall the frame model is clean: independent `Frame` (own pixels, transparency,
background, undo/redo), contiguous sorted `frames` list, correct nearest-lower
snapshot copy (§8.2), size lock (§8.4), delete + renumber (§8.5), and a
snapshot-at-toggle onion overlay cleared on every frame switch (§8.3). The
review-#1/#6/#13 fixes carried over correctly. Findings below use a separate
`PR-n` numbering. (Static review only — not compiled/run; see PR-10.)

## Correctness bugs

**PR-1. Propagated resize + per-frame undo diverges frame sizes (breaks the size-lock invariant and onion alignment).** `resizeCanvas` applies the resize to every frame as *independent* operations — `for (f in frames) f.resizeTo(w, h)` (`AppState.kt:162`), and each `Frame.resizeTo` is its own `edit { … }` that pushes a separate undo entry onto *that frame's* stack (`Frame.kt:116`). But `app.undo()` only undoes the **active** frame (`AppState.kt` → `active.undo()`). So after resizing `base` from 800×600 to 400×400 (which correctly resizes all frames), pressing Ctrl+Z reverts only the active frame to 800×600 while the others stay 400×400 — frames now have **different sizes**, violating §8.4's size-lock and de-aligning the onion overlay (which assumes equal dimensions). `lockedW/lockedH` (`AppState.kt:163`) also goes stale relative to the now-divergent frames, so the next created frame inherits a wrong size. Fix: make a propagated resize one atomic, app-level undo step (snapshot every frame's size/pixels together and revert them together), or store the pre-resize sizes so undo restores all frames at once. **ACCEPTED - ADDED** — `resizeCanvas` now snapshots every frame's pre-resize buffer into an app-level `ResizeStep` (with the prior `lockedW/H`); `undo()/redo()` fall through to `undoResize/redoResize`, which revert/replay all frames together and restore the lock. `applyResize` also clears every frame's per-frame history at the resize barrier (`Frame.clearHistory`), so a stale pre-resize snapshot can no longer restore a mismatched size; creating/deleting a frame or starting a new doc clears the resize history (its snapshots reference that frame set).

**PR-2. Surprising undo target on numbered frames after a resize.** Same root cause as PR-1, but worth calling out as its own UX defect: because the propagated resize pushes a resize entry onto *each* frame's undo stack, a user who draws on frame `2`, switches to `base`, resizes, then returns to frame `2` and presses Ctrl+Z will **undo the resize** (shrinking only frame `2`) instead of undoing their drawing — the resize silently became the top of frame 2's history. Folding resize into a single app-level undo (PR-1) fixes this too.

**PR-3. `resizeCanvas` no-op guard only inspects the active frame.** `if (w == active.canvas.width && h == active.canvas.height) return` (`AppState.kt:161`) compares only `base`. Under the normal invariant all frames share a size so this is fine, but once PR-1 lets sizes diverge, a resize back to base's current size early-returns without re-syncing the other (still-divergent) frames. Resolving PR-1 makes this moot; otherwise compare/repair all frames.

## Frame management & UX

**PR-4. Frame deletion is immediate, destructive, and not undoable.** Both the tab "✕" (`TabBar.kt`) and Frame ▸ Delete Current Frame (`Main.kt`) call `deleteFrame` with no confirmation, and there is no undo for it — a frame's entire pixel buffer and history are gone on a single (easily mis-clicked) tap next to the tab's activate target. Spec §8.5 doesn't require undo, but given the app's animation workflow this is real data-loss risk. Consider a confirm prompt (and/or making delete undoable).

**PR-5. Mid-gesture frame switch edits the hidden frame.** `gestureFrame` is captured at press (`AppState.kt`) and `onDrag`/`onRelease` keep mutating it. Pressing a number key during an active mouse drag switches `activeFrameIndex` (the view now shows the new frame) while the stroke continues writing to — and committing on — the *original* frame, whose `bump()`s aren't observed by the view. The in-progress stroke appears to vanish and lands on a now-hidden frame. Edge case (needs keyboard during drag); low severity. Could ignore number keys while a gesture is active, or commit the gesture first. **ACCEPTED - ADDED** (commit-the-gesture-first variant) — new `AppState.commitActiveGesture()` pushes the in-progress stroke's undo entry and ends the gesture; it is called from `activateFrame`/`gotoOrCreateFrame` (and `deleteFrame`/`newDocument`) before the active frame changes, so the stroke commits on the frame it started on and the live drag becomes a no-op until the next press. This also covers switching via a tab click, not just number keys.

**PR-6. `textFieldFocused` is a single shared flag set by only one field.** The hex field reports focus (`ColorPicker.kt`, `onFocusChanged`) to suppress frame-key navigation (`Main.kt:48`). It works today, but it's fragile: any future focusable text input must remember to toggle the same flag or number keys will hijack typing, and if a focused field ever leaves composition without firing `onFocusChanged(false)` the flag sticks and frame keys stay dead. Prefer deriving suppression from actual focus state, or at least centralize the contract.

## Efficiency

**PR-7. Onion overlay forgoes the visible-sub-rectangle optimization the main bitmap uses.** The main canvas pass deliberately uploads/draws only the visible source sub-rect to bound cost by the viewport (CanvasView, step-2 design). The onion pass instead draws the *entire* snapshot scaled to `onion.width * scale` (`CanvasView.kt:179`) and relies on `clipRect` to trim it. At high zoom on a large frame that hands Skia a very large destination rect to clip every frame the overlay is on. Mirror the main pass's `srcOffset`/`srcSize` sub-rect math for the onion draw so both scale the same way. **ACCEPTED - ADDED** — the onion pass now reuses the main pass's visible `srcL/srcT/srcR/srcB` sub-rect (coerced to the onion's dimensions) and draws only that sub-rectangle with matching `dstOffset`/`dstSize`, so its cost is bounded by the viewport like the main bitmap; the whole-snapshot scale + `clipRect` was removed.

## Minor / polish

**PR-8. `Ctrl+Y` redo accelerator dropped.** Redo is now only `Ctrl+Shift+Z` (`Main.kt:68`); the step-1–4 window handler also accepted `Ctrl+Y`. Minor regression for users who expect `Ctrl+Y`; add it back as a second accelerator if desired. **ACCEPTED - ADDED** — the window `onKeyEvent` now handles `Ctrl+Y` (no Alt/Shift) as a second Redo accelerator that calls `app.redo()`, alongside the menu's `Ctrl+Shift+Z`.

**PR-9. `Shift`+digit also navigates frames.** `digitKey` ignores modifiers (`Main.kt:50`), so `Shift+1`…`Shift+9` switch/create frames just like the bare digits. Harmless but undocumented; fine to leave, worth a note.

**PR-10. `TabBar` hardcodes the `"base"` string** (`TabBar.kt:41`) rather than sharing the `BASE` constant (currently `private` in both `AppState` and effectively duplicated). Promote one shared constant to avoid drift if the base label ever changes.

**PR-11. Not built.** This is a static read of the diff; I did not run `./gradlew run`/`build`. The code is internally consistent (imports, symbols, types line up), but a compile + a quick manual pass (create frames 1→3, delete `2`, toggle onion, resize base with frames present, then undo on each frame) would confirm PR-1/PR-2 behavior in particular.

## PR summary

Solid, spec-faithful implementation of frame tabs and onion skin; the per-frame
state split is the right shape. The one issue worth fixing before merge is
**PR-1/PR-2** — propagated resize must be a single atomic undo across all frames,
or per-frame undo will silently break the equal-size invariant the onion overlay
and §8.4 depend on. **PR-4** (unconfirmed, irreversible frame delete) is the next
most important given the animation workflow. The rest are minor.
