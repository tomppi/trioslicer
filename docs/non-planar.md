# Non-planar slicing

TrioSlicer includes an Android-native non-planar pipeline of its own - a relief-field flatten and an
inverse mapping, not the CurviSlicer method it began from, and not a reimplementation of it. It is available under **More → Experimental → Non-planar slicing** and is disabled by default.

## Pipeline

1. TrioSlicer rasterizes the projected interior of every upper-surface triangle from the displayed and positioned STL. This preserves broad surfaces even on coarse low-poly models.
2. It creates a smoothed height field and derives a bounded relief field.
3. Requested curvature strength is reduced automatically using a conservative inverse-field derivative bound. The final emitted path slope is checked again and rejected if it exceeds the effective nozzle-clearance slope.
4. The displayed STL is flattened in an isolated request workspace. Imported Cura affine transforms are resolved before this step, and the persisted/displayed source is never modified.
5. Smart Infill modifier volumes and painted support enforcer/blocker prisms are flattened with the same field, so their density regions and painted support regions remain aligned with the flattened solid.
6. CuraEngine slices the flattened solid using the active Cura profile.
7. Every printable linear `G0`/`G1` path is subdivided and mapped through the inverse field, creating continuously varying Z coordinates.
8. Positive extrusion is compensated for the actual three-dimensional path length. Relative XYZ/E output carries quantization residuals so each source move closes on its intended endpoint.
9. Feed rate is reduced where necessary to respect the configured maximum Z speed.
10. An EnderSlicer-owned sentinel marks the exact beginning of machine-end G-code. The boundary is monotonic: later comments cannot re-enable curvature. End-script retract, lift, wipe and park moves are preserved rather than curved.
11. The normal TrioSlicer sanitizer validates every spatial move—including travel, wipe and park moves—against the configured machine envelope before immutable publication.

The final file contains these markers:

```gcode
;ENDERSLICER_NON_PLANAR:ConformalSurface-Android-v1
;ENDERSLICER_CONFORMAL_REGIONS:...
;ENDERSLICER_CONFORMAL_SHELLS:...
;ENDERSLICER_MACHINE_END_BEGIN
```

## Shared command-safety policy

All G-code safety consumers now use one canonical command identity and fail-closed capability policy. Numeric aliases such as `G01`, `G02`, `G092`, and `M083` are normalized before any decision. Unsupported motion, coordinate, workspace, unit, tool, or spline commands are rejected rather than passed through invisibly.

Custom layer-event G-code uses a deliberately small state-neutral allowlist. It cannot change XYZ/E modes, extrusion mode, units, workspace, tool, motion mode, or inject unvalidated movement. Line-number and checksum framing is also rejected for custom event text so the app never rewrites a framed line into a semantically different command.

The transformer, sanitizer, layer-event materializer, immutable publisher, and Path parser share this policy. A command cannot be accepted for publication while being omitted from the Path preview or ignored by machine-envelope validation.

## Options

- **Curvature strength:** requested fraction of the safe field deformation.
- **Surface smoothing radius:** spatial scale that separates broad curved layers from small mesh features.
- **Maximum path slope:** hard upper bound for the final inverse-mapped path gradient.
- **Nozzle clearance angle and height:** conservative physical clearance model around the nozzle.
- **Flat base layers:** keeps the first layers planar for bed adhesion.
- **Field resolution:** sampling resolution of the deformation field.
- **Maximum generated move length:** subdivision limit used while restoring curves.
- **Maximum Z speed:** feed-rate limiter for simultaneous XYZ moves.
- **Extrusion length compensation:** scales extrusion for the true curved path length.
- **Warp Smart Infill modifiers:** required when Smart Infill regions are active.

Numeric drafts are preserved across configuration recreation. Save is disabled until every visible value is valid and within its displayed range. Both decimal point and decimal comma input are accepted.

Changing any non-planar option invalidates previously published G-code. Export, Layers and Path remain unavailable until a fresh slice exists for the current configuration.

## Engines

The relief-field pipeline described here is CuraEngine's. With OrcaSlicer active the same switch
sets OrcaSlicer's own `zaa_enabled` (Z-layer contouring) instead, which is why the sheet says so
and why the relief-field and hot-end clearance values do not apply on that engine. PrusaSlicer has
no non-planar slicing at all, so a slice with it enabled is refused with a message rather than
producing flat G-code.

## Path viewer

After slicing, the preview selector contains three independent modes:

- **Model** — the imported STL and build plate.
- **Layers** — the existing cumulative Cura layer preview.
- **Path** — the ordered nozzle journey from the first spatial move to the last.

The Path view includes both travel and extrusion. Travel is grey; extrusion is coloured by print speed, cyan slow to orange fast (the single nozzle-path colour mode), with a subtle per-layer tint so stacked beads stay readable. Playback, the slider, **Previous**, **Next** and **Restart** follow print order rather than layer order.

For files with at most 120,000 spatial moves, every move is retained and the controls step exact source moves. Larger files use an evenly distributed bounded preview that always retains the first and final move and records each retained source index. The UI labels these entries as **Preview segments** and states the corresponding source move number; Previous and Next then step between retained preview segments rather than claiming exact full-file stepping.

Path parsing resets when the artifact changes, is cooperatively cancellable, and rejects unsupported movement instead of silently drawing an incomplete route. Requested speed labels include the active `M220` speed factor. The OpenGL surface pauses, resumes, and releases with the Android lifecycle so repeated backgrounding or mode changes do not retain a live rendering context.

## Immutable artifact identity

A slice or layer-event reapplication publishes one immutable artifact directory and one matching artifact ID. Republishing layer events replaces both together. Export, Layers, Path, and OctoPrint availability require the expected ID, completion marker, envelope metadata, and G-code file to agree.

Lease acquisition fails for incomplete, released, or mismatched artifacts instead of returning a no-op lease. This prevents a leftover `print.gcode` file from being exported or uploaded after its publication contract has been lost.

## Rejected output

The slice fails without replacing or publishing G-code when:

- the model is too short for the selected flat base region;
- the field cannot be represented or inverted safely;
- any final emitted segment exceeds the effective slope limit;
- any spatial motion leaves the configured machine envelope or build height;
- the move budget would be exceeded;
- any arc, spline, unmodeled motion, coordinate-system change, or unsupported command is present while non-planar slicing is active;
- a coordinate reset would make printable-path transformation ambiguous;
- adaptive-wall modifier volumes are active, because they are generated from bend detection on the un-warped model and would misalign;
- a Smart Infill package is active while modifier warping is disabled;
- CuraEngine or the normal TrioSlicer validation rejects the output.

Arcs are rejected everywhere, including leading-zero aliases and custom startup purge paths. Disable arc fitting and remove custom arc commands before non-planar slicing.

## Physical safety

Software clearance checks cannot know the exact shape of a heater block, silicone sock, fan duct, probe or carriage. Start with a small model, conservative strength and slope, and inspect the complete Path view before printing. For a sampled large Path preview, use an external full-file validator as well. Keep a hand near the printer stop control during initial tests.

Machine-envelope validation currently uses the configured build-volume geometry for every resolved motion. Custom purge or park positions outside that configured envelope must be changed or represented by an appropriate printer definition; they are not silently accepted.

## Audit regression coverage

The post-audit regression suite covers:

- canonical and leading-zero command aliases;
- hidden arc, spline, coordinate, and modal-command rejection;
- state-neutral custom layer-event validation, including `M82`/`M83` rejection;
- startup arcs and unchanged source files after rejection;
- terminal machine-end lift, wipe and park preservation even after fake layer comments;
- clearance above positive and negative relief fields;
- inverse-slope limits and final segment enforcement;
- all-motion machine-envelope rejection;
- resolved-profile single-copy staging;
- low-poly projected-triangle rasterization;
- relative XYZ/E endpoint closure;
- truthful source-indexed Path sampling, requested `M220` speeds, and unsupported-motion rejection;
- artifact ID replacement, completion validation, lease enforcement, and UI availability checks;
- Path OpenGL lifecycle ownership contracts.

## Attribution and implementation scope

The method implemented here is **Daniel Ahlers'**: *Non-planar slicing*, his 2018 MSc thesis in the
TAMS group at the University of Hamburg
(<https://tams.informatik.uni-hamburg.de/publications/2018/MSc_Daniel_Ahlers.pdf>). Its approach is the
one this app follows, and the code says so where it is named: detect the near-horizontal top surfaces
automatically, check them for collisions, print the structure below them as ordinary planar layers,
and lay a non-planar toolpath over the top of that. Ahlers' implementation modifies Slic3r (GPLv3,
`Zip-o-mat/Slic3r`, branch `nonplanar`); this one carries the same idea out on CuraEngine and the
app's own transformers.

It is **not** CurviSlicer (Etienne et al., *Slightly Curved Slicing for 3-Axis Printers*), which solves
for curved layer surfaces directly, nor a reimplementation of it.

The Android backend is a clean Android-oriented implementation of the flatten/slice/inverse-map
concept. It does not package a desktop slicer or its automation, and it claims no numerical identity
with any other implementation. That keeps the whole process offline on an ARM64 Android device.
