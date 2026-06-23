# Microsoft Paint — Basic Specification

A lightweight raster (bitmap) drawing program. This spec covers the **canvas** and the **basic toolset** only — no layers, no filters, no advanced effects.

---

## 1. Overview

- **Type:** Raster (bitmap) editor specialized for authoring game sprites and animation frames.
- **Color model:** 32-bit RGBA (8 bits per channel) stored in an offscreen pixel buffer.
- **Document:** One program instance holds a set of **frame tabs** — a `base` frame plus numbered frames `1`–`9`. Each tab owns one flat bitmap (no layers). See §8 for the frame workflow.

---

## 2. Canvas

The canvas is the editable bitmap surface and the heart of the program.

### 2.1 Properties

| Property        | Description                                                        | Default        |
|-----------------|-------------------------------------------------------------------|----------------|
| Width           | Pixel width of the bitmap                                          | 800 px         |
| Height          | Pixel height of the bitmap                                         | 600 px         |
| Background      | Fill of a new/blank canvas — a solid color **or** transparent      | White (#FFFFFF)|
| Transparency    | Whether unpainted pixels are fully transparent (alpha = 0)         | Off            |
| Zoom            | Display scale factor (does not change pixel data)                  | 100%           |
| Max size        | Practical upper bound on dimensions                               | 4096 × 4096 px |

### 2.2 Behavior

- The canvas stores a fixed grid of pixels. Drawing operations write color values directly into this grid.
- **Resizing** the canvas adds/removes pixels at the right/bottom edges. New area is filled with the background color; cropped area is discarded.
- **Zoom** affects only on-screen rendering. At >100% zoom each logical pixel is drawn as an NxN block; pixel data is unchanged.
- Coordinates are integer pixel positions with origin `(0,0)` at the **top-left** corner. X increases right, Y increases down.
- All drawing is clipped to the canvas bounds — operations outside `[0,width) × [0,height)` are ignored.

### 2.3 Transparent Background

For producing game sprites and assets with no backdrop:

- A canvas can be created (or toggled) as **transparent**, meaning every unpainted pixel has alpha = 0 rather than an opaque color.
- Transparent regions are shown in the editor using a **checkerboard pattern** so the user can distinguish "transparent" from "white." The checkerboard is a display aid only and is never written to pixel data.
- Drawing tools write their color with full alpha (or the brush's alpha) over transparent pixels normally.
- The **Eraser** restores pixels to fully transparent (alpha = 0) when the canvas is in transparent mode — instead of painting the background color (see §4.3).
- **Toggling transparency off** fills all alpha = 0 pixels with the current solid background color. **Toggling on** does not alter existing painted pixels; it only changes how unpainted area is treated.

### 2.4 Operations

- **New** — clears the canvas to the background (solid color or transparent), resets zoom and undo history.
- **Resize** — change width/height (with optional clamp/scale). New area inherits the current background (transparent if the canvas is transparent).
- **Clear** — reset the entire canvas to its background (fully transparent if transparent mode is on, otherwise the background color).

---

## 3. Color Model

- **Primary color** — used by left mouse button / primary stroke.
- **Secondary color** — used by right mouse button / fills behind the primary.
- A color palette of preset swatches plus a custom color picker (RGB / hex input).
- Each color is `(R, G, B, A)`, each component `0–255`.

---

## 4. Tools

All tools operate on the active canvas using the current primary/secondary color and brush size. Exactly one tool is active at a time.

### 4.1 Pencil
- Draws single-pixel (or brush-sized) freehand lines following the cursor while the button is held.
- Hard edges, no anti-aliasing.
- Interpolates a straight line between consecutive mouse positions so fast movement leaves no gaps.

### 4.2 Brush
- Like the pencil but supports variable thickness and (optionally) soft/anti-aliased edges.
- Configurable brush size (e.g. 1, 3, 5, 8, 16 px).

### 4.3 Eraser
- On an **opaque** canvas: paints the secondary/background color over the cursor path.
- On a **transparent** canvas: sets pixels along the path to fully transparent (alpha = 0), punching holes through to the checkerboard.
- Configurable size, same path-interpolation as the pencil.

### 4.4 Fill (Bucket)
- Flood-fills a contiguous region of same-colored pixels starting at the click point with the active color.
- Uses a 4-connected flood fill with a color-match tolerance (default exact match).

### 4.5 Eyedropper (Color Picker)
- Clicking a pixel sets the active color to that pixel's color.
- Left click → primary color; right click → secondary color.

### 4.6 Line
- Click-drag to draw a straight line from the press point to the release point.
- Live preview while dragging; committed on release.
- Uses current color and brush size.

### 4.7 Shapes (Rectangle / Ellipse)
- Click-drag to define a bounding box; shape is drawn within it.
- Modes: **outline only**, **filled only**, or **outline + fill** (outline = primary, fill = secondary).
- Live preview while dragging.

---

## 5. Tool Options (shared)

- **Size / thickness** — applies to pencil, brush, eraser, line, shape outlines.
- **Fill mode** — outline / filled / both (shape and rectangle tools).
- **Color** — primary and secondary as described in §3.

---

## 6. Editing Operations

- **Undo / Redo** — step backward/forward through canvas states. Minimum history depth: 20 steps.
- **Select** — rectangular marquee selection.
- **Cut / Copy / Paste** — operate on the current selection; pasted content lands as a floating, movable region until committed.
- **Move** — drag a selection to reposition it; the vacated area is filled with the background color.

---

## 7. File Handling

| Action | Behavior |
|--------|----------|
| Open   | Load PNG / BMP / JPEG into the canvas, sizing the canvas to the image. |
| Save   | Write the canvas bitmap to PNG (lossless, recommended) or BMP/JPEG. **PNG preserves the alpha channel**, so a transparent canvas exports with a transparent background — ideal for game sprites/assets. JPEG and BMP have no alpha and flatten transparency onto the background color. |
| Export | Same as Save with explicit format/quality choice. |

---

## 8. Sprite Frame Workflow

The defining feature on top of the base editor. The goal is to take a base drawing and build animation frames by making small, incremental alterations — comparing each frame against the one before it.

### 8.1 Frame Tabs

- A program instance shows one or more **frame tabs** along a tab bar. Each tab is an independent canvas (its own bitmap, undo history, and transparency setting).
- There is always exactly one tab labelled **`base`**:
  - On **new instance**, an empty `base` canvas is created.
  - On **import** (always a PNG, see §7), the imported image opens as the `base` canvas, sized to the image.
- Numbered tabs `1`–`9` are the animation frames, created on demand (§8.2).
- Tabs are displayed in order: `base`, `1`, `2`, … `9`.
- Exactly one tab is **active** (visible and editable) at a time.

### 8.2 Creating & Navigating Frames (number keys 1–9)

Pressing a number key `N` (1–9) on the keyboard:

- **If tab `N` does not exist:** create it, prepopulated with a **copy of the immediately preceding tab's canvas at that moment**, then make it active.
  - The "preceding tab" is the existing tab with the next-lowest label: tab `1` copies `base`; tab `2` copies `1`; tab `3` copies `2`; and so on. (Formally: the highest existing tab whose index is `< N`.)
  - The copy is a **snapshot** — a deep copy of pixels. Later edits to the source tab do **not** propagate to frames already created from it, and vice versa.
- **If tab `N` already exists:** simply navigate to (activate) it — no copy, no overwrite.

> Edge note: pressing `N` when no lower-numbered frame exists yet (e.g. pressing `3` while only `base` and `1` exist) copies from the highest existing lower tab (`1` here). The new tab keeps the label `3`.

### 8.3 Onion-Skin Overlay (SHIFT+O)

While **any tab other than `base`** is active, **SHIFT+O** toggles an onion-skin overlay:

- The **previous tab's canvas** (same definition as in §8.2 — the existing tab with the next-lowest label) is rendered **on top of** the current canvas at **30% alpha**.
- The overlay is **read-only and non-destructive**: it is a display layer only. All tools continue to edit the **current** tab's bitmap normally; the overlaid previous frame is never modified and is not saved as part of the current frame.
- The overlay is a **snapshot** captured when SHIFT+O is pressed. Since only one canvas is visible/editable at a time, the previous frame cannot change while the overlay is shown, so a snapshot is sufficient (re-toggle to refresh if the previous frame was edited earlier).
- Toggling SHIFT+O again removes the overlay.
- On the `base` tab the shortcut is a no-op (there is no previous frame).

### 8.4 Frame Dimensions

- **All numbered frames (`1`–`9`) share one fixed size.** The size is established by the first numbered frame created, which inherits `base`'s dimensions at that moment; every later frame is created at that same size.
- The **`base` tab is exempt** — it may be any size (e.g. a larger reference drawing). Resizing `base` afterward does **not** change the established frame size.
- Because frames are size-locked to each other, the onion-skin overlay (§8.3) always aligns pixel-for-pixel between adjacent frames.

### 8.5 Deleting Frames

- A frame tab `1`–`9` can be deleted. The **`base` tab cannot be deleted** within the app.
- Deleting a frame **re-orders all higher-numbered frames down by one** to close the gap: e.g. deleting frame `2` renumbers `3`→`2`, `4`→`3`, and so on. This keeps frames contiguous (`base`, `1`, `2`, …) so number-key navigation and save labels stay consistent.
- Re-ordering only relabels tabs; it does not alter any frame's pixels.

### 8.6 Save All Frames

A **Save** button (and shortcut) exports **every open tab** in the instance as a separate PNG:

1. The user is prompted for a **base name** and a **destination folder**.
2. Each tab is written as `{name}_{label}.png`, using PNG so alpha/transparency is preserved (§7).

Example — tabs `base`, `1`, `2`, `3` open, name entered = `fighter_idle`:

```
fighter_idle_base.png
fighter_idle_1.png
fighter_idle_2.png
fighter_idle_3.png
```

- All frames save to the same folder in one action.
- Existing files of the same name are overwritten (with a confirmation prompt).

---

## 9. Out of Scope (for this basic spec)

- Layers, blending modes, transparency groups
- Filters / image adjustments (blur, brightness, etc.)
- Gradients, custom brushes, pressure sensitivity
- Vector objects (everything is rasterized immediately)
- Animation playback / preview, frame timing, sprite-sheet packing (frames are saved as individual PNGs only)
