# PrusaSlicer 3.0.0-alpha11

- **App side**: TrioSlicer 1.7.0, PrusaSlicer route, engine
  `libprusa_slicer_exec.so`.
- **Reference**: the released PrusaSlicer 3.0.0-alpha11, confirmed
  `PrusaSlicer-3.0.0-alpha11+flathub.org`.

Prusa moved Linux distribution to Flathub, and the GitHub release carries only a
`.dmg` and a Windows `.zip`. Flathub beta has since moved to 3.0.0-alpha12, so
the reference is installed **by commit** — `acc125b7…`, the alpha11 commit,
still present in the remote's history — and pinned, so an unrelated update
cannot silently change the reference mid-test.

## How the app invokes it

From the app's own log:

```
libprusa_slicer_exec.so --datadir <app files>/prusa/datadir \
  --load <request>/prusa-config.json --export-gcode \
  -o <request>/output.gcode <request>/model.stl
```

The `--load` file is JSON written by the app's `PrusaConfigWriter`, and the
`--datadir` is the app's own copy of the bundled PrusaSlicer resources — both
reproducible on the reference side, which is what makes this comparison exact.

## `top_one_perimeter_type`: resolved, and it is not affecting the output

The app's slice log repeats, for every preset evaluation:

```
[error] [PresetEvaluator.cpp:312] Invalid key top_one_perimeter_type for Slic3r::Domain::ToolPrintSettings
```

The key is in the app's own `prusa-config.json` (once), and PrusaSlicer
3.0.0-alpha11 does not recognise it. **The released PrusaSlicer, given the same
config, logs the identical error 80 times.** So it is the same on both sides and
cannot explain any difference — which the 1.0000 result confirms.

It is still worth cleaning up on the app's side: it is a setting the app writes
that the engine silently discards, so whatever it was meant to control is not
being controlled. But it is a dead setting in the config writer, not a mis-slice.

## Unlike Orca, this route is command-line comparable

The released PrusaSlicer offers exactly the options the app uses:

```
--load FILE                  Load configuration from the specified single JSON file
--datadir ABCD               Load and store settings at the given directory
-g, --export-gcode, --gcode  Slice the model and export toolpaths as G-code
```

and the app's runner describes itself as the *"bundled PrusaSlicer 3.0.0-alpha11
console"* — not, as Orca's runner says of its own engine, *"not the upstream
executable"*. So the same replay that worked for Cura should work here.

## Captured: a supports-off slice, in flight

| file | size |
|---|---|
| `model.stl` | 25 684 B, X 95…135, Y 95…135, Z 0…15 |
| `prusa-config.json` | 17 163 B — the app's own `--load` configuration |
| `output.gcode` | 3 669 051 B — the engine's output |

The app's Prusa sheet currently has `supportMaterial = false`, hence supports
off. This slice passed **no** vendor preset — the log has no preset line — so it
is the app's own settings against `prusa3-base.json` and the bundled resources.

## Result: supports OFF — the app matches the reference

The replay runs the app's **own** `prusa-config.json` through the released
PrusaSlicer against the app's **own** `prusa/datadir` (17 MB, 756 files, pulled
from the phone). Both sides therefore read the same configuration and the same
resources.

| comparison | output size | mean per-layer IoU | worst layer |
|---|---|---|---|
| **control** — reference vs reference | 3 670 032 / 3 670 032 B | **1.0000** | 1.0000 |
| **test** — app vs reference | 3 669 051 / 3 670 032 B | **0.9992** | 0.9823 |

74 of 74 layers matched by Z. **The app's Prusa output is 99.92 % identical to the
released PrusaSlicer's, layer by layer** — the closest agreement measured on any
engine in this exercise.

The control is worth noting on its own: **PrusaSlicer reproduces itself
exactly** — identical output size, every layer at 1.0000 — where CuraEngine at
`-m8` manages only 0.8213. The threading problem characterised in
[cura.md](cura.md) is a CuraEngine problem, not a general one.

At the segment level, 62 510 of 64 580 segments (96.8 %) are found in both files,
and the total tool-path length agrees to **0.008 %** (56 940.53 vs 56 936.12 mm).
About 3 % of the segments are decomposed differently — the same geometry cut into
moves at different points — which is what seam placement and arc handling do.

### A gap this exposed in the comparator

PrusaSlicer does not write `;LAYER:n`. It writes `;LAYER_CHANGE` and `;Z:`, so
`scripts/compare-gcode.py` saw this file as **one layer** and the per-layer table
above is really a whole-file figure. The coverage result stands — 1.0000 over the
whole file is a stronger statement than 1.0000 per layer — but a per-layer
breakdown for this engine needs the parser taught PrusaSlicer's layer markers.
Recorded rather than glossed over.

## Result: supports ON

Same treatment, with `support_material` switched on through the app's own UI
(the Prusa sheet's "Support material → Enable supports"):

| | value |
|---|---|
| layers | 76 vs 76, all matched by Z |
| **mean per-layer IoU** | **0.9696** |
| worst layer | 0.0000 (one layer) |
| tool-path length | 101 592.99 vs 101 593.22 mm — **0.0002 % apart** |
| segments found in both | 94 944 of 101 221 (**93.8 %**) |

The path-length agreement is the notable figure: **0.23 mm of difference across
101.6 metres of tool path.** The single layer at 0.0000 is the same prime-line
layer effect seen on the Cura route — the app emits the start G-code's prime line
inside its own layer block, so one layer has no counterpart at any Z.

Here the supports *do* match, unlike Cura's. That is consistent with everything
else: CuraEngine's tree support is chaotic (proven in [cura.md](cura.md) by
re-running the app's own engine on the same phone), while PrusaSlicer produces
support that reproduces.

## Summary for this engine

| configuration | mean per-layer IoU | notes |
|---|---|---|
| supports OFF | **0.9992** | control 1.0000 |
| supports ON | **0.9696** | path length within 0.0002 % |

**PrusaSlicer is the cleanest result in this exercise**, and the reason is
instructive: the reference reproduces itself *exactly* (control 1.0000, identical
output sizes) and the app lands within 0.03 % of it in the worst case. Nothing
here depends on the threading behaviour that makes CuraEngine irreproducible.

## Status

- [x] Reference installed and version-exact, pinned by commit
- [x] Confirmed the released binary accepts the app's options
- [x] Comparator taught PrusaSlicer's `;LAYER_CHANGE` / `;Z:` markers
- [x] Supports OFF: compared — **0.9992**, control 1.0000
- [x] Supports ON: compared — **0.9696**, path length within 0.0002 %
- [x] `top_one_perimeter_type`: same error on both sides, harmless
