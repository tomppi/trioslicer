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

## Bead geometry follows Cura, not our own conventions

Checked against Cura's source, and each one was wrong here before:

- The bead's **top face sits on the path Z**; the body hangs one layer below it
  (`layers3d.shader`: `v1_vertex.y -= a_line_dim.y / 2`).
- **Travels have zero thickness** - they are lines, not beads
  (`FlavorParser.py`: "Travels are set as zero thickness lines").
- **Bead width** = `ΔE × π(d/2)² / length_XY / layer_height`, and it is read from the file's
  `;Layer height:` header rather than guessed from Z rises.
- A width is **never zero** (`FlavorParser.py`: `line_widths[:, 0] = 0.35  # Just a guess`).
