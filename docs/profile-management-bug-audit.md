# Profile and filament management bug audit

This audit covers the first named print-profile and filament-profile implementation on `feature/profile-filament-management`.

## Confirmed and fixed during implementation

1. **Print and filament settings could overlap conceptually.** The categories now use explicit disjoint key sets. Printer geometry, nozzle size, G-code flavor and start/end G-code remain machine settings.
2. **Repeated taps could race create, save, apply, rename or delete operations.** The management sheet now serializes mutations and disables conflicting controls until completion.
3. **Closing the sheet could surface coroutine cancellation as a false storage error.** Cancellation is propagated separately and is not shown as a failure toast.
4. **A future setting addition could make an older preset unreadable.** Stored presets are accepted when they contain at least one recognized correctly typed value; missing newer values are left unchanged.
5. **A partial legacy preset could choose an unrelated settings marker.** Preset application derives its atomic `updateSettings` marker from a value that the preset can actually apply.
6. **Applying a preset outside the normal settings path could leave stale G-code valid.** Presets now use the existing `MainViewModel.updateSettings` lifecycle, which clears G-code, preview, events and estimates and persists the result.
7. **Deleting an active preset could unexpectedly reset current settings.** Deletion clears only the saved record and active ID; the current slicer values remain unchanged.
8. **Optimized builds could rename backing fields used for serialization.** The release rules retain `SlicerSettings` fields.
9. **Locale-sensitive ordering could reorder names unexpectedly.** Preset sorting uses `Locale.ROOT`.
10. **A damaged active ID could reference a missing or wrong-kind preset.** Active IDs are validated when loading and after deletion.
11. **A failed write could replace the prior library with a partial file.** Saves stage a replacement and preserve a rollback copy until commit succeeds.
12. **Duplicate names could create ambiguous selections.** Names are normalized and compared case-insensitively within each preset kind.

## Regression coverage

The unit tests verify:

- filament presets change material behavior without changing printer, layer-height or infill settings;
- print profiles change print behavior without changing machine width, temperature or material flow;
- complete snapshots include every current category key;
- partial older filament presets remain applicable while preserving newer settings;
- presets without any recognized correctly typed value are rejected;
- applying a preset marks only its own category as explicit overrides.

Workflow `30692469066` passed against source commit `73c285ec94e6ca6e5e4d69d7d8f71a34210b66fa`. It successfully completed pinned Cura-resource validation, BumpMesh preparation, the ARM64 CuraEngine build, Kotlin/Compose compilation, all unit tests and definition audits, debug APK assembly, and artifact upload.

## Manual device checks still required

- Create two print profiles and two filament profiles, restart the app and verify names, active IDs and values restore.
- Modify an active preset and test Cancel, Discard & apply and Save & apply.
- Rename active and inactive presets; verify duplicate-name rejection is case-insensitive.
- Delete active and inactive presets; verify current settings remain unchanged.
- Apply a profile after slicing and verify export and OctoPrint upload no longer use the stale G-code.
- Import a Cura project/profile and verify named presets remain available while the imported configuration remains the baseline.
- Switch print and filament presets in both orders and verify each category preserves the other.
- Dismiss the sheet during a save and reopen it to confirm the atomic library remains readable.
- Exercise the 60-character name limit and preset-count limit.

## Known scope limits

The first version does not import/export the user preset library, synchronize it across devices, or provide per-printer compatibility filters. Those are feature limitations rather than unresolved defects in the local named-preset workflow.
