# Paint — Implementation Document

> **Status:** In progress. This is the code-level source of truth. The product/behavior source of truth is [`paint-spec.md`](./paint-spec.md). When the two disagree, the spec wins for *what* the app does; this doc wins for *how* it's built.

---

## 1. Goals & Constraints

- **Target platform:** Windows desktop application.
- **Language:** Kotlin.
- **UI framework:** Compose Multiplatform (a learning goal for this project — see §2).
- **Build / dependencies:** Gradle (Kotlin DSL, `build.gradle.kts`).
- **Scope:** Implements `paint-spec.md` — single flat-bitmap canvas, basic tool set, transparency, and the sprite frame workflow (tabs, onion-skin overlay, save-all).

---

## 2. Technology Choices & Feasibility

### 2.1 Can this be done in Compose Multiplatform? — Yes

Compose Multiplatform (CMP) has a first-class **Desktop target that runs on the JVM**, and the JVM runs natively on Windows. So a Windows-native-feeling app written entirely in Kotlin + Compose is fully supported. Key facts that matter for this project:

- CMP Desktop renders through **Skia** (via the **Skiko** binding). Skia gives us a fast 2D raster surface, which is exactly what a Paint-style app needs.
- The same Skia layer exposes **`org.jetbrains.skia.Bitmap` / `Image`**, and Compose provides **`ImageBitmap`** plus `DrawScope.drawImage(...)`. This is the bridge we use to display our editable pixel buffer (see §3).
- Desktop CMP supports the **window/menu/file-dialog** primitives we need (`Window`, `MenuBar`, `AwtWindow` for native file choosers, or `java.awt.FileDialog`).
- CMP can **package a native Windows installer** (MSI/EXE) with a bundled JRE via the Compose Gradle plugin's `nativeDistributions` (which wraps `jpackage`).

### 2.2 Stack summary

| Concern | Choice |
|---|---|
| Language | Kotlin (latest stable) |
| UI | Compose Multiplatform — Desktop target |
| 2D raster | Skia / Skiko (comes with CMP), via `ImageBitmap` |
| Build | Gradle, Kotlin DSL |
| Async/state | Kotlin coroutines + Compose state (`mutableStateOf`, `State`, `snapshotFlow`) |
| File I/O | `javax.imageio.ImageIO` (PNG read/write) + native file dialogs |

> **Note on "Multiplatform" vs "Desktop":** even though we only ship Windows, CMP can still be set up either as a *full Kotlin Multiplatform project* (a `commonMain` source set + a `jvm`/desktop target) or as a *Desktop-only JVM module*. This is one of the open questions in §6 because it changes the whole module/build layout — and the multiplatform layout is more instructive if learning CMP is a goal.

---

## 3. Canvas Data Model & Rendering (core decision)

This is the heart of the app, so it's settled up front; the spec's tools and transparency all build on it.

### 3.1 Pixel buffer is the source of truth

Each frame's canvas is backed by a **mutable pixel buffer**, not by retained draw commands:

- Store pixels as an **`IntArray` of size `width * height`**, one packed **ARGB** `Int` per pixel (`0xAARRGGBB`).
- This gives O(1) random pixel access, which the spec's **flood fill (§4.4)**, **eyedropper (§4.5)**, and **transparent eraser (§4.3)** all require — operations that are awkward or impossible with a retained draw-command model.

```kotlin
class PixelCanvas(
    var width: Int,
    var height: Int,
    var pixels: IntArray = IntArray(width * height) { TRANSPARENT },
) {
    // ARGB, NON-premultiplied. index = y * width + x. TRANSPARENT = 0x00000000.

    fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Hard write — replaces the pixel outright (pencil, eraser-to-transparent, fill). */
    fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }

    /** Alpha-composite `src` OVER the existing pixel (soft brush edges, paste, move). See §3.5. */
    fun blend(x: Int, y: Int, src: Int) { pixels[y * width + x] = srcOver(src, pixels[y * width + x]) }

    fun fill(argb: Int) { pixels.fill(argb) }
    // bounds-checked variants clip to [0,width) x [0,height) per spec §2.2
}
```

> `width/height/pixels` are `var` because **resize (§3.6)** reallocates the buffer in place. A `Frame` (see §4) pairs a `PixelCanvas` with its `backgroundColor` and `transparent` flag — those live on the frame, not the raw pixel buffer.

### 3.2 Display: buffer → ImageBitmap → Compose Canvas

- Wrap the `IntArray` into a Skia `Bitmap`/`Image` and expose it as a Compose **`ImageBitmap`**.
- A `Canvas(modifier)` composable's `DrawScope` draws that `ImageBitmap` scaled by the current zoom (`drawImage` with integer nearest-neighbor scaling so pixels stay crisp at >100% zoom, per spec §2.2).
- After a tool mutates the buffer, mark the bitmap dirty and recompose. We keep a `version: Int` state that tools bump; the composable reads it so Compose knows to re-upload and redraw.
- The **transparency checkerboard (spec §2.3)** is drawn *underneath* the ImageBitmap in the `DrawScope` as a display-only layer — never written into `pixels`.

### 3.3 Tools mutate the buffer

- Each tool is a small strategy that, given a **`ToolEvent`** (mapped pixel + button + modifiers, §15.2) and the current `AppState`, mutates the active `PixelCanvas`.
- Pointer screen coordinates are mapped to pixel coordinates through the **viewport transform** (zoom + pan) before reaching a tool — see §15.1. Tools never see screen pixels.
- Freehand tools (pencil/brush/eraser) interpolate a line between successive pointer samples (spec §4.1) using Bresenham/DDA so fast strokes have no gaps.
- Shape/line/selection tools render a **live preview** in an overlay `DrawScope` pass and only commit into `pixels` on pointer-release (spec §4.6–4.7).
- A whole gesture is **one undo entry** — snapshot on press, commit on release (§15.3).

### 3.4 Undo/redo

- Spec §6 requires ≥20 steps. Start simple: **snapshot the `IntArray`** onto an undo stack on each committed mutation (copy-on-commit).
- For an 800×600 canvas a snapshot is ~1.9 MB; 20 steps ≈ 38 MB — acceptable. (A later optimization could store dirty-rect diffs; noted, not built yet.)
- Resize, new, clear, transparency-toggle, paste, cut, and move are all **single undo steps** (snapshot before the operation).

### 3.5 Compositing model (the rule everything else references)

Several features write *partial-alpha* color and must agree on how it combines with what's already there. We use standard **Porter-Duff "source-over"** on non-premultiplied ARGB:

```
outA   = srcA + dstA * (1 - srcA)
outRGB = (srcRGB*srcA + dstRGB*dstA*(1 - srcA)) / outA      // outA != 0
```

(`srcOver(src, dst)` in §3.1 implements this with 0–255 integer math.)

Who uses which write path:

| Operation | Write path | Why |
|---|---|---|
| Pencil, Fill, Line, Shape outline (hard) | `set` (replace) | Hard-edged, full-alpha color per spec §4.1/§4.4/§4.6. |
| **Brush soft/anti-aliased edges (spec §4.2)** | `blend` | Edge pixels get fractional **coverage** → `srcA = color.a * coverage`, composited over the canvas. Interior is full coverage. Pencil keeps coverage hard (threshold at 0.5, no AA). |
| Eraser — opaque canvas | `set` to `backgroundColor` | spec §4.3. |
| Eraser — transparent canvas | `set` to `TRANSPARENT` (hard), or reduce `dstA *= (1-coverage)` for a soft eraser | "destination-out"; punches alpha holes per spec §4.3. |
| Paste / Move drop | `blend` | Floating region composited over the canvas (§14). |
| Onion-skin overlay (spec §8.3) | **none** — display only | Drawn in the `DrawScope` at 0.30 alpha, never written to any buffer. |

> Brush coverage is computed from each pixel's distance to the interpolated stroke centerline vs. the brush radius (1 inside, 0 outside, a 1px linear ramp between when AA is on). `brushAntialias` is an `AppState` option (§4 / §14.4).

### 3.6 Resize / crop (spec §2.2, §2.4, §8.4)

Reallocate and copy the overlap, anchored at the **top-left** origin:

```kotlin
fun PixelCanvas.resizeTo(newW: Int, newH: Int, fill: Int) {
    val dst = IntArray(newW * newH) { fill }              // new area = fill color
    val copyW = minOf(width, newW)                         // overlap region
    val copyH = minOf(height, newH)
    for (y in 0 until copyH) {
        System.arraycopy(pixels, y * width, dst, y * newW, copyW)  // crop right/bottom for free
    }
    pixels = dst; width = newW; height = newH
}
```

- **`fill`** = the frame's `backgroundColor` if the frame is opaque, else `TRANSPARENT` (per spec §2.2 "new area filled with the background color"; transparent frames extend with transparency).
- Growing adds blank right/bottom area; shrinking discards the cropped right/bottom pixels. Existing content never moves.
- **Frame size-lock interaction (spec §8.4):** resizing `base` calls this on `base` only; the locked numbered-frame size is independent, so resizing `base` afterward does not touch frames `1`–`9`. Numbered frames are all created at — and stay at — the locked size.

### 3.7 New & Clear (spec §2.4) + base initialization

Default fresh-canvas constants (spec §2.1 defaults):

```kotlin
object CanvasDefaults {
    const val WIDTH = 800
    const val HEIGHT = 600
    const val BACKGROUND = 0xFFFFFFFF.toInt()  // opaque white
    const val TRANSPARENT_MODE = false          // spec §2.1: transparency Off by default
}
```

- **New** (`FrameManager.newDocument()`): discard all tabs, create a single `base` `Frame` at `WIDTH×HEIGHT`, `backgroundColor = BACKGROUND`, `transparent = false`; its pixel buffer filled with `BACKGROUND` (opaque) — because in opaque mode unpainted area *is* the background color. Reset zoom to 100% and clear all undo/redo and the locked frame size.
- **Clear** (current frame): `pixels.fill(if (transparent) TRANSPARENT else backgroundColor)`, as one undo step (spec §2.4).
- **Import** seeds `base` from the PNG instead of the blank fill (spec §7); imported PNGs with alpha set `transparent = true`.

### 3.8 Transparency toggle (spec §2.3)

`transparent: Boolean` and `backgroundColor: Int` both live on `Frame`. Toggling is a single undo step:

- **OFF → ON:** change only *rendering* (checkerboard instead of background) and *eraser mode*. **Pixels are not altered** (spec §2.3: "does not alter existing painted pixels; only changes how unpainted area is treated"). Note: in opaque mode unpainted area was already filled with `backgroundColor`, so those pixels simply stay opaque; only genuinely-transparent pixels (`a==0`) begin showing the checkerboard.
- **ON → OFF:** flatten transparency by compositing the whole buffer over the opaque background:
  `for each pixel: pixels[i] = srcOver(pixels[i], backgroundColor)`.
  This sets every `a==0` pixel to `backgroundColor` (the spec's literal requirement) **and** correctly resolves partial-alpha edge pixels (a superset of the spec rule — see note). The result is fully opaque.

> **Spec clarification flagged:** §2.3 only mentions `a==0` pixels when toggling off. Our implementation also flattens *partial*-alpha pixels by compositing over the background, since leaving semi-transparent edges in a now-"opaque" canvas would be inconsistent. If you'd rather hard-snap (only touch `a==0`, leave partials), say so — it's a one-line change.

---

## 4. Application Architecture (high level)

A unidirectional, state-driven structure that fits Compose:

```
                 ┌──────────────────────────────────────┐
                 │            Compose UI (View)          │
                 │  Window, MenuBar, ToolBar, TabBar,    │
                 │  ColorPicker, CanvasView (DrawScope)  │
                 └──────────────┬───────────────────────┘
                       events   │   reads state
                                ▼
                 ┌──────────────────────────────────────┐
                 │        AppState / "Store"             │
                 │  - frames: List<Frame> (base, 1..9)   │
                 │  - activeFrameIndex                   │
                 │  - activeTool, primary/secondary,     │
                 │    brushSize, brushAntialias,         │
                 │    overlayOn                          │
                 │  - viewport: Viewport (zoom+pan, §15.1)│
                 │  - fillMode, fillTolerance            │
                 │  - selection: Selection?              │
                 │  - floating: FloatingRegion?          │
                 │  - clipboard: Clipboard?              │
                 └──────────────┬───────────────────────┘
                                ▼
   ┌───────────┬──────────────┬──────────────┬───────────────┐
   │  Tools    │ FrameManager │ FileService  │ ClipboardSvc  │
   │ (mutate   │ (create/copy/│ (PNG import, │ (cut/copy/    │
   │ PixelCanvas)│ delete/renum)│  save-all)  │ paste/move)   │
   └───────────┴──────────────┴──────────────┴───────────────┘
```

- **`Frame`** = `{ label: String /* "base" | "1".."9" */, canvas: PixelCanvas, backgroundColor: Int, transparent: Boolean, undo/redo stacks }`.
  - `backgroundColor` (default opaque white) is what opaque-mode unpainted area is filled with, what resize/clear use as fill, and what the opaque eraser paints (§3.5–3.8).
- **Tool options on `AppState`** (spec §4.2, §4.4, §5): `brushSize`, `brushAntialias: Boolean` (soft vs. hard edges, §3.5), `fillMode: FillMode` (`OUTLINE | FILLED | BOTH`, for Line/Shape tools), `fillTolerance: Int` (0–255 flood-fill match threshold, §14.2).
- **`FrameManager`** implements spec §8.2 (number-key create/navigate with snapshot copy), §8.4 (size lock), §8.5 (delete + renumber).
- **Onion-skin overlay (spec §8.3):** when toggled, snapshot the previous frame's `ImageBitmap` and draw it in the CanvasView's `DrawScope` at `alpha = 0.30f` *above* the active bitmap. Pure display; never mutates the active buffer.
- **Keyboard:** a window-level key handler maps `1`–`9` → `FrameManager`, `Shift+O` → toggle overlay.

---

## 5. Frame Workflow Mapping (spec §8 → code)

| Spec | Implementation note |
|---|---|
| §8.1 tabs `base`,`1`–`9` | `AppState.frames`, always index 0 = `base`. |
| §8.2 number-key create/navigate | If frame missing: deep-copy `pixels` of nearest lower frame, append, activate. Else: activate. |
| §8.3 SHIFT+O overlay | Snapshot prev frame bitmap at toggle; draw at 30% alpha; read-only. |
| §8.4 size lock | First numbered frame fixes `(w,h)`; `base` exempt; new frames forced to that size. |
| §8.5 delete + renumber | Remove frame; relabel higher frames down by one; `base` not deletable. |
| §8.6 save-all | Prompt name + folder; write `{name}_{label}.png` per frame via `ImageIO`. |

---

## 6. Resolved Build Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Project structure | **Desktop-only JVM module** (`org.jetbrains.compose`) | Already runs on Windows/macOS/Linux from one codebase (Skiko ships native binaries for all three). No Android/iOS planned, so the full KMP `commonMain` layout would be unused ceremony. A future migration to KMP is a contained refactor if ever needed. |
| Cross-OS | Code is cross-desktop for free; **only Windows installer built now** | jpackage must run on each target OS to emit that OS's installer — defer macOS/Linux packaging to per-OS CI later. |
| JDK | **JDK 21 (LTS)** | Current LTS; well-supported by recent CMP + jpackage. |
| Distribution | **MSI installer** via Compose `nativeDistributions` (jpackage) | Goal is a fully-qualified installable app, not `gradle run` each time. `./gradlew run` still available for dev. |

---

## 7. Project Layout

Single Gradle module, desktop-only:

```
BreethSomeLife/
├── build.gradle.kts            # plugins, deps, compose desktop config
├── settings.gradle.kts         # project name, plugin/dependency repos
├── gradle.properties           # jvm args, kotlin/compose flags
├── gradle/
│   └── libs.versions.toml       # version catalog (Kotlin, Compose, etc.)
└── src/
    └── main/
        ├── kotlin/com/breeth/paint/
        │   ├── Main.kt                    # main() + application { Window { ... } }
        │   ├── app/
        │   │   ├── AppState.kt            # the Store (frames, tool, colors, viewport, overlay)
        │   │   ├── Viewport.kt            # zoom + pan; screen<->pixel mapping (§15.1)
        │   │   └── KeyHandler.kt          # 1–9, Shift+O window key mapping
        │   ├── model/
        │   │   ├── PixelCanvas.kt         # IntArray ARGB buffer + resize/fill (§3.1, §3.6)
        │   │   ├── Compositing.kt         # srcOver() + coverage helpers (§3.5)
        │   │   ├── Frame.kt               # label, canvas, backgroundColor, transparent, undo/redo
        │   │   ├── FrameManager.kt        # new/create/copy/delete/renumber (spec §8, §3.7)
        │   │   ├── Selection.kt           # marquee rect + marching-ants state (§14.1)
        │   │   ├── FloatingRegion.kt      # lifted/pasted pixels floating above canvas (§14.1)
        │   │   └── Clipboard.kt           # app-internal copied pixel block (§14.1)
        │   ├── render/
        │   │   ├── BitmapBridge.kt        # IntArray <-> ImageBitmap (§3.2)
        │   │   └── CanvasView.kt          # Canvas: checkerboard, image, overlay, preview, floating region
        │   ├── tools/
        │   │   ├── Tool.kt                # interface: onPress/onDrag/onRelease(canvas, ToolEvent) (§15.2)
        │   │   ├── Pencil.kt Brush.kt Eraser.kt
        │   │   ├── Fill.kt Eyedropper.kt
        │   │   ├── LineTool.kt ShapeTool.kt
        │   │   ├── SelectionTool.kt       # marquee + move (§14.1)
        │   │   └── stroke/Interpolation.kt # Bresenham/DDA line fill (§3.3)
        │   ├── ui/
        │   │   ├── ToolBar.kt TabBar.kt ColorPicker.kt MenuBarUi.kt
        │   └── io/
        │       ├── FileService.kt         # PNG import, save-all (spec §8.6)
        │       └── ClipboardService.kt    # cut/copy/paste/move orchestration (§14.1)
        └── resources/                     # icons, app metadata
```

---

## 8. Gradle Build

### 8.1 `settings.gradle.kts`

```kotlin
rootProject.name = "BreethPaint"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
```

### 8.2 `gradle/libs.versions.toml` (version catalog)

```toml
[versions]
kotlin = "2.1.0"          # verify latest stable at setup time
compose = "1.7.3"          # Compose Multiplatform plugin/runtime

[plugins]
kotlin-jvm       = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose   = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose          = { id = "org.jetbrains.compose", version.ref = "compose" }
```

> **Important (Kotlin 2.0+):** the Compose compiler is now a **separate Kotlin plugin** `org.jetbrains.kotlin.plugin.compose`, versioned with Kotlin (not with the Compose library). Both `org.jetbrains.compose` *and* `kotlin.plugin.compose` must be applied.

### 8.3 `build.gradle.kts`

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)   // required on Kotlin 2.0+
    alias(libs.plugins.compose)
}

group = "com.breeth.paint"
version = "0.1.0"

kotlin {
    jvmToolchain(21)                      // JDK 21 (LTS)
}

dependencies {
    implementation(compose.desktop.currentOs)   // Skiko native libs for the build host
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.components.resources)
    // coroutines come transitively via compose; add explicitly if needed:
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:<ver>")
}

compose.desktop {
    application {
        mainClass = "com.breeth.paint.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)      // Windows installer
            packageName = "BreethPaint"
            packageVersion = "1.0.0"             // jpackage requires MAJOR.MINOR.PATCH, MAJOR >= 1
            vendor = "Breeth"
            description = "Sprite-focused raster paint editor"

            windows {
                menuGroup = "BreethPaint"
                perUserInstall = true            // no admin rights needed
                // upgradeUuid must be a STABLE GUID across releases for clean upgrades:
                upgradeUuid = "<generate-once-and-keep-fixed>"
                shortcut = true                  // desktop shortcut
                // iconFile.set(project.file("src/main/resources/app.ico"))
            }
        }
    }
}
```

### 8.4 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2g
org.gradle.caching=true
kotlin.code.style=official
```

---

## 9. Dependencies (what each is for)

| Dependency | Purpose |
|---|---|
| `compose.desktop.currentOs` | Compose Desktop runtime + Skiko native libs for the **build host** OS. (Building the Windows MSI happens on Windows.) |
| `compose.material3` | Buttons, menus, dialogs, sliders for the toolbar/color picker UI. |
| `compose.ui` | Core Compose UI + `Canvas`/`DrawScope`, `ImageBitmap`, pointer & key input. |
| `compose.components.resources` | Bundled app resources (icons, etc.). |
| `javax.imageio` (JDK) | PNG decode (import) and encode (save-all). No extra dependency — part of the JDK. |

No third-party image library is needed: `ImageIO` handles PNG, and Skiko handles on-screen raster.

---

## 10. Packaging the MSI

- Build on **Windows** (jpackage emits the host OS's installer format).
- jpackage's MSI backend requires the **WiX Toolset v3** to be installed and on `PATH` — this is an external prerequisite, document it in the README.
- Commands:
  - `./gradlew run` — launch for development.
  - `./gradlew packageMsi` — produce `build/compose/binaries/main/msi/BreethPaint-<version>.msi` (bundles a trimmed JRE; end users need no Java install).
  - `./gradlew packageReleaseMsi` — optimized/release variant.
- **`upgradeUuid`** must be generated once and kept constant forever, so future MSI versions upgrade in place instead of installing side-by-side.
- **`packageVersion`** must be `MAJOR.MINOR.PATCH` with `MAJOR >= 1` (jpackage/MSI constraint) — note this differs from the Gradle `version` used for the project artifact.

---

## 11. Input & File I/O Notes

- **Keyboard (spec §8.2/§8.3):** attach `onKeyEvent` / `onPreviewKeyEvent` at the `Window` level. Map `Key.One`..`Key.Nine` → `FrameManager.gotoOrCreate(n)`; `Shift+Key.O` → `AppState.toggleOverlay()` (no-op on `base`).
- **File dialogs:** use `java.awt.FileDialog` (native Win32 dialog) wrapped in `AwtWindow`, or the Swing `JFileChooser`. Save-all prompts once for base name + folder, then loops frames writing `{name}_{label}.png`.
- **PNG I/O:** `ImageIO.read(file)` → fill a `PixelCanvas` from the `BufferedImage` (TYPE_INT_ARGB); `ImageIO.write(bufferedImage, "png", file)` for save, preserving alpha per spec §7.
- **Buffer ↔ BufferedImage:** `BufferedImage(TYPE_INT_ARGB).setRGB(...)` / `getRGB(...)` line up directly with our packed-ARGB `IntArray`, making conversion a bulk array copy.

---

## 12. Build Order (suggested first milestones)

1. Gradle skeleton + empty Compose `Window` that runs (`./gradlew run`) and packages (`./gradlew packageMsi`).
2. `PixelCanvas` + `Compositing` (`srcOver`) + `Viewport` (screen↔pixel mapping, zoom, pan, §15.1) + `BitmapBridge` + `CanvasView` showing a checkerboard and an empty canvas.
3. Tool event plumbing (`ToolEvent` button+modifiers §15.2, one-gesture-undo §15.3); pencil + brush (AA via §3.5) + eraser with stroke interpolation; color picker.
4. Fill (tolerance), eyedropper, line, shapes (fillMode); undo/redo.
5. Canvas ops: New, Clear, resize/crop, transparency toggle (§3.6–3.8) + menu actions.
6. Frame tabs: number-key create/navigate, size lock, delete/renumber.
7. Onion-skin overlay (Shift+O).
8. PNG import + save-all.
9. Selection / cut / copy / paste / move (§14.1).
10. Icon, installer polish, README with WiX prerequisite.

---

## 13. Setup Gotchas (read before first build)

Three non-obvious things that will bite during initial scaffolding:

1. **Kotlin 2.0+ Compose compiler is a separate plugin.** The Compose compiler moved out of the Compose library into the Kotlin-versioned plugin `org.jetbrains.kotlin.plugin.compose`. Both it **and** `org.jetbrains.compose` must be applied (see §8.3), or the build fails. This stays an internal build concern — no need to surface it to end users.

2. **MSI packaging requires the WiX Toolset v3.** jpackage's MSI backend shells out to WiX v3, which must be installed and on `PATH`. Without it, `./gradlew packageMsi` fails. → **Move to project `README.md`** as a build prerequisite when code implementation begins.

3. **`upgradeUuid` must be a fixed GUID.** Generate it once and never change it, or future MSI versions install side-by-side instead of upgrading in place. Also note `packageVersion` must be `MAJOR.MINOR.PATCH` with `MAJOR >= 1`. → **Move to project `README.md`** (release/build section) when code implementation begins.

> **README hand-off:** items **2** and **3** above are deployment/build-environment facts that belong in the eventual `README.md` at code-implementation time, not just this design doc. Item **1** is purely an internal build detail and can stay here.

### Version pinning — TODO at scaffold time

The catalog in §8.2 pins **Kotlin `2.1.0` / Compose `1.7.3`** as placeholders. These were current at design time and are likely behind by the time code starts — **look up and pin the latest stable Kotlin + Compose Multiplatform versions when the project is actually created.**

---

## 14. Detailed Design: Selection & Tool Options

### 14.1 Selection / Cut / Copy / Paste / Move (spec §6)

Three small model types plus one tool and one service, all built on the compositing rule (§3.5).

**Model:**

```kotlin
// A committed rectangular marquee on the active canvas.
data class Selection(val rect: IntRect)                       // marching-ants drawn in CanvasView

// Pixels lifted off the canvas (Move) or pasted in (Paste), floating until committed.
class FloatingRegion(
    val w: Int, val h: Int,
    val pixels: IntArray,                                     // ARGB block
    var offset: IntOffset,                                    // top-left position on the canvas
)

// App-internal clipboard (system clipboard interop can come later).
class Clipboard(val w: Int, val h: Int, val pixels: IntArray)
```

**Lifecycle:**

- **Select** — `SelectionTool` drag defines `Selection.rect` (clipped to canvas bounds). Rendered as marching ants in `CanvasView`'s overlay pass; pixels are untouched.
- **Copy** — extract `rect` pixels into `AppState.clipboard` (row-by-row `arraycopy`). Canvas unchanged.
- **Cut** — Copy, then fill `rect` in the buffer with the **vacated-area fill** = `backgroundColor` if opaque else `TRANSPARENT`. One undo step.
- **Paste** — build a `FloatingRegion` from `clipboard`, initial `offset` at canvas origin (or view center). It renders **above** the canvas at full alpha while floating; the buffer is not yet modified.
- **Move** — when a `Selection` exists, starting a drag inside it **lifts** those pixels into a `FloatingRegion` *and* fills the vacated `rect` with the vacated-area fill (spec §6). Dragging updates `offset`.
- **Commit** (click outside / Enter / tool switch) — composite the `FloatingRegion` into the buffer via `blend` (source-over, §3.5) at `offset`, clipped to bounds; clear the floating region; push one undo step.

**Rendering order in `CanvasView` `DrawScope`** (bottom → top): checkerboard (if transparent) → active `ImageBitmap` → onion-skin overlay (30%, if on) → `FloatingRegion` (full alpha) → marching-ants selection border → live tool preview.

> **Interaction notes:** switching frame tabs or pressing a number key while a `FloatingRegion` is uncommitted should **auto-commit** first (so floating content isn't silently lost). Cut/Copy/Paste honor the size-lock: paste never resizes the canvas — content is clipped to current bounds.

### 14.2 Flood fill: tolerance & matching (spec §4.4, §5)

- `AppState.fillTolerance: Int` (0–255), default `0` (exact match per spec §4.4).
- A pixel matches the seed if its per-channel ARGB distance is within tolerance. Use **max-channel difference** (Chebyshev) for predictable behavior:
  `match = maxOf(|dA|,|dR|,|dG|,|dB|) <= tolerance`.
- Algorithm: iterative **4-connected** scanline flood fill over the `IntArray` using an explicit stack/queue (no recursion — avoids stack overflow on large regions). Replacement uses `set` (hard) with the active color.
- Guard the no-op case (seed already equals fill color) to avoid infinite work.

### 14.3 Shape / Line fill mode (spec §4.7, §5)

- `AppState.fillMode: FillMode = { OUTLINE, FILLED, BOTH }`.
- `ShapeTool` (rect/ellipse) on commit: `FILLED`/`BOTH` rasterize the interior with the **secondary** color; `OUTLINE`/`BOTH` stroke the border with the **primary** color at `brushSize` thickness (spec §4.7).
- `LineTool` ignores `fillMode` (always a stroke); uses primary color + `brushSize`.
- Outline stroking respects `brushAntialias` (§14.4) for edge coverage.

### 14.4 Brush anti-aliasing (spec §4.2)

- `AppState.brushAntialias: Boolean` (default on for Brush, forced off for Pencil per spec §4.1 hard edges).
- For each stamped pixel along the interpolated stroke, compute `coverage` from distance-to-centerline vs. brush radius: `1.0` inside, `0.0` outside, a 1px linear ramp between when AA is on (when off, threshold at `0.5` → hard edge).
- Final source alpha = `color.a * coverage`, written via `blend` (source-over, §3.5). This is the single place soft edges enter the buffer, so anti-aliased brush, paste, and move all composite consistently.

> **On compositing generally (the earlier "biggest omission"):** the source-over model and the per-tool **replace (`set`) vs. blend (`blend`)** decision are fully specified in **§3.5** and `PixelCanvas` exposes both paths (§3.1). Brush alpha (spec §2.3) and anti-aliased edges (spec §4.2) both go through `blend`; hard tools use `set`. No tool overwrites where the spec calls for blending.

---

## 15. Input Mapping, Event Routing & Render Performance

### 15.1 Viewport: screen ↔ pixel mapping & pan (covers omission #10)

All pointer input arrives in **screen/component coordinates** and must be converted to **canvas pixel coordinates** through a single viewport transform. One `Viewport` owns this so every tool, the marquee, and paste positioning agree.

```kotlin
class Viewport(
    var zoom: Int = 1,            // integer factor for crisp nearest-neighbor (§2.2): 1,2,4,8,...
    var pan: Offset = Offset.Zero // top-left of the canvas image within the component, in screen px
) {
    // screen -> pixel (floor so a whole on-screen block maps to one pixel)
    fun toPixel(screen: Offset): IntOffset = IntOffset(
        x = floor((screen.x - pan.x) / zoom).toInt(),
        y = floor((screen.y - pan.y) / zoom).toInt(),
    )
    // pixel -> screen origin of that pixel's block (for drawImage / previews)
    fun toScreen(px: Int, py: Int): Offset = Offset(px * zoom + pan.x, py * zoom + pan.y)

    fun inBounds(p: IntOffset, w: Int, h: Int) = p.x in 0 until w && p.y in 0 until h
}
```

- **Mapping:** `pixel = floor((screen − pan) / zoom)`. The inverse drives `drawImage` (`dstOffset = pan`, scale `= zoom`, nearest-neighbor) so on-screen rendering and hit-testing use the *same* transform — they can't drift.
- **Clipping (spec §2.2):** tools test `inBounds`; out-of-canvas pointer positions are ignored for drawing but still tracked for drag math (e.g. a shape dragged partly off-canvas clips on commit).
- **Pan / scroll** — when `width*zoom > viewportW` or `height*zoom > viewportH`, the canvas exceeds the viewport:
  - Vertical mouse-wheel scrolls Y, **Shift+wheel** scrolls X, **Ctrl+wheel** zooms (anchored at the cursor: keep the pixel under the cursor fixed by adjusting `pan` when `zoom` changes).
  - Optional scrollbars reflect `pan`; `pan` is clamped so the canvas can't be dragged entirely out of view.
  - Zoom levels snap to the integer set (plus a "fit to window" that picks the largest integer zoom fitting the viewport). Fractional zoom is avoided to keep pixels crisp per spec §2.2.

### 15.2 Tool event signature: button + modifiers (covers omission #11)

The `Tool` interface must carry **which mouse button** and **which modifier keys** are active, so primary/secondary color selection (spec §3, eyedropper §4.5) and shift-constraints work.

```kotlin
enum class MouseButton { PRIMARY, SECONDARY }   // left, right per spec §3

data class ToolEvent(
    val pixel: IntOffset,        // already mapped via Viewport (§15.1) and clip-tested
    val button: MouseButton,
    val shift: Boolean,
    val ctrl: Boolean,
    val alt: Boolean,
)

interface Tool {
    fun onPress(canvas: PixelCanvas, e: ToolEvent, app: AppState)
    fun onDrag(canvas: PixelCanvas, e: ToolEvent, app: AppState)
    fun onRelease(canvas: PixelCanvas, e: ToolEvent, app: AppState)
}
```

- **Button → color:** `PRIMARY` uses `app.primary`, `SECONDARY` uses `app.secondary` (spec §3). Eyedropper writes the picked color to primary on left, secondary on right (spec §4.5).
- **Modifiers:** `shift` constrains shapes to **square/circle** (equalize the bounding box) and lines to **0°/45°/90°** snapping; `ctrl` reserved for future (e.g. add-to-selection). 
- **Source in Compose Desktop:** `PointerEvent` exposes `buttons.isPrimaryPressed` / `isSecondaryPressed` and `keyboardModifiers.isShiftPressed` (and `awtEventOrNull` for anything missing). `CanvasView` translates raw pointer events → mapped `ToolEvent` before dispatch, so tools stay free of Compose/AWT types.

### 15.3 Freehand undo granularity (covers omission #12)

A drag must be **one** undo entry, not one per sample:

- **On `onPress`** of a mutating tool: take a single snapshot of the pre-gesture buffer into a held `pendingUndo` (do **not** push yet).
- **On `onDrag`**: mutate the live buffer freely (and accumulate a dirty rect, §15.4). No snapshots.
- **On `onRelease`**: push `pendingUndo` onto the undo stack as one entry and clear redo. Result: an entire pencil/brush/eraser stroke, or a shape/line drag, collapses to a single undo step.
- Instantaneous operations (fill, clear, resize, transparency-toggle, paste-commit, cut, move-drop) follow the same shape: snapshot-before → apply → push once.

### 15.4 Dirty-rect bitmap upload (covers omission #15)

Re-uploading the entire `ImageBitmap` on every `version` bump is fine at 800×600 but wasteful during a live drag on a large canvas. Optimize **upload** the same way §3.4 optimizes undo:

- Back the display by a **long-lived Skia `Bitmap`** (created once per canvas size) rather than rebuilding an `ImageBitmap` from scratch each mutation.
- During a gesture, accumulate a **dirty `IntRect`** (union of every stamped pixel's bounds, §15.3).
- On recompose, refresh **only the dirty sub-rectangle** of the Skia bitmap from the `IntArray`, then draw. Reset the dirty rect after upload.
- **Pragmatic staging:** milestone 2–3 ships the simple full-reupload (correct, trivial); add dirty-rect upload when measured — trigger it when `width*height` exceeds ~1 MP (≈1024²) or whenever a drag is in progress. Below that, full reupload per frame is imperceptible.
- A full reupload is still used for whole-buffer ops (resize, transparency-flatten, clear, import).

> Dirty-rect tracking is shared plumbing: the same accumulated rect can later feed the undo dirty-diff optimization noted in §3.4, so build the `DirtyRect` accumulator once in the gesture loop.

## followup notes
Show relative UIs as floating windows when relative tool is being used:
e.g.
- show the Tolerance slider only when Fill is selected
- show the Outline/Filled/Both buttons only when Rectangle/Ellipse is selected