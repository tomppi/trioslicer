# Support painting × non-planar pipelines bug check

Date: 2026-08-19
Audit target: branch feature/support-painting-nonplanar, feature commit 0bfe4a6 ("feat: warp painted support prisms through CurviSlicer and conical transforms")
Base: main at e62e436
Reviewers: engine adversarial review (subagent), UI/state-machine review (subagent), model pass

## Scope

0bfe4a6 allows painted support enforcer/blocker prisms to combine with
CurviSlicer and conical slicing. The prisms are warped with the same transform
as the model (relief-field flatten for CurviSlicer, cone warp around the model
centre for conical) in both engine transports (standalone command builder and
the resolved-settings writer). Adaptive-wall modifiers remain rejected.

## Severity summary

| Severity | Confirmed | Fixed in this branch |
|---|---:|---:|
| Critical | 0 | 0 |
| High / stop-ship | 1 | 1 |
| Medium | 4 | 4 |
| Low | 8 | 7 |

## High findings

### SUP-01 — Conical slicing with supports lifted the entire model off the plate

CuraEngine builds support towers in WARPED space from the plate (z = 0) up to
the warped model surface. The back-transform maps a tower bottom at radius r to
z_orig = z_warped - r*tan(theta) < 0 (about -14 mm at r = 50, 16 deg). The
global translate() scan takes the minimum extruded Z across the whole file -
including support moves - and lifts everything by firstLayerHeight - zMin, so
the model (whose own bottom back-transforms to 0) printed 14-28 mm in mid-air.
Reachable with auto supports (enabled by default) and with painted supports,
which this feature enabled. CurviSlicer was immune because unflattenZ keeps
flatZ <= baseZ planar.

Status: Fixed. ConicalGcodeTransformer now tracks ;TYPE:SUPPORT sections,
clamps back-transformed support Z at 0, and skips support extrusion layers that
fall entirely below the plate without advancing the emitted E state. The tower
keeps its correct top height, sits on the plate, and no longer drags the
translate() lift.
Regression: ConicalGcodeTransformerTest.supportTowerBottomsStayOnThePlateAndTheModelIsNotLifted,
supportLayersPartiallyBelowThePlateAreClampedToTheBed.

## Medium findings

### CAN-01 — Conical preparation was effectively uncancellable

Both up-front checks passed workItems = 1 against the default interval 1024
(1 % 1024 != 0), so the interrupt check never fired, and
ConicalTransform.refineOnce / warpAround had no per-triangle checks. A large
painted region (prisms are 8 triangles per painted triangle, refined by
4^iterations) ran uninterruptibly.

Status: Fixed. Per-triangle checkConicalCancellation(triangleIndex) in
refineOnce/warpAround; the up-front calls use interval 1.
Regression: ConicalPipelineTest.interruptedThreadAbortsPreparationAndLeavesTheSourceIntact.

### CAN-02 — Interrupted STL reads surfaced as ClosedByInterruptException

StlParser reads through NIO file channels; interrupting the slice worker
mid-parse throws ClosedByInterruptException before the cooperative check runs,
so a user cancel surfaced as an internal I/O failure.

Status: Fixed. Both pipelines wrap their parse calls
(ConicalPipeline.parseCancellable, CurviSlicerPipeline.parseCancellable) and
map ClosedByInterruptException to InterruptedException.
Regression: CurviSlicerPipelineTest.interruptedThreadAbortsPreparationAndLeavesTheModelIntact.

### UI-01 — Import persisted the previous model's paint (phantom paint)

importStl and importPartTopoResult saved the workspace snapshot pairing the NEW
model with the OLD paint state; a process death before the in-memory reset
restored the old triangle indices onto the new model, silently painting
arbitrary triangles.

Status: Fixed. Both import paths save the snapshot with an empty
SupportPaintState, matching the post-import in-memory state.

### UI-02 — Paint changes were not persisted until an unrelated save

paintAt / clearSupportPaint / setBrushRadius mutated MainUiState only; the
workspace snapshot was written on import, placement, and settings changes, so
process death silently lost recent paint.

Status: Fixed. A 400 ms debounced persistPaintSoon() writes the current
workspace after every paint mutation.

## Low findings

### UI-03 — Conical slices did not auto-select the nozzle Path preview

Status: Fixed. The viewer-mode branch now checks
(nonPlanarSettings.enabled || conicalSettings.enabled).

### UI-04 — Strokes painted during an in-flight slice diverged from the exported G-code

Status: Fixed. paintAt is ignored while isBusy.

### UI-05 — The mutual-exclusion backstop left stale artifacts exportable

Status: Fixed. The branch now clears slice artifacts.

### ENV-01 — Envelope rejection messages named a "Model vertex" for paint prisms

Post/pre-warp prism checks used the generic "Model vertex N is outside ..."
message and never named the failing file.

Status: Fixed. requireBinaryStlFits accepts an optional label; the paint-prism
call sites pass "Support-paint modifier <file>". The conservative warped-space
check itself is intentional: CuraEngine enforces the build volume on the warped
geometry, so rejecting early with a clear message is correct.

## Follow-up round (fixed after the first pass)

- CURV-01 — The Curvi G-code transformer had no cooperative cancellation in
  the line loop or the segment-emission loop, so interrupting a long
  G-code transform did nothing until the whole file was curved.
  Status: Fixed. checkCurviCancellation(segment) in the emission loop and
  checkCurviCancellation(lineNumber) in the line loop.
  Regression: CurviSlicerSafetyRegressionTest.interruptedThreadAbortsCurvingAndLeavesThePlanarSourceIntact.
- ENV-02 — resolved.json mesh sections were keyed by file name only; a future
  second modifier source sharing a name would silently overwrite a section.
  Status: Fixed. CuraResolvedSettingsWriter now rejects duplicate mesh names
  across the model and all modifier sets.
  Regression: CuraResolvedSettingsWriterSupportPaintTest.duplicateModifierMeshNamesAreRejected.
- LEAK-01 — AdvancedFeatureSettingsLeakTest had no paint x non-planar
  cross-product cases.
  Status: Fixed. paintedSupportsWithNonPlanarOnNeverLeakPaintMeshesWhenUnpainted
  and paintedSupportsWithNonPlanarOffEmitThePaintedEnforcerMeshAndNoWarpSidecars.

## Remaining notes (not bugs)

- The feed-rate fail-closed guard for printable moves without a positive F
  exists only in a local backup patch outside the repository; reapply it if a
  stricter pre-slice validation pass is desired.

## Checked and fine

- CurviSlicerField.sampleRelief clamps out-of-field coordinates to the grid
  edge, so prisms extruded past the model bounds degrade gracefully.
- Both transformers preserve ;TYPE:SUPPORT / ;TYPE:SUPPORT-INTERFACE comment
  lines verbatim; CurviSlicer unflattens support paths consistently (flatZ <=
  baseZ stays planar).
- Envelope checks: paint prisms are validated pre-warp and re-validated
  post-warp in both transports; the warped intermediate is exactly what
  CuraEngine slices, so a conservative check cannot reject a slice CuraEngine
  would accept.
- The resolved flow's staged-identity markers guarantee prisms are generated
  in displayed coordinates and warped around the model centre; no non-identity
  transform can reach the warp steps.
- Modifier mesh names (support-enforcer.stl / support-blocker.stl) are distinct
  constants; the two transports are mutually exclusive per slice and each
  slice regenerates prisms in a fresh workspace, so double-warping cannot
  occur.
- ConicalPreparations.adjustSettings leaves supports untouched;
  SmartOverhangStrategy does not interact with paint.
- ConicalSettings.validated() forces OUTWARD cones.

## Verification

- Full JVM unit suite green (gradle :app:testDebugUnitTest, build gates
  excluded): 89 tests / 0 failures on the feature commit; the same suite stays
  green after every round fix (final run below).
- New regression tests this round: 2 support-anchoring transformer tests,
  3 interrupt-cancellation tests (conical prep, Curvi prep, Curvi G-code),
  1 duplicate-mesh-name rejection, 2 leak-contract cross-product cases,
  joining the 7 feature tests already on the branch.
