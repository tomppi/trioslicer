# Profile and filament management

TrioSlicer stores two independent kinds of named user presets, and every preset belongs to one slicing
engine:

- **Print profiles** contain quality, layer-height, wall, seam, infill, speed, support, travel, adhesion,
  arc-overhang and ironing settings.
- **Filament profiles** contain filament diameter, nozzle and bed temperatures, flow, cooling,
  retraction, Z-hop, firmware-retraction and coasting settings.

Each engine captures, stores and applies only the settings it actually reads:

| Engine | Print profile | Filament profile |
| --- | --- | --- |
| Cura | the categories above, from `SlicerSettings` | the categories above, from `SlicerSettings` |
| PrusaSlicer | layer heights, extrusion widths, perimeters, infill, skirt and brim, support, print speeds | nozzle and bed temperatures, cooling, retraction, extrusion multiplier |
| OrcaSlicer | layer heights, walls, sparse infill, skirt and brim, support, print speeds | nozzle and plate temperatures, cooling, flow ratio, retraction, Z-hop |

Printer dimensions, nozzle size, G-code flavor, printhead geometry, custom start/end G-code and the
selected vendor presets remain machine settings for all three engines. Applying a filament therefore
cannot replace machine geometry or print-quality choices, and applying a print profile cannot replace the
selected filament's temperatures, flow or retraction behavior.

## Engine scoping

The three engines do not share a settings vocabulary, so a preset is scoped to the engine it was saved
for:

- the sheet lists the active engine's presets only, and names them in its header;
- names are unique within an engine and a kind, and the limit of 100 print profiles and 100 filament
  profiles is counted per engine;
- each engine keeps its own active print preset and its own active filament preset, so switching engines
  neither hides nor re-points the other engine's selection;
- applying a preset whose engine is not active is refused with a note to switch back, because it would
  rewrite settings the running engine's slice never reads.

## Using presets

Open **Profiles & filament** from the **More** tab (the first row of *Configuration*). Select **Print
profiles** or **Filaments** and then:

1. Adjust the normal slicer settings.
2. Select **Save current as…**.
3. Enter a name for the new preset.
4. Apply a saved preset at any time.

The selected preset is marked active. Further settings changes mark it **Active · modified**. **Save
changes** replaces that preset's stored category values. **Rename** changes only its display name.
**Delete** removes the saved preset but deliberately leaves the current slicer settings unchanged.

When switching away from modified settings, the app offers three choices:

- discard the unsaved category changes and apply the selected preset;
- save the active preset first and then apply the selected preset;
- cancel the switch.

## Persistence

Named presets use each engine's own settings lifecycle. Applying a preset is one settings update, it
invalidates stale G-code and layer previews, persists the resulting settings, and keeps the imported
baseline and dependency-resolution behavior intact - Cura's `SlicerSettings` override lifecycle,
`PrusaSliceSettings`, and `OrcaSliceSettings` respectively.

The preset library is stored as one versioned private JSON document under the app's persistent-state
directory. Writes use a staged file and rollback copy. Every record carries its engine; a record written
before 1.3.6 has no engine field and loads as Cura's, which is what it was, and the document is rewritten
in the current format on the next save. Names are normalized, limited to 60 characters and unique within
their engine and kind.

Older partial presets remain usable if a later release adds settings. Only recognized values with the
expected primitive type are applied; values that are absent from the older preset remain unchanged.
Saving changes upgrades the preset to a complete snapshot for the current release.

## Safety behavior

- Applying a preset never changes settings outside its engine and category.
- PrusaSlicer and OrcaSlicer preset values pass the same gate as Cura's: a recognized key of that
  engine's category, the expected primitive type, a numeric range, and - where the engine enumerates
  them - a known fill, infill or support pattern. An "automatic" (null) extrusion width is a value a
  PrusaSlicer preset stores and restores like any other.
- A value that is out of range, of the wrong type, null where the engine has no null, or an unknown
  pattern is rejected before any live setting changes, and the preset is not applied.
- Applying a preset invalidates previously sliced G-code before it can be exported or uploaded.
- Repeated save/apply/rename/delete taps are serialized.
- Coroutine cancellation is not presented as a false storage error when the sheet closes.
- Corrupt records, missing active IDs and presets without any usable values are ignored or rejected.
- Optimized builds retain the stable `SlicerSettings` backing-field names used by preset serialization.
- Deleting an active preset clears its active marker without resetting the current settings.

## Current limitation

Presets are local to the app. Import/export of the named preset library and cloud synchronization are not
included. Cura `.3mf` and `.curaprofile` import continue to work independently as the underlying
baseline configuration, and PrusaSlicer `.ini` and OrcaSlicer preset import as their own baselines.
