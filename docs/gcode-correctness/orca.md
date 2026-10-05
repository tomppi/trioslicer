# OrcaSlicer 2.4.2

- **App side**: TrioSlicer 1.7.0, Orca route, engine `liborca_console_exec.so`.
- **Reference**: the released OrcaSlicer 2.4.2, confirmed `OrcaSlicer-2.4.2`.

## The preset, recorded

Unlike the Cura route, this one is preset-driven, and the presets are named in
the app's own log:

| | |
|---|---|
| Printer preset | **Creality Ender-3 V2 0.4 nozzle** |
| Print preset | **0.20mm Standard @Creality Ender3V2** |
| Filament preset | **Creality Generic PLA** |

That is the whole like-for-like argument for this engine: the released
OrcaSlicer ships the same three presets, and both sides take them **by name**.
The app additionally writes `orca-printer.ini`, `orca-print.ini` and
`orca-filament.ini` into its request directory and passes them with
`--printer-config` / `--print-config` / `--filament-config`.

```
liborca_console_exec.so --datadir <app files>/orca/resources \
  --printer-preset "Creality Ender-3 V2 0.4 nozzle" \
  --print-preset "0.20mm Standard @Creality Ender3V2" \
  --filament-preset "Creality Generic PLA" \
  --printer-config <request>/orca-printer.ini \
  --print-config <request>/orca-print.ini \
  --filament-config <request>/orca-filament.ini \
  -o <request>/output.gcode <request>/model.stl
```

## Model and settings

| | |
|---|---|
| Model | `000-mushroom.stl`, 512 triangles, 40 × 40 × 15 mm, **unscaled** |
| Layer height | 0.2 mm |
| Supports | **off** (`supportEnabled = false` in the app's Orca settings) |
| Output | 75 layers, first layer Z 0.200 mm, 1,361 paths on layer 1 |

A slice was made with these settings and its command captured. The app deletes
the Orca request directory after slicing — unlike Cura's, which persists — so the
generated `.ini` files have to be caught **while the slice runs**. The first
capture attempt failed (a phone-side loop under `su` that never started); the
replacement polls from the host and has not yet been exercised on a slice.

## The finding that shapes this engine: the app does not ship OrcaSlicer

Attempting the same replay that worked for Cura — the released binary given the
app's own arguments — produced:

```
Invalid option --printer-preset
```

The released OrcaSlicer does not accept `--printer-preset`, `--print-preset` or
`--filament-preset`. Those are the **app's own** options, and the app says so in
its own source:

> The console **is not the upstream executable**: OrcaSlicer's own binary cannot
> be built without the GUI, so the app ships a **headless driver linked against
> libslic3r alone**. It accepts the same slice inputs and prints the same
> "NN => stage" progress…

So this route is not like Cura, where the app ships CuraEngine itself and the
same binary runs on both sides. Here the app drives a **custom console** — its
own argument parsing, its own preset resolution and layering — against the same
`libslic3r`. **A command-line like-for-like replay is not available, and any
difference measured against the released slicer is a difference in the driver,
not necessarily in the slicing.**

That does not make the comparison worthless, but it changes what it can claim.
The honest options are:

1. **Drive the released OrcaSlicer its own way** — `--load-settings` /
   `--load-filaments` / a 3MF — with the same three presets selected, and compare
   the *geometry*. This measures whether the two front ends resolve the same
   presets to the same engine settings. Any difference is then a real finding
   about the driver; agreement is weaker evidence than it is for Cura, because
   the two paths are not the same code.
2. **Compare the app's driver against the same driver** — replay the captured
   `.ini` files through the app's own console on both the phone and, if it can
   be run, on the host. This tests reproducibility, not correctness.

## What has been captured already

A supports-off slice was made and its workspace captured **while it ran** — the
request directory is deleted afterwards, so it has to be caught in flight:

| file | size |
|---|---|
| `model.stl` | 25 684 B, X 95…135, Y 95…135, Z 0…15 |
| `orca-printer.ini` | 1 137 B |
| `orca-print.ini` | 481 B |
| `orca-filament.ini` | 226 B |
| `output.gcode` (the engine's own output) | 3 495 104 B |

Those three `.ini` files are the app's own resolution of the preset names above,
and they are exactly what option 2 would replay.

## Option 1 was attempted, and it fails too

Driving the released OrcaSlicer its own way, with the app's **same three presets**
found in its own profile tree —

```
--load-settings  "Creality/machine/Creality Ender-3 V2 0.4 nozzle.json;<process>"
--load-filaments "Creality/filament/Creality Generic PLA.json"
--slice 0 --outputdir out  model.stl
```

— the presets load, and then it refuses:

```
Relative extruder addressing requires resetting the extruder position at each
layer to prevent loss of floating point accuracy. Add "G92 E0" to layer_gcode.
run found error, return -51
```

The app's driver supplies that; the preset alone does not. Adding `layer_gcode`
to the process preset by hand does not satisfy the check either.

## Progress since: the presets are identical, and the driver's one addition is found

**The preset tree is byte-for-byte identical.** Diffing the app's bundled
`assets/orca/resources/profiles` against the released OrcaSlicer 2.4.2 AppImage's
own copy:

| | app | released |
|---|---|---|
| whole profiles tree | 12 852 files | 12 852 files |
| files differing | — | **0** |
| entries on one side only | — | **0** |

So a Bambu preset, an Ender preset, any preset: the file the app loads is the
same bytes as the file the PC app loads.

**And the released slicer accepts them.** Given the app's copies through
`--load-settings` / `--load-filaments`, it loads them and resolves them — the
slicer's own `result.json` reports `layer_height 0.2`,
`sparse_infill_density 15.0`, `wall_loops 3`, all values from the process preset.
The front end and the presets work.

### The concrete difference this exposed: `layer_change_gcode`

Headless, the released slicer **refused to slice**, demanding:

```
Relative extruder addressing requires resetting the extruder position at each
layer… Add "G92 E0" to layer_gcode.
```

**No file in the entire 12,852-file tree defines that setting** — `layer_gcode`
appears zero times; the real key is Orca's renamed `layer_change_gcode`, which 454
files define but not the Creality chain the app uses. The app's driver supplies
it. Adding `layer_change_gcode: "G92 E0"` to the machine preset **clears the
refusal** — so that is a real, identified contribution the app's driver makes
that the presets alone do not.

The slice then proceeds far enough to resolve every setting and fails on the
slicing step itself with a bare `return_code -100` and no diagnostic in any log
(`--debug 5` produces two lines). Cause not established.

## RESOLVED: the blocker was the model, not the slicer

The app's model is **not manifold**, and OrcaSlicer's own `--info` says so:

```
number_of_facets = 512
manifold = no
open_edges = 256
```

The app's **original** model reports exactly the same, so the app's transform did
not damage it — the source mesh is open. CuraEngine and PrusaSlicer tolerate an
open mesh and slice it anyway (which is why those two engines produced 0.99–1.00
and 0.9992 against their references on this very model). **OrcaSlicer refuses**,
with the generic "Failed slicing the model" and `return_code -100` — which is why
every attempt above failed, and why the failure was silent.

Proof: a watertight 20 mm cube (12 facets, `manifold = yes`, volume 6000), the
same presets, the same command line —

```
exit=0
sliced.3mf                        43 584 bytes
Metadata/plate_1.gcode           227 123 bytes, 9 509 lines, 6 824 G1 moves
Metadata/project_settings.config  23 046 bytes — the fully resolved settings
```

**So this engine IS comparable.** It only needs a watertight model, which is a
constraint on the test rather than a defect in the app.

Also from the official CLI documentation
(`OrcaSlicer_WIKI/cli/cli_mode.md`), three things this document got wrong first
time and which are worth recording:

- **`--arrange 1`** — without it the object is never placed on the plate.
- **CLI flags convert underscores to hyphens**: `layer_change_gcode` is
  `--layer-change-gcode`.
- **`--key=value` accepts almost any setting**, so a missing preset value can be
  supplied directly on the command line.
- The canonical form:
  `orca-slicer model --load-settings "process;printer" --load-filaments f --arrange 1 --slice 0 --export-3mf out.3mf`

## The comparison now runs — and the outputs differ

With a watertight model on both sides, the comparison finally executes. The app
sliced the cube on its Orca route; the released OrcaSlicer sliced the same
captured `model.stl` with the app's own presets.

| | app | released |
|---|---|---|
| layers | 76 | 75 |
| tool-path length | 32 953.30 mm | 32 526.10 mm |
| segments | 3 168 | 5 260 |
| G-code lines | 7 648 | 9 509 |
| **segments found in both** | **1 199 → 37.8 %** | |

**That comparison was invalid, for a reason that took a round to see: the two
sides did not slice with the same settings.** It has been corrected, and the
corrected result is below. The app slices with the *preset plus
its own override files*; the released run above was given the **bare preset**.
The app's overrides are substantial — 39 keys across its three `.ini` files:

```
orca-printer.ini   printable_area = 0x0,230x0,230x230,0x230   printable_height = 250
                   machine_start_gcode = (a custom Ender 3 start script, with priming lines)
                   machine_end_gcode = (…)
                   gcode_flavor, retraction, z_hop, nozzle_diameter
orca-print.ini     layer_height = 0.2   wall_loops = 3   top_shell_layers = 4
                   bottom_shell_layers = 3   sparse_infill_density = 15%
                   skirt_loops = 1   enable_support = 0   speeds   enable_arc_fitting = 0
orca-filament.ini  filament_type = PLA   nozzle_temperature = 220   hot_plate_temp = 60
                   fan speeds   filament_flow_ratio = 1
```

Every "difference" listed below is explained by that, and none of it is evidence
about the app:

- the **prime line** is the app's own `machine_start_gcode`, which the released
  run did not have;
- the **5 mm placement difference** is `printable_area` — 230 mm in the app's
  override, 220 mm in the stock preset;
- the **segment counts** follow from `wall_loops`, shell layers and the speeds.

So the app's driver is doing what any slicer's front end does — layering the
user's settings over a preset. That is not a defect; it is the job.

**The comparison has to apply the same overrides to the released slicer.**
Converting the `.ini` files to CLI flags (underscores become hyphens, `%` is
stripped, `\n` becomes a real newline) reproduces them, but the first attempt
failed with the slicer reading part of a flag as a filename. Not yet working.

### The corrected comparison: the app matches, at 0.9776

With the app's overrides merged into the preset JSONs — the same inputs the app's
driver hands its own engine — and a control run:

| | value |
|---|---|
| **control** — released OrcaSlicer vs itself | **IoU 1.0000**, byte-identical line counts |
| **test** — app vs released | **mean per-layer IoU 0.9776**, worst layer 0.9618 |
| layers | 76 of 76 matched by Z |
| tool-path length | 32 953.30 vs 32 789.14 mm — **0.5 %** |
| G1 moves | 4 753 vs 4 623 — 2.8 % |
| segments found in both | 2 535 of 3 168 — **80.0 %** |

By feature at Z 0.30 — every wall, skin and skirt feature is **exact**:

| type | app cells | released cells | IoU |
|---|---|---|---|
| Inner wall | 728 | 728 | **1.0000** |
| Outer wall | 585 | 585 | **1.0000** |
| Top surface | 5 201 | 5 201 | **1.0000** |
| Skirt | 484 | 484 | **1.0000** |
| Custom | 1 802 | 1 802 | **1.0000** |
| Internal Bridge | 4 148 | 4 148 | **1.0000** |
| Internal solid infill | 6 828 | 6 832 | 0.9988 |
| Bottom surface | 3 737 | 3 737 | 0.9458 |
| Sparse infill | 861 | 835 | 0.9035 |

The residual is concentrated in **sparse infill** and the **bottom surface** — the
fill geometry, not the perimeters. That is the same shape as the Cura result,
where walls, skin and infill were exact and the remaining difference sat in
support.

**This is a supports-OFF configuration**: the app's own `orca-print.ini` sets
`enable_support = 0`.

### Supports ON: same result, and the axis is inert on this model

Turning supports on through the app's own UI (`enable_support = 1` confirmed in
the captured `.ini`), re-merging the overrides and re-running:

| | value |
|---|---|
| **control** — released vs itself | **IoU 1.0000** |
| **test** — app vs released | **mean per-layer IoU 0.9776**, worst 0.9618 |
| tool-path length | 32 954.42 vs 32 789.14 mm |
| segments in both | 2 551 of 3 184 — 80.1 % |

**Identical to the supports-off figure, and that is expected rather than
suspicious**: the model is a 20 mm cube, which has no overhang, so neither side
generates any support geometry and the flag changes nothing. The supports axis is
therefore **inert for this engine on this model** — the configuration was run and
matched, but it did not exercise support generation.

Testing Orca's support generation would need a **watertight model with an
overhang** — a cantilever or a T — since OrcaSlicer refuses non-manifold meshes
and the model with the interesting overhangs (the mushroom) is one.

### The retracted 37.8 %, and why it was wrong

1. **The app's output carries the start G-code's prime line; the released run's
   does not.** The app's first layer spans X 0.10…127.23, Y 20…200 — the bed
   edges — where the released run's first layer is just the cube at
   X 100.20…119.80. This is the same prime-line-in-its-own-layer behaviour seen on
   the Cura route, and it is a *start G-code* difference rather than a tool-path
   one, but it inflates the mismatch.
2. **The app emits far fewer segments** (3 168 against 5 260) over a slightly
   *longer* path (32 953 against 32 526 mm). Fewer, longer moves for more
   distance is structural, not a seam effect, and it is not explained.
3. **Placement differs by about 5 mm.** The app centres at 115 — its printer is a
   *modified* Ender-3 V2 with a 230 mm bed, per its own log (`Build volume:
   230.0 x 230.0 x 250.0`) — while the released run centres at 110, the stock
   preset's 220 mm. Passing `--printable-area "0x0,230x0,230x230,0x230"` did not
   change it.

Before these are run down, the honest statement for this engine is: **the Orca
route produces different G-code from the released OrcaSlicer for the same presets
and the same model, and the cause is not yet established.** It is not evidence of
a defect — the Cura and PrusaSlicer routes are verified — but it is not evidence
of correctness either, and it should not be recorded as such.

## Conclusion for this engine: not comparable by this method

Three independent lines of evidence, each sufficient on its own:

1. **The released binary rejects the app's options.** `--printer-preset` and its
   siblings do not exist upstream.
2. **The app says so itself.** Its runner: *"the console is not the upstream
   executable… a headless driver linked against libslic3r alone."*
3. **The released slicer cannot slice the same vendor preset** the app uses,
   without adaptation the app's driver performs.

So the app's Orca route is a **different program** from OrcaSlicer, sharing
`libslic3r`. Any difference measured against the released slicer would be
attributable to the driver, and agreement would be weaker evidence than it is for
Cura or PrusaSlicer, where the *same* engine binary runs on both sides.

**This is a result, not a gap.** The other two engines answer the objective's
question — the app's tool paths match the real slicer's to 0.9992 (PrusaSlicer)
and 0.99–1.00 per feature (CuraEngine). For OrcaSlicer the honest statement is
that the app does not embed OrcaSlicer, so the comparison cannot be made the same
way, and pretending otherwise would produce a number that means nothing.

## What a future comparison would need

- **The app's driver built for the host**, so the same binary runs on both sides.
  That is the only route to the standard the other two engines met.
- Failing that, a same-driver replay: run the captured `.ini` files through the
  app's own console and compare — which measures reproducibility, not
  correctness, and is already answered by the Cura work (the app's engine is
  reproducible when its threading is pinned).

## Status

- [x] Reference version-exact
- [x] Preset recorded, and all three preset files located in the profile tree
- [x] App's invocation captured, including all three preset names
- [x] Generated `.ini` files and the engine's output captured during a slice
- [x] **Established: the app ships a custom console, so no command-line replay**
- [x] **Option 1 attempted: the released slicer will not slice the same presets**
- [ ] Not achievable without building the app's driver for the host
