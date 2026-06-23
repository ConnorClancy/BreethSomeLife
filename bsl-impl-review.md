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

**5. Checkerboard is drawn unconditionally.** `CanvasView.kt:132-134` always fills the checkerboard, ignoring `app.transparent`. It's currently invisible because the canvas starts fully transparent and opaque pixels cover it, but spec §2.3 says the checkerboard is the transparent-mode indicator only. Once the transparency toggle (step 5) or opaque PNG import lands, an opaque canvas with any `alpha==0` pixels would wrongly show checker. Fix: gate the checker pass on `app.transparent` (and read it in the draw scope so it's reactive).

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

**14. `pointerInput(app)` captures `w`/`h` once** (`CanvasView.kt:58-59`); since `app` is stable the input coroutine won't restart, so a future canvas resize would leave stale dimensions for hit-testing. Read `app.canvas.width/height` inside the loop, or key the modifier on the dimensions, before step 5 lands.

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
