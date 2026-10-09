# Multi-object printing

The build plate holds several models. Each one is a `PlateObject`: the mesh as imported, the same
mesh with its placement applied, its file path, its support paint, and a stable id. The tools -
gizmo, drag, paint brush, transform panel, placement undo - all act on the *selected* object, and
`MainUiState.mesh` / `modelPath` / `modelPlacement` / `supportPaint` answer for that object, which
is why the single-model call sites did not have to be rewritten.

Importing appends. It used to replace - and delete the file behind - whatever was on the plate,
which with several parts would take one of the user's models away without asking.

## Why the app arranges the plate itself

None of the three engines arranges anything for the CLI paths this app uses:

- **CuraEngine** has no arrangement code at all, and never reads its own gantry or head-distance
  settings.
- **PrusaSlicer** 3.0.0-alpha11 ships a hand-written headless console
  (`scripts/build-prusa-engine-android-3.sh` writes `src/slic3r-console-headless/main.cpp`); nothing
  in it calls arrange, and its collision check is commented out
  (`Print.cpp:1442` passes `std::nullopt` where `check_seq_conflict` used to be).
- **OrcaSlicer**'s console loads a model, sets `print_sequence` if asked, and slices; it never calls
  `Print::validate`.

So `PlateArranger` packs the plate (largest first, rows centred, spacing kept to neighbours and to
every bed edge, `null` when it does not fit), and `SequentialPrintCheck` refuses a plate that
one-at-a-time printing would crash into. Both are pure and unit-tested; the app owns this because
nothing downstream would catch it.

## How several objects reach each engine

**One file per object** is staged, with each object's placement already baked into its vertices;
nobody needs a per-object transform.

- **CuraEngine** (`CuraEngineCommand`, `CuraEngineRunner`): a mesh *group* is a whole print unit,
  not a container. `Slice::compute` slices and writes G-code per group, and only group 0 gets the
  machine start sequence - every later group gets `processNextMeshGroupCode` (fan off, z-hop to
  `max_object_height + 5`, travel to the next part). That is sequential printing, and it is exactly
  how Cura's own frontend uses groups (`all_at_once` => one group holding every object;
  `one_at_a_time` => one group per object). So a normal plate is **one group with an `-l` block per
  object**, each object's modifiers right after its own `-l`, and "Print one object at a time"
  inserts `--next` before every object after the first.
  The resolved-profile transport (`-r`) cannot express a group boundary, so one-at-a-time with an
  imported Cura profile is refused for a plate of several objects rather than silently sliced
  all-at-once; one object needs no boundary, so that slice is allowed.
- **PrusaSlicer and OrcaSlicer**: their consoles take exactly ONE positional model path. In the
  Prusa console a second positional argument silently overwrites the first, so several files must
  arrive as one **3MF** (`PlateThreeMfWriter`). Orca reads geometry, `<build><item transform>`
  instances and `Metadata/model_settings.config` per-object settings, and ignores the file's own
  print settings; Prusa 3.0.0-alpha11's new reader drops object metadata unless the file is stamped
  `<metadata name="Application">PrusaSlicer-2.9.6</metadata>`, which routes it to the legacy loader
  that reads `Metadata/Slic3r_PE_model.config`. Both dialects are written and tested.

## Object identity in the G-code

`M486 S<id>` selects object `id` (0-based, one per object), `M486 S-1` clears the selection and
`M486 A<name>` registers a name; Klipper targets get `EXCLUDE_OBJECT_*` instead. The app's
G-code policy used to bound `M486 S` to `-1..1`, from when the plate held one model, which rejected
the third object's marker *after* the engine had already produced good G-code - it now accepts any
object id. The menu's "Label each object in the G-code" passes the engine's own key
(`gcode_label_objects=firmware` for Prusa, `gcode_label_objects` + `exclude_object` for Orca), and
the user's All-settings overrides still win over it.

CuraEngine emits no per-object markers at all, so on the Cura path the app's Klipper
`EXCLUDE_OBJECT_DEFINE` remains the whole plate as one object; per-object cancellation needs
PrusaSlicer or OrcaSlicer.

## Sequential printing

The engine key is `complete_objects` (Prusa) and `print_sequence` (`by object` for Orca,
`one_at_a_time` for Cura); there is no `print_sequence` key in PrusaSlicer. Because no engine
validates clearance, `SequentialPrintCheck` refuses a plate where parts touch or overlap, where the
head's footprint would sweep through a finished part, or where a part is taller than
`gantryHeightMm` while something would be printed after it.

## The workspace

`WorkspaceStateStore` keeps a `models` list beside the single-model fields it has always written,
so a descriptor saved by an older build still restores as a one-model plate. The multi-object
preferences (`PlatePreferences`: placement mode, gap, sequential, object labels) are
SharedPreferences - deliberately not `SlicerSettings`, because that feeds the workspace fingerprint
and toggling auto-arrange must not invalidate a saved workspace.

## CAD and Blender handoff

Both engines write their exports into a directory the app watches, and every file the watcher sees
is imported as its own plate object, so exporting several parts from either engine puts several
objects on the plate for auto-arrangement. Each screen also has an **All to plate** action:

- CAD: `CadPreviewClient.exportEveryShape` runs an `execute_code` script over the scene's named
  shapes, writing each to its own STL through a temporary name and renaming it into place (the
  rename is what publishes a file the importer will accept). Verified against the device's own
  build123d: three shapes out, exact triangle counts, no `.part` files left behind.
- Blender: `EnginePreviewClient.exportEveryObject` writes one binary STL per mesh object. The
  addon's own `_mesh_to_binary_stl` writes *mesh-local* vertices, which for several parts would
  drop every one of them at the origin with the layout lost, so the app's script applies
  `object.matrix_world` itself.

## What has been verified on hardware

- **CuraEngine, a support blocker**: painted blockers remove support. This path does not ask the
  engine to understand paint at all - the app turns the painted facets into modifier volumes
  (`anti_overhang_mesh`) and adds them to the command.
- **PrusaSlicer, a support blocker**: painted blockers remove support. Different route: there are
  no modifier volumes, the paint rides inside the 3MF as a per-triangle attribute, and the file
  reaches the only reader that understands it - the legacy loader - through its
  `Application: PrusaSlicer-2.9.6` stamp. This confirms the stamp, the attribute name
  (`slic3rpe:custom_supports`), the per-triangle values and the volume range in practice.
- **OrcaSlicer, a support blocker**: painted blockers remove support. Its console reads the same
  values under `paint_supports` - run against the 2.4.2 console, an app-style file round-trips its
  paint and the same file under the Prusa attribute round-trips with none.

All three engines are therefore confirmed on hardware, by two different routes: the app either
synthesises modifier volumes the engine never has to understand (CuraEngine), or hands it an
attribute only its own loader reads and its own support generator acts on (both Slic3r forks).

Everything else claimed above was exercised on the device while the feature was built: several
objects sliced by all three engines, one-at-a-time G-code from CuraEngine, the clearance refusal,
arrangement and its persistence, the batch handoff, and the CAD and Blender exports.
