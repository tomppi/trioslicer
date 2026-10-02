# Working in TrioSlicer

Notes for anyone - human or agent - changing this app. Keep it short and only add rules that a
change has already broken once.

## The nozzle-path preview must draw every move

`GcodeNozzlePathParser` (and `PrusaNozzlePathParser`) feed the start-to-finish path view. Their
move budget exists only to bound memory - the ribbons cost roughly 800 bytes per move - and when it
bites, a move is **merged into the next kept move**, never dropped.

- Do **not** convert these parsers to a streaming reader that draws what it holds and discards the
  rest.
- Do **not** lower the budget, and do **not** sample, decimate or skip moves to fit it. It is a
  last-resort guard (400,000 moves), set so a real print never reaches it.
- A move may only be folded into a run that genuinely continues it - the same kind of move, in
  the same direction. Folding across a corner cuts the corner, and folding a travel into an
  extrusion draws a straight line across the part that the printer never made.
- A skipped move is a hole in the middle of a wall. With a third of the moves missing, the preview
  rendered as beads with gaps between them, and as short plates with air where the plastic should
  be when viewed up close. It took four wrong fixes to find, because the geometry that survived was
  correct - only a third of it was missing.
- `NozzlePathDecimationTest` fails if a tight budget loses distance or filament. Keep it passing.
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
