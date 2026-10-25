# Working in TrioSlicer

Notes for anyone - human or agent - changing this app. Keep it short and only add rules that a
change has already broken once.

## A borrowed payload must be extracted by the borrower

The CAD engine has no interpreter of its own: it runs on the CPython inside the Klipper payload
(`files/klipper`), which for a long time only the Klipper host ever extracted. A user who opened
CAD first watched 533 MB of OCCT unpack and then got "the app's Python is not extracted yet" -
a sentence nothing on that path could ever change, because the tree it named was never written.

- `KlipperPayload` is the one extractor and the one stamp for `files/klipper`; the host and the
  CAD engine both go through it.
- Anything that borrows another engine's payload calls that owner's extraction path and must not
  assume it has run. A screen that depends on it says what it is waiting for, with numbers that
  move, and ends in ready or a named failure.
- The first-run **Prepare the engines** screen prepares each payload through these same paths, in
  dependency order (the Python before the CAD tree), one engine at a time.

## Every G-code reader that draws must draw everything it read

The app reads G-code for display in exactly four places. **None of them may sample** - a stride
is what took a third of every layer out of the Layers tab:

| reader | what it feeds | budget behaviour |
|---|---|---|
| `GcodeNozzlePathParser` | Path tab, Cura dialect | folds only, never drops |
| `PrusaNozzlePathParser` | Path tab, PrusaSlicer and OrcaSlicer output | folds only, never drops |
| `GcodeLayerPreviewParser` | Layers tab, all three dialects | folds only, never drops |
| `GcodeLayerEventProcessor` | event injection into a rewritten file | streams every line, no budget |

Read-only helpers that look like readers but cannot drop geometry: `ParseProgress` (byte
progress), `PrintTimeEstimate` (one header string), `KlipperGcodeFiles` (metadata and a
thumbnail; its 200-entry cap is a list of prints, not moves), `GcodeSanitizer` (validates and
repairs every line; a rejected line is a named error, not a silent drop), the conical and
conformal transformers (they rewrite every move and `check` the `MAX_EMITTED_MOVES` guard
rather than trimming), and `NozzleCollisionScanner` (reads every move; its caps bound the
sweep's step count and a safety grid, not the toolpath).

## Object labels are text, not parameters

`GcodeCommand` scans parameters as `letter + number`, so a quoted object name's own digits used to
become parameters. PrusaSlicer's firmware labelling writes `M486 A"cube10.stl lower"`, the scan found
`E10` in the name, and the published-G-code policy rejected the engine's own output - the
PrusaSlicer engine could not publish a slice at all unless `gcode_label_objects` was set to 0 by
hand, and the label the printer needs never reached the file.

- Quoted spans are **text**: `GcodeCommand` does not scan them for parameters, and `rawArguments`
  keeps them for the labels the printer and the app read.
- **M486 accepts Marlin's whole parameter set** - `S` (any whole object id from -1 up, no upper
  bound), `A"name"`, `C`, `P"name"`, `T`, `U` - because both PrusaSlicer's firmware labelling
  and the app's own multi-object writer emit it. A letter outside that set, or an `S` that is not a
  whole id from -1 up, is still refused by name. `PublishedObjectLabelTest` holds both halves, and
  checks that a published file keeps its labels.

## The nozzle-path preview must draw every move

`GcodeNozzlePathParser` (and `PrusaNozzlePathParser`) feed the start-to-finish path view. Their
move budget exists only to bound memory - the ribbons cost roughly 800 bytes per move - and when it
bites, a move is **folded into the run it continues**, never dropped: consecutive moves that
genuinely continue each other are drawn as one straight move.

- Do **not** convert these parsers to a streaming reader that draws what it holds and discards the
  rest.
- Do **not** lower the budget, and do **not** sample, decimate or skip moves to fit it. It is a
  last-resort guard (400,000 moves), set so a real print never reaches it.
- A move may only be folded into a run that genuinely continues it - the same kind of move, in
  the same direction. Folding across a corner cuts the corner, and folding a travel into an
  extrusion draws a straight line across the part that the printer never made.
- That holds for the move a run **closes** at, not only for the moves folded inside it. A run
  closed at a move that turns, or that extrudes after a travel, draws a chord across the part:
  one reported Ghost Dog slice had 513 drawn beads over more than 5 mm of travel each, the
  longest 97 mm, which is what the slivers across it were. `NozzlePathFoldInvariantTest` fails
  when a drawn move stands for source moves off its own line.
- A skipped move is a hole in the middle of a wall. With a third of the moves missing, the preview
  rendered as beads with gaps between them, and as short plates with air where the plastic should
  be when viewed up close. It took four wrong fixes to find, because the geometry that survived was
  correct - only a third of it was missing.
- `NozzlePathDecimationTest` fails if a tight budget loses distance or filament. Keep it passing.
- The ribbon is built a chunk at a time into small direct buffers and uploaded to the GPU as it is
  built (`RibbonChunkStaging` into `RibbonVboStore`); window i always lands at vertex i * 18, so
  the draw calls, the scrubbing and the picture are what they were. **Never go back to one buffer
  per path.** Android charges direct ByteBuffers against the Java heap, and the Path tab died on a
  single 160 MB one - `extrusionCount * 18 * 3` floats for the 778,626 extrusion moves of a real
  print, with three more buffers behind it. `NozzlePathMemoryTest` measures this on a
  900,000-move file and fails if the whole-path allocation pattern comes back.
- Size those buffers from the moves themselves (`RibbonPathGeometry.countKind`), never from the
  parser's `extrusionMoveCount`: a merged move carries the extrusion of the moves folded into it,
  so the stored extrusion count can be higher than the parser's readout - and a buffer sized below
  the windows written overruns it.
- The buffers no longer hold the whole path by default. A print can need more windows than a
  phone has graphics memory - the reported 0.08 mm PrusaSlicer print is 1,617,938 of them, 1.2 GB -
  so the preview holds a **band** of moves around the scrub and rebuilds it when the scrub leaves
  the middle half (`RibbonBand`). Every move inside the band is drawn; the band is a window, never
  a sample.
- How big a band, and how much folding, comes from the **device**, not a constant: AUTO derives it
  from free RAM and the app's heap class, UNLOCKED and LOW_MEMORY are the user's choice in either
  direction (`PreviewMemoryPolicy`, `PreviewCapacity`), and the low-memory profile keeps the old
  conservative figures (400,000 Cura / 1,000,000 Prusa moves, a 200,000-window band). A device
  with RAM to spare folds nothing and can hold the whole path.
- Whatever the profile, **no move is ever skipped**: a low-memory profile draws a coarser preview,
  never a lossier print. The parse budget only decides how much folding happens.
- A driver may refuse an allocation the RAM figures said was fine, so the preview asks for the
  whole band first and **halves on refusal** until something fits, remembering what fitted for the
  session (`BandAllocator`). Only a refused floor ends in the named refusal, and that sentence
  carries both numbers.
- Cura's layer view is the reference for this screen, and it draws every line it reads:
  `plugins/GCodeReader` and `plugins/SimulationView` in github.com/Ultimaker/Cura.

## The layer view draws every path of the layer it shows

`GcodeLayerPreviewParser` feeds the Layers tab. Its cap is the same kind of memory guard as the
path view's, and it used to behave the way that view's did before the fold: past 800,000 segments
it kept one path in three spread across the whole print, so a single layer came out sparse - the
reported PrusaSlicer print showed "1228 paths - 1228 through this layer" where the layer really
holds about three times that.

- The cap **folds**: a run closes only at a move that genuinely continues it (same feature, same
  direction within `GcodeNozzlePathParser.FOLD_COLLINEAR_DOT`, no further off the run's line than
  `FOLD_DRIFT_MM`), and every source path is covered exactly once.
  `GcodeLayerPreviewFoldTest` proves it for Cura, PrusaSlicer and OrcaSlicer files past the cap,
  and `RealGcodeLayerCoverageTest` measures the reported prints.
- Every count the Layers tab shows - "N paths", "N through this layer", "Support N" - is the
  **source** count (`sourceSegmentCount`, `supportSegmentCount`), never the stored runs.
- The vertex buffers hold a **window** of layers, and the style decides which window. **Current
  layer** holds the selected layer, eight under it and two above (`LayerWindow.plan`), because
  that view only ever shows one layer. **Build-up** holds **layer 0 to the selected layer** - the
  whole model under the scrub (`LayerWindow.planBuildUp`) - because that view draws the
  accumulation; an eleven-layer context window drew a slice of a 586-layer print. The style is
  part of the plan, not just the draw: both styles were planned as a context band in the 101/107
  layer-window work, and the footer's "11 layers" could not tell the slice apart from a stack.
- The build-up window is budgeted by `LayerWindow.vertexPathBudget` from
  `PreviewCapacity.segments`: the profile's heap share is counted in 24-byte stored paths, and
  the window's path vertices cost 56 bytes, so the same share buys fewer of them. The reported
  586-layer Cura slice is 768,747 paths - about 43 MB of path buffers in full - so the whole
  stack is the normal case; a device that genuinely cannot take it holds the layers under the
  selection that fit and **names both numbers**, never draws a silently short stack. Never build
  the whole print's lines into one buffer: that is what the old 800,000-segment cap was really
  protecting.
- A driver may refuse the window's allocation, so the path budget halves down to the selected
  layer alone (`BandAllocator`); a layer that even then will not fit is **named with both
  numbers** (`LayerWindow.refusal`), never sampled.
- The window's ribbons belong to the selected layer and are rebuilt on every selection change,
  including moves inside the window; the path buffers are rebuilt only when the selection leaves
  a current-layer window, and the build-up stack is **grown** - the new layers are appended (or
  copied into a larger buffer), not rebuilt from the bed - as the scrub moves up
  (`LayerPreviewGeometry.select`). The footer's "N layers in the buffers" is the held window in
  both styles.
- A restored artifact has no preview until it is read: the Layers tab says "Reading the file's
  layers…" and the ViewModel parses it (`ensureLayerPreview`) instead of showing the model.
- The window is **re-planned for every selection** from the device's capacity, and nothing about a
  smaller window is remembered between them. Remembering it is what collapsed Build-up to "1 layer in
  the buffers" while 6.9 GB were free: the window near the start of a print is small because the
  print ends there, not because a driver refused anything. Only a refused allocation halves the plan,
  and a reduced window says so in the footer with both numbers.

## Bead geometry follows Cura, not our own conventions

Checked against Cura's source, and each one was wrong here before:

- The bead's **top face sits on the path Z**; the body hangs one layer below it
  (`layers3d.shader`: `v1_vertex.y -= a_line_dim.y / 2`).
- **Travels have zero thickness** - they are lines, not beads
  (`FlavorParser.py`: "Travels are set as zero thickness lines").
- **Bead width** = `ΔE × π(d/2)² / length_XY / layer_height`, and it is read from the file's
  `;Layer height:` header rather than guessed from Z rises.
- A width is **never zero** (`FlavorParser.py`: `line_widths[:, 0] = 0.35  # Just a guess`).

## A CAD document is the engine's; the plate edits a derived mesh

The Plate can show a live SolidCraft CAD document. Two owners, and a change must not confuse
them: the **document** is the engine's (features, parameters, its own coordinates, which bodies
exist) and only the agent authors it; the **derived mesh** on the plate is the app's, and every
human plate function - move, rotate, scale, split, snap fit, paint, Slice, Export - acts on it.

- A repaint after an agent edit replaces the plate object's `sourceMesh` in place and
  **re-applies** the human's edits: the placement transform relative to the old mesh, and the
  paint only while the triangle count matches (otherwise it is cleared and named in the
  status). It must never reset the camera, recentre the part, or go through a file-loading
  step: `ModelSurfaceView.resetCamera` fires on a new display name or a changed object count,
  so the document object keeps one id and one name.
- No debounce on the picture. An edit repaints as soon as it is reported; a second edit that
  arrives during a repaint is merged into the next one. Persisting the display cache and the
  workspace descriptor is batched - that is persistence, not the view.
- The engine exports **only the bodies the edit named** (`file.export` with a `bodies` list).
  Undo/redo, `set_parameter`, `new_design` and `open` carry no body report and fall back to
  reading the document body by body. `screenshot` is refused by the bridge: the plate is the
  viewport.
- A split or snap layout is geometry made from the previous shape. When the document changes
  under it, the document is marked stale with a named reason, Slice refuses it, and **Rebuild
  plate from the document** is the deliberate way to take the new shape. Never paste the new
  mesh over the user's split.
- Slice exports the print STL from the current document (or reuses the last export while the
  document is unchanged) and slices the derived mesh, so the human's plate edits are what
  prints. The Slice export and the per-edit display tessellation are the same SolidCraft STL
  export; they differ in when they are taken.
- Open CAD files (STEP/IGES/3MF/OBJ/glTF) through `Import > Open CAD document`, which uses the
  engine's own io crate and keeps a document. `Import model` keeps the mesh routes, including
  OpenCASCADE's STEP/IGES conversion. The menu item is the route choice.
