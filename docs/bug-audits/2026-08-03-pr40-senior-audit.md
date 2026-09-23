# EnderSlicerCura PR #40 senior bug audit

Date: 2026-08-03  
Audit target: pull request #40, `fix/smart-infill-audit-round`  
Initial audited head: `0b8644fc2fabff74c800a9416c40f08b2a7fa69b`  
Base: `main` at `e18275f3a89af65ede691aa171ae2d00518cf1cb`

## Severity summary

| Severity | Confirmed | Fixed in this branch |
|---|---:|---:|
| Critical | 0 | 0 |
| High / stop-ship | 5 | 5 |
| Medium | 6 | 6 |
| Low | 2 | 2 |

## High and stop-ship findings

### SI-01 — Smart Infill publication raced slicing and plate operations

Package extraction, validation and activation were not represented by `MainUiState.isBusy`, so Slice or Clear plate could run against the previous package generation while the UI later announced the new generation.

**Fix:** add a modal `smartInfillImporting` transaction, disable connected actions, detach stale process-local state before publication, restore the previous generation on ordinary failure, and publish storage/runtime/UI state in one serialized flow.

**Status:** Fixed.

### BM-01 — BumpMesh exports could grow cache without bound and retain partial files

Completed FileProvider handoffs were never deleted, active streams were not canceled from `onDestroy()`, and interrupted exports could leave large partial STLs.

**Fix:** one-shot FileProvider deletion, `.part` publication, exact binary-STL validation, atomic rename, active-stream cancellation, and bounded age/count/byte pruning.

**Status:** Fixed.

### BLD-01 — One-shot FileProvider violated Kotlin’s non-null descriptor contract

The provider override returned non-null `ParcelFileDescriptor`, but fallback calls exposed a nullable platform type and broke Kotlin compilation.

**Fix:** one checked ordinary-open helper and an explicit `FileNotFoundException` for an unexpected null descriptor.

**Status:** Fixed.

### SI-06 — Smart Infill modifiers inherited printable walls and skins

Both Cura transports assigned each modifier regional infill settings but allowed the modifier mesh to inherit the printable model’s nonzero shell configuration. Cura could therefore generate internal walls, top skins, bottom skins or roofing/flooring at modifier boundaries instead of using the volume only to partition sparse infill. This was a stop-ship print-correctness defect.

**Fix:** define one shared modifier contract and force these values to zero after each modifier load and again at resolved-JSON serialization:

- `wall_line_count`
- `wall_thickness`
- `top_layers`
- `bottom_layers`
- `initial_bottom_layers`
- `top_bottom_thickness`
- `roofing_layer_count`
- `flooring_layer_count`

The printable mesh keeps filaSim’s requested walls and top/bottom layers. Unit tests verify command ordering and resolved-map serialization. A real pinned host-CuraEngine fixture slices graded and binary packages through fallback and resolved transports and rejects internal wall/skin extrusion around the modifier volume.

**Status:** Fixed; real-engine CI result pending on the final head.

### SI-07 — Binary and graded regional pattern semantics were collapsed into one field

The Android filaSim export stored `state.solidPattern` as the package pattern in binary mode, discarding the ordinary sparse base pattern. Cura then applied one pattern to the entire model. Graded 100% regions also missed filaSim’s rectilinear full-density rule.

**Fix:** version the Android export and private manifest schema:

- `basePattern` always stores filaSim’s ordinary sparse pattern;
- `binarySolidPattern` stores the binary region pattern only in binary mode;
- graded 100% modifiers use filaSim’s explicit `rectilinear` rule, mapped to Cura `zigzag`;
- every resolved modifier recalculates density-dependent Cura values with its own regional pattern;
- legacy graded packages remain loadable, while legacy binary packages fail closed with a regeneration message because their lost base pattern cannot be reconstructed safely.

Asset format 8 forces a clean source workspace and CI validates the generated TypeScript metadata contract.

**Status:** Fixed; generated-source and Kotlin validation pending on the final head.

## Medium findings

### SI-02 — Clear plate removed Smart Infill before durable model cleanup succeeded

**Fix:** await ViewModel cleanup, confirm the plate is empty, then remove only the captured immutable package generation. A failure keeps model and package together.

**Status:** Fixed.

### SI-03 — Remove Smart Infill retained its package directory indefinitely

**Fix:** clear the active pointer/runtime immediately and delete only the captured package directory asynchronously. Regeneration and successful Part Topo replacement also delete the superseded generation.

**Status:** Fixed.

### SI-04 — Smart Infill touch navigation had competing camera paths and no pinch zoom

filaSim’s custom one-finger pivot orbit and Three.js two-touch controls could process the same gesture. The earlier pan repair still lacked pinch zoom.

**Fix:** one finger keeps pivot orbit; two fingers enter one manual centroid-pan and orthographic-pinch path. Pinch remains centered below the gesture midpoint, clamps to `0.05`–`200`, updates camera/target/orbit pivot together, handles cancellation, and keeps the 2→1 transition in multi-touch mode until all fingers lift.

**Status:** Fixed in generated Android filaSim source; on-device feel remains required.

### SI-08 — Part Topo replacement reused the generic STL/3MF import path

The result callback called `importStl()`. That path can apply a matching imported Cura scene affine or center the replacement on the bed. A filaSim Part Topo result is derived from the exact displayed STL and is exported in filaSim’s local center/min-Z frame, so generic name matching could double-transform it or lose the analyzed placement.

**Fix:** add a dedicated durable transaction that:

1. materializes and parses the result without publishing state;
2. restores only the analyzed displayed mesh’s XY center and minimum Z;
3. uses identity linear transform because prior rotation, scale and 3MF affine were already baked into the displayed input;
4. validates the transformed geometry against the active printer envelope;
5. writes the workspace snapshot before publishing ViewModel state;
6. keeps the previous model and Smart Infill package on any failure;
7. deletes the previous model/package only after confirmed success.

A pure placement regression test verifies identity linear transform and exact displayed center/base restoration.

**Status:** Fixed; Kotlin and APK validation pending on the final head.

### BM-02 — BumpMesh’s warm Gradle marker covered only two output files

`prepareBumpMeshAssets` considered a workspace valid when the marker, `index.html`, and bridge existed. Missing or modified JS/vendor files could therefore survive a warm build and be packaged.

**Fix:** create and verify a complete `SHA256SUMS` manifest for every generated BumpMesh runtime file, gate `preBuild` on verification, invalidate `.source-version` on any mismatch, and add CI that deletes `js/main.js`, requires rejection, then requires deterministic regeneration and a valid manifest.

**Status:** Fixed; final CI result pending.

### UI-01 — Smart Infill and BumpMesh overflow horizontally on compact portrait windows

The dense desktop-style WebGL controls exceed the width of a folded cover display or ordinary phone in portrait, forcing horizontal scrolling and hiding controls. A permanent device-type check is incorrect for foldables because the same device changes from compact to expanded when opened.

**Fix:** classify the current window by `smallestScreenWidthDp`:

- `1..599dp`: request sensor landscape;
- `600dp+`: leave orientation unrestricted;
- unknown/zero: leave unrestricted.

The policy is applied before tool activity creation, on resume and after configuration changes. BumpMesh now handles the same orientation/screen/fold configuration changes as Smart Infill so neither WebView session is destroyed by the forced rotation or Fold open/close transition. Android 16 also ignores orientation restrictions on `sw600dp+`, matching the expanded policy, but the runtime policy keeps behavior correct on older Android versions.

**Status:** Fixed in code; folded/unfolded device validation required.

## Low findings

### SI-05 — Embedded Save Project and Load Project conflicted with Android persistence

**Fix:** hide the browser project file input and Save/Load controls only when `?android=1` is active. Settings and result exports remain available; browser filaSim is unchanged.

**Status:** Fixed.

### BLD-02 — APK output lacked a recorded artifact digest

**Fix:** CI now writes `apk-sha256.log` beside the APK-content validation logs.

**Status:** Fixed.

## Connected paths reviewed

- immutable Smart Infill generation snapshot across a synchronous Cura request;
- fallback Cura per-mesh parser order after `-l`;
- imported-profile resolved JSON per-density and per-pattern settings;
- shell-free modifier contract in both transports and final serialization;
- filaSim width/wall/skin assumptions on the printable mesh;
- local modifier translation back to analyzed printer coordinates;
- source SHA validation before modifier staging;
- versioned regional pattern metadata and legacy package behavior;
- explicit Part Topo placement and durable publication transaction;
- Android sample-model suppression;
- Smart Infill orbit, pan, pinch and pointer-release handling;
- Android-only project-control suppression;
- complete filaSim and BumpMesh generated-asset manifests;
- compact-window landscape and expanded-window free orientation;
- WebView retention across rotation and Fold state changes;
- cloud-backup exclusion of persisted Smart Infill geometry.

## Validation evidence

- Run `30790289877` passed the original audit repair set, including ARM64 CuraEngine packaging, unit tests, definition tests, host-CuraEngine regional toolpath tests and APK content checks.
- Run `30811127094` compiled filaSim Rust/WASM and TypeScript/Vite camera/top-bar edits, then exposed stale asset-format assertions.
- Run `30809813682` exposed the FileProvider nullability compile defect, which was corrected.
- Later source-preparation runs passed filaSim format-8 generation and the BumpMesh corruption/recovery exercise before subsequent commits correctly canceled them.
- A complete successful workflow on the final head is still required. PR #40 remains draft until that run and the device checks below pass.

## Required on-device regression checks

1. Generate Smart Infill and verify the modal import state blocks Slice, Clear plate and connected top-bar actions.
2. Slice graded and binary packages; inspect layer view/G-code for regional infill changes without walls or skins at modifier boundaries.
3. Verify binary base and solid regions use their selected patterns, and a graded 100% region uses rectilinear/zigzag.
4. Verify one-finger orbit, two-finger pan, stationary pinch, combined pan/pinch, pointer cancellation and fresh gestures after 2→1 release.
5. Repeat camera gestures after Fold open/close and orientation changes.
6. Export Part Topo from a moved and rotated model; verify the replacement stays at the same displayed center/base and does not inherit a second 3MF transform.
7. Force Part Topo failure and confirm the previous model, workspace and Smart Infill package remain active.
8. Clear the plate with an active package and verify restart restores neither model nor Smart Infill.
9. Remove Smart Infill and verify its package directory is deleted while the model remains.
10. Export, cancel and repeat large BumpMesh operations; verify no `.part` files or unbounded cache growth.
11. On the folded cover screen, open Smart Infill and BumpMesh from portrait and verify they enter landscape without horizontal page scrolling.
12. Unfold while each tool is open and verify the live WebView/session remains, orientation becomes unrestricted, and controls use the expanded width.
13. Fold again and verify the same live session returns to compact landscape.
14. Verify Save Project and Load Project are absent only in Android-hosted Smart Infill while Settings and result exports remain.
