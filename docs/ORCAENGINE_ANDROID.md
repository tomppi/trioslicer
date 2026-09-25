# The OrcaSlicer engine on Android

The third slicing engine in the app, alongside CuraEngine and PrusaSlicer. It ships as
`liborca_console_exec.so` plus the vendor profile tree, and behaves like the desktop product:
the user picks a machine, and the process and filament that machine preselects come with it.

## Why the engine is a driver, not the upstream binary

Upstream OrcaSlicer has one executable, and it cannot be built without the GUI:

- `src/CMakeLists.txt` adds the GUI library only under `SLIC3R_GUI`, but defines the
  `orca-slicer` executable unconditionally, and that executable links `libslic3r_gui`.
- `src/OrcaSlicer.cpp` includes `<wx/stdpaths.h>`, `slic3r/GUI/PartPlate.hpp`, `GLCanvas3D.hpp`
  and `<GLFW/glfw3.h>` outside any `#ifdef`, and its CLI body uses `Slic3r::GUI::PartPlateList`
  and `Slic3r::GUI::GCodeResult` (defined in `src/slic3r/GUI/PartPlate.hpp`).

wxWidgets and OpenGL on Android are not an option, so the port links `libslic3r` alone - the
shape PrusaSlicer's own `prusa-slicer-console` has, and the shape OrcaSlicer's
`OrcaSlicer_profile_validator` already proves works (it links `libslic3r` and no GUI library).
`native/orca/console/orca_console.cpp` is that driver: presets are selected through
`PresetBundle`, the model is read through `Model::read_from_file`, and slicing runs through
`Print::apply`, `Print::process` and `Print::export_gcode`.

Two things libslic3r expects from the GUI build are supplied by the driver instead:

- NanoSVG's implementation is compiled in `slic3r/GUI/BitmapCache.cpp` while
  `Format/svg.cpp` calls into it, so `native/orca/console/nanosvg_impl.cpp` compiles it here.
- The vendor bundles ship under `resources/profiles`, but `load_system_presets_from_json()`
  reads installed presets from `<datadir>/system`. The console links `system -> profiles`, so
  the 79 MB tree is not duplicated on the device.

## Command line

```text
orca-console --datadir DIR [--printer-preset NAME] [--print-preset NAME]
             [--filament-preset NAME] [--printer-config FILE] [--print-config FILE]
             [--filament-config FILE] [-o FILE | --outputdir DIR] [--info]
             [--dump-settings] [--list-presets] model.stl
```

- Named presets are looked up by exact name from the bundled bundles. The lookup is done in
  the driver rather than through `select_preset_by_name()`, which silently falls back to the
  first visible preset and still reports success.
- With only a printer named, the process and filament come from that machine profile's own
  `default_print_profile` and `default_filament_profile` - the same pair the desktop app
  preselects.
- `--dump-settings` writes every print option (name, type, default, enum values, tooltip) as
  JSON. That output is the app's all-settings catalogue and the source of the option names the
  config writer uses.
- Progress is printed as `NN => stage`, the shape all three engines share.

## What ships

| asset | size | why |
|---|---|---|
| `liborca_console_exec.so` | 25 MB | the console, stripped of DWARF (858 MB unstripped) |
| `assets/orca/resources/profiles` | 79 MB | every vendor's machines, processes and filaments |
| `assets/orca/resources/{info,flush,printers}` | < 1 MB | nozzle compatibility, flush volumes, bed definitions |
| `assets/orca/all-settings.json` | 200 KB | the engine's own option catalogue |

The GUI directories (`images/` 35 MB, `fonts/` 35 MB, `hms/` 63 MB, `web/` 22 MB, `shaders/`,
`i18n/`, `dailytip/`, `handy_models/`) are not packaged: nothing in a headless console reads
them.

## Painted supports and Z contouring

Support painting reaches OrcaSlicer inside the model file, because an STL cannot say that one
facet is a support enforcer and another a blocker. When a model carries paint and OrcaSlicer is
the active engine, the slice stages `transformed.3mf` instead of `transformed.stl` and the
request workspace keeps that extension, so the engine picks its 3MF reader. Paint is written as a
`slic3rpe:custom_supports` attribute on each painted triangle — one hex nibble holding the
triangle's serialised TriangleSelector state, `4` for an enforcer and `8` for a blocker, with
unpainted triangles carrying no attribute at all. Painted supports still need support enabled in
the print settings, exactly as they do in OrcaSlicer's own window.

Non-planar slicing is OrcaSlicer's own Z-layer contouring: enabling it sets `zaa_enabled=1` in the
print configuration, which varies Z inside a layer so top-facing surfaces follow the model. The
CurviSlicer relief-field options in the sheet belong to the CuraEngine path and do not apply here;
`zaa_min_z` and `zaa_minimize_perimeter_height` keep OrcaSlicer's defaults unless All settings
overrides them. Conical slicing is refused on this engine: it is the app's own G-code transform,
wired into the CuraEngine pipeline.

## G-code dialect

OrcaSlicer is a PrusaSlicer fork,, so the estimated-time and filament footer comments are the
same text. The layer marker depends on **which vendor is being sliced**: the engine picks the
envelope in `GCode.cpp:4619-4622`.

| marker | non-Bambu vendor | Bambu Lab printer |
|---|---|---|
| layer change | `;LAYER_CHANGE` | `; CHANGE_LAYER` |
| layer Z | `;Z:` | `; Z_HEIGHT:` |
| layer height | `;HEIGHT:` | `; LAYER_HEIGHT:` |

`GcodeDialect.ORCA` accepts either spelling, because both reach the app from this engine.
The vendor also decides the rest of the envelope: a Bambu Lab printer additionally gets the
M981 spaghetti-detector pair and the M624/M625 handling, which is why the engine's
`is_BBL_printer` flag has to be set (see below).

There are no `;FEATURE:`/`;TYPE:` comments by default, so anything the app derives from
feature labels has no Orca input; the app's own layer segmentation reads the markers above.

### The one flag libslic3r leaves to its caller

`bool m_isBBLPrinter;` is declared without an initialiser, and libslic3r never assigns it. The
upstream CLI sets it from the selected vendor (`OrcaSlicer.cpp:6059`), the GUI from its preset
bundle (`BackgroundSlicingProcess.cpp:199`). A driver that does not set it slices with an
undefined value - the first device run took the Bambu path for a Creality printer and emitted
`M981 S1 P20000 ;open spaghetti detector` and `M624`, which the app's G-code safety gate
rightly refused. `native/orca/console/orca_console.cpp` sets it from the bundle before
`Print::apply`.

## Building

```bash
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/28.2.13676358
export ORCA_SRC=$PWD/.build/orca-src

scripts/build-orca-deps-android.sh      # ~1 h on 6 cores; cached by CI on the script hash
scripts/build-orca-engine-android.sh    # patches, configures, builds, strips, stages
scripts/stage-orca-resources.sh         # the profile tree into the app assets
```

The dependency bundle is OrcaSlicer's own `deps/` project with `-DSLIC3R_GUI=OFF`; the tracked
patches in `native/orca/patches/` carry the five things the Android toolchain needs:

1. ABI, platform, STL and the dependency prefix repeated for every ExternalProject, prepended
   to its `CMAKE_ARGS` (cmake_parse_arguments folds `DEPENDS` into that list, and anything
   after it is read as a dependency).
2. `CMAKE_FIND_ROOT_PATH` set to the prefix: the NDK toolchain sets `CMAKE_SYSROOT`, which
   makes every `find_package` search only inside the sysroot.
3. GMP and MPFR built with the target-prefixed NDK driver, GMP without assembly, MPFR without
   maintainer mode, and both with their shipped manuals left alone (makeinfo is not a build
   dependency).
4. OpenSSL configured through `ANDROID_NDK` with the NDK toolchain on PATH, because its
   `android-*` configurations resolve their own compiler.
5. FreeType built against the bundle's zlib (the recipe only treats "Linux" as using system
   zlib, and the bundled gzip copy collides with `libz.a` at link time), plus `dep_JPEG` added
   to the aggregate - upstream computes `JPEG_PKG` and never lists it.

## Gates

`:app:verifyOrcaEngineExecutable` (non-empty AArch64 ELF), `:app:verifyDebugApkOrcaContents`
and `:app:verifyReleaseApkEngines` (the console plus `profiles/Creality.json` and the settings
catalogue inside the APK), and the `assemble*` gate in `app/build.gradle.kts`.
