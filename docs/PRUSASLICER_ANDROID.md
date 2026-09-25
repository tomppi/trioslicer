# PrusaSlicer Android integration

The APK ships the PrusaSlicer 3.0.0-alpha11 console for Android and runs it as a
child process. It is packaged as `libprusa_slicer_exec.so` in the APK's
`arm64-v8a` jniLibs and executed directly; there is no JNI boundary.
`PrusaEngineRunner` stages a workspace, `PrusaConfigWriter` renders the
configuration the console loads, and the console's own resources are extracted
next to it on first use.

## Console contract

The 3.0 console takes an already-resolved configuration - its whole help text is

```
usage: prusa-slicer --datadir DIR --load config.json --export-gcode -o out.gcode model.stl
```

`--load` reads the same format `--save` writes: `{preset, configuration}`,
where `preset.hw_config` names the hardware and `configuration` carries the
resolved per-bucket values (`print_settings`, `printer_settings`,
`filament_settings`, `toolprint_settings`, `project_settings`). The types are
3.x types: percentages are `{value, is_percent}` objects, filament and tool
values are per-slot arrays, bed shapes and offsets are point lists, and machine
limits are per-slot arrays too.

`app/src/main/assets/prusa3-base.json` is a configuration the real
PrusaSlicer 3.0 saved for a Prusa MK4 with a 0.4 nozzle: 222 print, 65 printer
and 68 filament resolved values. Every slice starts from it, the app's own
values are layered over it, and - when the user picks them - so are the vendor
presets (below). It doubles as the fidelity oracle for the preset resolver.

## The vendor preset repository

`scripts/fetch-prusa-engine-android.sh` stages the console's own resources into
`app/src/main/assets/prusa/resources` (387 files, 8.7 MB): the
`prusa-research-fff` repository with 578 YAML documents - 11 printer, 13 print,
288 filament, 32 hardware configurations, plus tools, sheets, feeders and the
shared presets they inherit from.

That repository is not a flat profile tree. A preset's values sit behind
conditions on the hardware, and one printer document covers every model and
nozzle through nested variants; the user-facing quality profiles are *named
variants* inside those trees (`0.20mm SPEED @MK4 0.4`). The console does no
resolution for us, so the app does it, and the YAML never reaches the APK:
`scripts/prusa-presets-to-json.py` converts it to
`app/src/main/assets/prusa-presets.json` (2.1 MB) with PyYAML at fetch time.

### What the app resolves

- `PrusaPresetCatalog` offers 32 machines - one per `printer_config` document
  (`Prusa MK4 0.4`, `Prusa CORE One 0.4`, `Prusa XL 2T 0.4`, the MMU variants,
  ...). A machine states its model, its tool (including high-flow nozzles, which
  the bundle writes as `0.4HF`), its sheet and its feeder, and that is the
  context the conditions are evaluated against.
- `PrusaPresetCondition` parses and evaluates the bundle's condition language -
  comparisons, `=~` / `!~` regular expressions, `and` / `or` / `!`,
  parentheses, and bare lookups that ask whether a hardware feature is on. The
  138 distinct conditions the bundle ships all parse, and
  `PrusaPresetCatalogSnapshotTest` pins that count.
- Resolution follows PrusaSlicer's own evaluator: a preset starts from the
  documents it inherits, then applies the branches whose conditions hold, in
  order, nested branches after their parent's values. A branch list is
  first-match - it stops after the first branch that carries a condition and
  holds - unless the document declares `match_mode: all_matches`, which the XL,
  XL+ and CORE One INDX quality trees do. Later values win.
- The XL, XL+ and CORE One INDX quality profiles keep their settings in a *tool
  print*: the profile sets only the layer height and inherits a
  `default_tool_print` pointer, and resolving it also applies the tool print that
  layer height selects (minus the keys the app owns), so the picker's choice
  reaches the slice.
- `PrusaPresetValues` maps the resolved values onto the configuration's shapes,
  using `prusa3-base.json` as the type oracle. Keys the app writes itself
  (`AllSettingsCatalogs.PRUSA_MANAGED_KEYS`) are never taken from a preset, and
  neither are G-code templates, `binary_gcode`, `post_process`, the bundle's
  `default_*` pointers or the printer identity: the machine envelope, start and
  end G-code and every setting the sheet edits stay the app's.

### Fidelity

For the machine the base configuration describes, resolving the same machine out
of the repository has to reproduce it. `PrusaPresetCatalogSnapshotTest` maps
the resolved MK4 0.4 machine, its default quality profile (`0.20mm SPEED @MK4
0.4`) and the generic PLA material, then compares every mapped value with the
snapshot: 24 printer, 95 print and 39 filament values, all equal, counts pinned.
The bundle's own default material for that machine is Prusament PLA, which is
what the picker preselects.

## Painted supports, non-planar and conical

Painted support enforcers and blockers reach PrusaSlicer inside the model file: an STL cannot
express per-facet paint, so a painted model is staged as `transformed.3mf` and the request
workspace keeps that extension. The paint is written as the legacy per-triangle
`slic3rpe:custom_supports` attribute (hex nibble `4` for an enforcer, `8` for a blocker, absent
on unpainted triangles), which this build reads through PrusaSlicer's legacy-painting path;
PrusaSlicer 3.x writes painting as JSON metadata, so a file this app produces is understood
rather than round-tripped by newer PrusaSlicer builds. Supports must be enabled in the print
settings for the paint to matter, as in PrusaSlicer's own window.

Non-planar slicing is refused before the slice starts, with a message naming the engines that can
do it: PrusaSlicer has no Z contouring, and silently slicing flat while the UI says non-planar is
the failure this avoids. Conical slicing is refused for the same reason — it is the app's own
G-code transform, wired into the CuraEngine pipeline.

## The All-settings catalogue

The sheet's "all settings" list reads
`app/src/main/assets/prusa/all-settings.json` through
`AllSettingsCatalogs.prusa`. The OrcaSlicer console writes its own catalogue with
`--dump-settings`; the PrusaSlicer 3.0 launcher has no dump mode. Its whole help
text is the one-line usage stub above: `--help-fff`, `--dump-settings`,
`--help-all` and `--list-presets` all print that stub and exit 0, and the binary
contains no other option string. Nothing on a build host can produce the
catalogue, so it is derived instead:

- `scripts/generate-prusa-all-settings.py` takes the keys, types and defaults
  from `prusa3-base.json`, the fully resolved configuration above: a JSON number
  becomes a float or an int, a `{value, is_percent}` object a percentage or
  FloatOrPercent option, a per-slot array a filament/tool value, and booleans,
  strings and point lists are stated the way the engine writes them. The type
  vocabulary is the OrcaSlicer dump's, so both catalogues are one shape.
- The values the vendor presets state in `prusa-presets.json` become the
  `values` hint beside a key - `fill_pattern` offers the cubic/grid/rectilinear
  the repository uses - for keys whose observed values are choices rather than a
  continuous range.

Neither input can state Prusa tooltips, categories or numeric ranges, so those
fields are absent rather than empty. The file is committed (34 KB): the console
cannot regenerate it, and `checkPrusaSlicerPackaged` fails a debug or release APK
that lost it - the silently empty sheet this used to ship (a reader with no
generator since cc58ef32, with `runCatching` swallowing the missing asset) cannot
come back unnoticed. The fetch script regenerates the file after
`prusa-presets.json` exists, so refreshing the resources cannot leave it stale.

## Checks

- `:app:testDebugUnitTest` covers the catalogue, the condition language, the
  value mapping and the snapshot fidelity. `PrusaAllSettingsCatalogTest` fails
  when the derived catalogue is empty or no longer matches `prusa3-base.json`.
- `:app:verifyDebugApkPrusaContents` (and `:app:verifyDebugApkEngines`) fails
  an APK that is missing the console, its resources, the preset catalogue or the
  derived All-settings catalogue.
