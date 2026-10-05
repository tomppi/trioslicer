# G-code correctness: does TrioSlicer produce what the real slicer produces?

One document per engine. Each records the model, the preset and settings used,
how the comparison was made, what the tool paths measure, and every difference
found.

The app is not a reimplementation of these slicers. It bundles the real engines
as Android binaries and drives them:

| engine | bundled version | reference used |
|---|---|---|
| CuraEngine | 5.14.0-alpha.0 | the engine inside the released `UltiMaker-Cura-5.14.0-alpha.0` AppImage |
| PrusaSlicer | 3.0.0-alpha11 | the released PrusaSlicer 3.0.0-alpha11 (Flathub beta, pinned by commit) |
| OrcaSlicer | 2.4.2 | the released OrcaSlicer 2.4.2 AppImage |

All three references are **version-exact** with what the app bundles. With
identical input the tool paths should therefore be identical, and any difference
is a finding rather than a question of tolerance.

## The comparison

`scripts/compare-gcode.py` compares two files as tool paths:

- an extruding move is one segment, with its start and end X/Y/Z;
- segments are matched as a multiset within a tolerance, so emission order and
  where a closed loop starts (the seam) are not differences;
- a segment present in one file and not the other, or at measurably different
  coordinates, is a real difference;
- `--geometric` compares per-layer coverage on a grid instead, which is the
  measure that survives the engine's own run-to-run noise;
- **layers are aligned by Z, never by index** — see cura.md for why that is not
  a detail.

`scripts/gcode-truth-run.sh` slices a configuration in the app (writing its
persisted settings directly, so the value that reaches the engine is the value
intended) and brings back the output, the log and the engine's working
directory.

## The headline finding, and it is not about the app

**CuraEngine 5.14 is not deterministic run to run.** Two runs, same machine,
byte-identical arguments: 74.1% of segments the same, and the segment *count*
itself changes. See [cura.md](cura.md). Nothing can be compared exactly against
a slicer that will not reproduce itself, so every comparison here reports a
control — reference against reference — alongside the app against the reference.
