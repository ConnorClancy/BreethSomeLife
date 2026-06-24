# BreethPaint

A sprite-focused raster paint editor built in Kotlin + Compose Multiplatform
(Desktop / JVM). See [`paint-spec.md`](./paint-spec.md) for product behavior and
[`paint-impl.md`](./paint-impl.md) for the implementation design.

## Status

Build-order steps **1–4** of `paint-impl.md` §12 are implemented:

1. Gradle skeleton + runnable Compose window.
2. `PixelCanvas` + compositing (`srcOver`) + `Viewport` (zoom/pan) + bitmap
   bridge + `CanvasView` (transparency checkerboard + canvas).
3. Tool plumbing (`ToolEvent` with button + modifiers, one-gesture undo),
   **pencil / brush (AA) / eraser** with stroke interpolation, and a color
   picker.
4. **Fill bucket** (4-connected, tolerance), **eyedropper**, **line**, and
   **rectangle / ellipse** shapes (outline / filled / both) with live preview;
   undo/redo.
5. **Canvas ops** — New, Clear, Resize/crop, and the Transparency toggle
   (§3.6–3.8), exposed via the window menu bar.

Not yet implemented (later steps): frame tabs, onion-skin, file I/O, and
selection/clipboard.

## Running (development)

```
./gradlew run
```

- **JDK:** the build targets a **JDK 21 toolchain**. If JDK 21 isn't installed,
  Gradle auto-provisions it via the `foojay-resolver` plugin on first build
  (one-time download). Gradle itself runs on the JDK on your `PATH`.

## Controls

- **Tools:** Pencil, Brush, Eraser, Fill, Eyedropper, Line, Rectangle, Ellipse.
  Pencil = hard edges; Brush = variable size with optional anti-aliasing (AA
  checkbox); Eraser punches transparency (or paints the background color in
  opaque mode). Fill flood-fills within the **Tolerance** slider; Eyedropper
  picks a pixel's color. Line/shapes drag with a live preview; shapes honor the
  **Shape fill** mode (outline = primary, fill = secondary). Hold **Shift** to
  constrain lines to 0°/45°/90° and shapes to a square/circle. Line/shape edges
  are always hard (no anti-aliasing) for crisp pixel art — the **AA** checkbox
  only affects the Brush.
- **Colors:** left mouse = primary, right mouse = secondary. Click a palette
  swatch (left = primary, right = secondary) or type a hex value.
- **Brush size:** slider (1–32 px).
- **Zoom:** toolbar `−` / `+` / `100%`, or `Ctrl` + mouse wheel. Stops range
  25%–1000% (integer stops are crisp nearest-neighbor per spec §2.2).
- **Pan** (when zoomed past the viewport): mouse wheel = vertical,
  `Shift` + wheel = horizontal.
- **Undo / Redo:** toolbar buttons, or `Ctrl+Z` / `Ctrl+Shift+Z`.
- **Menu bar:** **File ▸ New** (`Ctrl+N`); **Edit ▸ Undo/Redo/Clear Canvas**;
  **Image ▸ Resize…** and **Transparent Background** (toggle). Resize is
  anchored top-left. The background is a non-destructive display/export layer:
  the pixel buffer always holds the foreground (with alpha), and toggling
  Transparent Background just switches what renders behind it (checkerboard vs.
  the solid background color) without altering pixels.

## Building the Windows installer (MSI)

> Not required for `./gradlew run`. These are prerequisites only for packaging.

```
./gradlew packageMsi          # build/compose/binaries/main/msi/BreethPaint-<version>.msi
./gradlew packageReleaseMsi   # optimized variant
```

1. **Build on Windows** — `jpackage` emits the host OS's installer format.
2. **WiX Toolset v3** must be installed and on `PATH`; jpackage's MSI backend
   shells out to it. Without WiX, `packageMsi` fails.
3. **`upgradeUuid`** (in `build.gradle.kts`) is a fixed GUID — never change it,
   or future MSI versions install side-by-side instead of upgrading in place.
   `packageVersion` must be `MAJOR.MINOR.PATCH` with `MAJOR >= 1`.
