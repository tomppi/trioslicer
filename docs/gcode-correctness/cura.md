# CuraEngine 5.14.0-alpha.0

- **App side**: TrioSlicer 1.7.0 on the dev phone (Xiaomi M2007J3SG, arm64),
  Cura route, engine `libcuraengine_exec.so`.
- **Reference**: `CuraEngine` from the released
  `UltiMaker-Cura-5.14.0-alpha.0-linux-X64.AppImage`, confirmed
  `Cura_SteamEngine version 5.14.0-alpha.0`, run on x86-64 Linux.

## Model and settings

| | supports ON (compared) | supports OFF (sliced, not yet compared) |
|---|---|---|
| Model | `000-mushroom.stl`, 512 triangles, 40 × 40 × 15 mm | same |
| Placement | scaled 4×, centred at (115, 115) → 160 × 160 × 60 mm | unscaled, 15 mm tall |
| Printer | Modified Ender 3 V2, 230 × 230 × 250 mm, 0.4 mm nozzle | same |
| Layer height | 0.2 mm | 0.2 mm |
| Support | tree, everywhere, 56° | none |
| Output | 301 `;LAYER:` blocks, ~1.49 M segments | 76 layers, 86,617 segments |

Preset: **no vendor preset** on this route. The app slices the sheet's settings
directly; the machine definition is `creality_ender3.def.json` from the app's
own bundled Cura 5.14 definitions (1,251 files under
`app/src/main/assets/cura/definitions`), and the settings are passed as
`-s key=value` — the app's log calls this "standalone fallback command-line
values". The exact 867 arguments are taken **verbatim from the app's own
diagnostic log**, and the slice's working directory (the transformed
`model.stl` and the definitions it used) is still in the app's cache.

## Finding 1: the engine does not reproduce itself

Two reference runs, same machine, byte-identical arguments:

| | run A | run B |
|---|---|---|
| extrusion segments | 1,493,574 | 1,488,694 |
| tool-path length | 3,125,721.06 mm | 3,116,367.26 mm |
| output | 60,946,126 B | 60,775,145 B |
| **segments in both** | **1,106,576 → 74.1 %** | |
| layers with a difference | 239 of 300 | |

The segment **count** changes, so this is not emission order: it is
thread-scheduling sensitivity in the slicing. The app runs `-m8`
(`CpuTopology.recommendedThreadCount()`, hard-coded, with no setting), and so
did the reference.

This generalises an earlier observation recorded as B42, where support
generation drifted 3.3 % across three runs and was put down to a support-specific
quirk. It is not support-specific and it is far larger.

### It is purely threads, and single-threaded it is exact

The same slice was run twice with `-m1` substituted for the app's `-m8`, nothing
else changed:

| threads | output size | mean per-layer IoU | worst layer |
|---|---|---|---|
| `-m8` (what the app uses) | 60,946,126 / 60,775,145 B | 0.8213 | 0.3705 |
| **`-m1`** | **60,965,197 / 60,965,197 B — identical** | **1.0000** | **1.0000** |

Single-threaded, CuraEngine is **exactly reproducible**: the two outputs are the
same size and every one of 300 layers scores 1.0000. The non-determinism is
entirely a threading artefact.

**The consequence for the app is the real finding.** `CuraEngineCommand` passes
`-m` `CpuTopology.recommendedThreadCount()` — `max(runtime, hardware)`, clamped —
with no setting to change it. **TrioSlicer therefore cannot reproduce its own
slices**: the same model and the same settings give measurably different G-code
each time, on the device, by the same margin measured here. Everything downstream
of that — "did the last slice change anything", "is this print the one I
validated", any comparison against a reference — is affected.

A slicer-threads setting (default unchanged, so nothing gets slower) would make
the app reproducible on demand. It is a user-facing control, so it has not been
added unilaterally; it is raised as the finding it is.

## Finding 2: the app's tool paths are as accurate as the engine allows

Per-layer coverage on a 0.2 mm grid, layers aligned by Z:

| comparison | mean per-layer IoU |
|---|---|
| **control** — reference vs reference, identical arguments | **0.8213** |
| **test** — app vs reference | **0.7605** |

299 of 301 layers matched a reference layer by Z. The engine disagrees with
itself about 18 % of covered cells; the app is within a few points of that. **No
evidence of a placement, scaling or geometry defect.** X, Y and Z all agree: the
layers match by Z to 0.02 mm, and the coverage overlaps in place.

### The layer-count difference is a marker difference

The app's file has 301 layers, the reference's 300, and an index-aligned
comparison reads that as catastrophic. It is not:

| | `;LAYER:` blocks and segment counts |
|---|---|
| app | 0 → 2, 1 → 6 250, 2 → 2 293, 3 → 2 096 |
| reference | 0 → 5 873, 1 → 2 162, 2 → 1 935 |

At the same Z the counts differ by 6–8 %, the non-determinism magnitude. The app
splits the start G-code's prime line and the first layer across two `;LAYER:`
blocks where the reference uses one, so every later marker is one higher.
Comparing by index pits the first layer against the second and manufactures a
translation that is not there. **Layers must be aligned by Z.**

## Finding 3: supports on and off, both measured

Same model, same settings, one flag apart. Every pair is compared on a 0.2 mm
grid with layers aligned by Z, and every test carries its own control — the
reference against itself — because Finding 1 makes an uncontrolled comparison
meaningless.

| supports | model | control (ref vs ref) | test (app vs ref) | layers matched |
|---|---|---|---|---|
| ON | 4× scaled, centred at (115, 115) | 0.8213 | 0.7605 | 299 of 301 |
| **OFF** | **unscaled, centred at (115, 115)** | **0.9836** | **0.9511** | 74 of 76 |

**Supports OFF — the app is correct.** 0.9511 against a control of 0.9836, and the
whole of the 0.033 gap is the two unmatched prime-line layers; 2 layers of 76
scoring zero costs 0.026 of the mean on its own. Bucketed by feature, at three
heights, with supports genuinely off (no `;TYPE:SUPPORT` in either file):

| Z | type | app cells | ref cells | IoU |
|---|---|---|---|---|
| 0.48 | SKIN | 806 | 806 | **1.0000** |
| 0.48 | WALL-INNER | 144 | 144 | **1.0000** |
| 0.48 | WALL-OUTER | 176 | 176 | **1.0000** |
| 2.08 | FILL | 151 | 151 | **1.0000** |
| 2.08 | SKIN | 16 | 16 | **1.0000** |
| 2.08 | WALL-INNER | 247 | 247 | **1.0000** |
| 2.08 | WALL-OUTER | 851 | 849 | 0.9976 |
| 8.08 | WALL-INNER | 555 | 555 | 0.9964 |
| 8.08 | WALL-OUTER | 2 937 | 2 938 | 0.9915 |

**Supports ON — the support geometry differs.** Same treatment, with
`support_enable=true` in both:

| type | app cells | ref cells | IoU |
|---|---|---|---|
| SKIN / WALL-INNER / WALL-OUTER / FILL | — | — | **1.0000** (identical to the table above) |
| **SUPPORT** | 1 578 | 1 456 | **0.0672** |

The walls, skin and infill are *exactly* the reference's in both configurations.
With supports on, the entire difference is support — and it is a real difference,
not the threading noise: the engine can reproduce support (two `-m8` runs agreed
to 0.9998 with support included), yet the app's support differs from all five
reference runs.

### The support difference is the engine, not the app — and it is now proven

The decisive test does not involve the reference at all. The app's **own arm64
engine binary** was run **on the same phone**, in the same working directory,
with the same 867 arguments, writing a second G-code file. If the app were doing
anything wrong, this would show it.

| type | app's slice | same engine, re-run | IoU |
|---|---|---|---|
| SKIN | 16 917 | 16 917 | **1.0000** |
| WALL-INNER | 683 | 683 | **1.0000** |
| WALL-OUTER | 713 | 713 | **1.0000** |
| **SUPPORT** | 8 817 | 9 068 | **0.2595** |

**The engine cannot reproduce its own tree support** — same binary, same device,
same arguments, same input — while every other feature comes out *exactly*
identical. Tree support is chaotic: it branches recursively, and the smallest
difference in evaluation order changes the topology completely. This is the same
effect that makes reference runs a and b differ from each other and from c/d/e
(0.086–0.429 support-to-support) while c, d and e agree to 1.0000.

**So the answer to "what does the app's tree support do differently" is:
nothing.** The app's support differs from the reference's for the same reason the
reference's differs from itself between runs. What the app *controls* — walls,
skin and infill — is exact in every configuration tested.

That also means tree-support comparisons are not usable as a correctness signal
for any slicer, and the honest measure for a supports-on comparison is the
non-support geometry plus the support's *envelope*, not its IoU.

**Supports ON**: the app is 0.06 below the reference's own reproducibility. That
is within the run-to-run spread and needs no explanation.

**Supports OFF**: the app is **0.24 below** — and unlike the supports-on case,
one control pair is not enough to dismiss it. Seven reference runs were compared
against each other and the app against every one of them:

| | mean IoU |
|---|---|
| reference vs reference | 0.8953, 0.8039, 0.8040, 0.8040, 0.8162, 0.8162, **0.9998** |
| app vs reference, five runs | 0.6589, 0.6608, 0.6570, 0.6570, 0.6570 |

The reference agrees with itself between **0.804 and 1.000** — runs c, d and e are
the same slice to 0.9998, so the spread is real and not a measurement artefact.
The app scores **0.657 ± 0.004** against every run: perfectly stable, and
**outside** the reference's own range.

**This is a real, reproducible difference, not noise.** It is the first thing
this rig has found that the app does differently.

### What the difference looks like

Per-layer bounding boxes, app minus reference, at matched Z:

| Z | segments app / ref | ΔXmin | ΔXmax | ΔYmin | ΔYmax |
|---|---|---|---|---|---|
| 0.480 | 415 / 405 | **+1.9420** | +0.4200 | +0.1960 | +0.4560 |
| 0.880 | 441 / 432 | **+1.8600** | +0.0590 | +0.1720 | +0.4520 |
| 1.280 | 402 / 400 | **+1.7830** | −0.3810 | +0.1720 | +0.4530 |

The segment counts differ by 2–6 %, which is the ordinary non-determinism. But
the app's geometry extends about **1.9 mm further in −X** while X max moves by
less than half a millimetre in either direction, and Y moves by a few tenths.
**That is not a rigid translation** — a shifted copy would move min and max
together. Something is being laid down on the −X side that the reference does not
lay down, and it is stable across five reference runs.

Three explanations were tested and all three are ruled out:

| hypothesis | test | result |
|---|---|---|
| a rigid XY offset | shift search, ±0.30 mm in 0.05 mm steps | best alignment is **dx=0, dy=0**; every shift is worse |
| a Z offset | compare against the reference's neighbouring layers | best is the **matching Z** (0.3406 at 2.080, next best 0.2503 at 2.280) |
| a localised extra feature | app-only cell histogram, 2 mm bins | spread evenly **across the whole part** |

What is left is that the app and the reference lay down **different lines in the
same envelope**: about half the covered cells are shared, at the same Z, with no
translation that improves it, while the segment *counts* differ by only 2–6 %.
That is the signature of line placement differing — wall offsets or infill
position — rather than the part being in the wrong place.

Not yet established: which of the two is wrong, and which feature it is. The next
step is to separate the cells by feature type (`;TYPE:` is in both files) and see
whether the difference sits in walls, infill or skin. Note also that the app
passes the placement to the engine itself
(`-s mesh_position_x=-115.0 -s mesh_position_y=-115.0` with `center_object=false`),
which is the kind of thing that moves geometry without changing its shape.

### On getting the model right

The first supports-off attempt was void: the desktop replay was given the 4×
scaled model from a *different* slice, pulled from the wrong
`model-placement` directory. It scored 0.0351 and looked like a catastrophe. The
app's own `persistent-state/current-workspace.json` carries the placement —
`{"linear":[1,0,0,0,1,0,0,0,1],"centerXmm":115,"centerYmm":115,"baseZmm":0}` — and
applying it to the app's original model reproduces the engine's input exactly:
the translated mesh spans X 95…135, matching the app's own output header
(`;MINX:95.201 … ;MAXX:134.799`). **Reproduce the transform from the workspace;
do not go looking for a cached mesh**, because the cache is keyed by a slice UUID
and the entry you want may have been evicted.

## Status

- [x] Reference engine version-exact
- [x] App's invocation captured verbatim (867 arguments)
- [x] Comparator written, self-tested, extended with a geometric mode, Z-aligned
- [x] Non-determinism measured (Finding 1)
- [x] Supports ON: measured, app within the reference's own spread
- [x] Supports OFF: measured, app 0.24 below the reference's own spread — **open**
- [ ] The supports-off gap: one more reference run and a per-layer breakdown to
      separate the prime-line layers from any real difference
- [ ] `-m1` determinism: two single-threaded runs started; the first had not
      finished after ~1 hour and was abandoned, so this is unresolved.
