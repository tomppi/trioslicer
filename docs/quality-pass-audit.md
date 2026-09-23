# Quality pass: what the five-hunter audit found

This records the second phase of the quality pass: five independent audits over
the slicing engines and process handling, the G-code pipeline, the Compose UI and
state, persistence and workspace restore, and the Smart Infill/native bridges.
Every finding below was reproduced and triaged; the fixes are in the working
tree and in `CHANGELOG.md`, and each one that could be tested has a regression
test in `app/src/test`.

## Fixed

| Area | Finding | Fix |
| --- | --- | --- |
| UI / state | Support paint and the Smart Infill package are engine modifiers, but changing either left the previous slice exportable | One `MainUiState.withoutPublishedSlice`, shared by the seventeen settings/engine/printer/model call sites and the paint and package paths |
| Smart Infill | Options edited while an optimization ran were stamped onto that run's metadata, which is what the store validates the archive against | Controller refuses the edit; panel rows disabled while busy; metadata taken from the run's own result |
| Persistence | A configuration snapshot wrote its settings but not the profile name the state shows, and never re-saved the model's workspace record - the next launch dropped the model | Import names the profile and re-persists the workspace, as a Cura project import does |
| Engines | PrusaSlicer and OrcaSlicer slice without the Smart Infill package and without saying so | Slice refuses with a message when a package is active on a non-Cura engine |
| Engines | A 60-minute timeout surfaced as the same `InterruptedException` a cancellation uses | Both engines raise their own `SliceException` with the log path, as CuraEngine did |
| G-code | `M117 Loading * * *` was truncated at the asterisk and refused as framed G-code | Framing is only read as `*<digits>` after an `N` line number |
| G-code | An estimate of 24 hours or more parsed as no estimate at all | One shared day-aware parser for both engines and the sanitizer, saturating instead of wrapping |
| G-code | The Prusa-family strip deleted M201-M205 the user had written into their own start G-code | The user's templates are passed to the sanitizer and their limit lines are kept |
| G-code | The nozzle-path sample was sized from a move count the emitter never reached (Bambu end block) | Both region gates accept both spellings, so the count and the emit agree |
| Persistence | Importing a PrusaSlicer profile without G-code templates wrote empty strings over the stored ones | Absent templates are reported as null and the stored ones are kept |
| Persistence | The paint debounce saved the state captured when the stroke landed, so a settings edit inside the window was saved under the old fingerprint | The debounce reads the state when it fires |
| UI / state | The session value dialog rendered the snapshot taken when it was tapped, so the check mark never moved | The rail remembers which value is open and re-reads it per recomposition |
| UI / state | Back left the paint brush and annotation toolbars (or the app) | Both register a back handler |
| UI / state | The gesture-help banner came back after a fold: its flag was remembered inside the layout branch | The flag lives with the other panel flags |
| Engines / process | PrusaSlicer and OrcaSlicer kept a diagnostic log per request forever, and their request header was wiped when the engine output streamed in | One reaper for all three engines; both streams append, as CuraEngine's did |
| Engines / process | The Orca failure path read no log path out of its `SliceException` | It does now |
| Smart Infill / JNI | A Blender handoff that failed after staging left its copy behind, one per generation | The staged file is deleted on failure |
| Smart Infill / JNI | An export claimed in the window between the listener check and the queueing stayed queued for good | Both sides decide under the same lock |
| G-code | The layer preview kept a third copy of the GL line-width query | It uses the shared one |
| Imports | The configuration snapshot was read whole with `readText()` | All three import paths share one reader that caps as it reads |
| UI / state | The Orca preset picker parsed a multi-megabyte vendor index and a profile per machine during composition | Read on a worker thread with a "reading" state |
| UI / state | The Orca settings catalogue (200 KB of JSON) was parsed during composition | Parsed on a worker thread; a key declares no range until it arrives |

## Triaged as not a bug

- **Artifact lease staleness.** Leases are held for the duration of a copy or an
  upload, and the reaper's threshold is 24 hours, so it cannot pull an artifact
  out from under a live reader.
- The hunters also checked and ruled out the settings JSON codecs,
  `WorkspaceStateStore` atomicity, `AppStateStore` bundle verification,
  `UserPresetStore`, the Cura archive limits, `OneShotExportFileProvider`,
  artifact-lease accounting, JNI declared-vs-exported parity, unit conversions,
  the Smart Infill frame contract, modal-state parsing and preview sampling.

## Second pass: the session's own diff

A fresh audit of everything this pass changed, run after the work settled. Four
real defects came out of it, all fixed (see the changelog): the Smart Infill
panel's numeric rows fought the typist because the focus flag that guards the
value resync was never set; the diagnostic log export refused a log larger than
its cap instead of truncating it; the Smart Infill apply path caught
`CancellationException` and reported a bogus write failure; and OrcaSlicer
reported a zero-second estimate where PrusaSlicer falls back to the engine's
summary. It also confirmed the areas it checked were clean - the checksum
framing rule, the sanitizer's user-template keep-set, the print-time estimate
saturation, log reaping, the Blender handoff lock, the layer-marker sets, the
Prusa config merge, the picked-document reader, the paint colour buffer - and
mechanically verified that every function moved into `NozzlePathSupport.kt` is
byte-identical to the copy it replaced.

## Third pass: the areas the second pass did not reach

Two more audits - the Compose shell and its state wiring, and the viewer's GL
lifetimes plus the CuraEngine command/post-processor - found nine defects and one
duplicate layout branch. Fixed: the M220/M221 restores searched for the raw end
template while the engine wrote the resolved script; the region program and its
VBO names were not forgotten when the GL context was recreated; the colour
fallback path was given a client pointer with the mesh VBO still bound; a
rotation could cancel a Smart Infill import between its disk commit and its
published-slice invalidation; the printer screen, all-settings sheet and model
tools flags survived a tab change (ghost title, eaten back press); the
layer-events and AI-chat back handlers did not require their surface's own
condition; a session value editor reset an in-progress drag on any recomposition;
and the session tiles accepted taps while slicing even though the setters refuse
them. One finding was rejected: the claim that the Smart Infill region shells go
stale when the model is moved - their geometry is in the model's own coordinates
and is drawn with the model matrix, so a placement change moves both together.

Also fixed from this pass: `EnderSlicerApp` computed `expandedLayout` twice - the
inner one measured the Scaffold content, which the rail has already narrowed, so
the two could disagree on a ~600-680 dp window and lay the model tools out for a
phone beside a rail meant for a fold. The inner measurement and the two identical
`ViewerPanel` calls it guarded are gone; the viewer is composed once, from the
window's own decision.

## Fourth pass: the sheets and the modelling/native bridges

Two more audits - the settings and profile sheets, and the modelling screens,
harness client, Smart Infill controller and process runner - produced twelve
findings. Nine are fixed (see the changelog): the Smart Infill preparation ran
its native voxelizing on the main dispatcher; a cancelled start leaked the
native session; a stop during Blender's startup was undone when the coroutine
resumed; an Orca vendor switch kept the previous vendor's machine list; the AML
button failed to register the switch it turned on; a Prusa printer change left
the previous printer's profiles selectable; a mixed-case extra-setting key never
showed its stored value; `runStreaming` lost the interrupt flag; and a disposed
preview client could reconnect. The audits also confirmed clean: the conical and
non-planar sheets, profile management's confirm paths, the 157 field/key
mappings in the Prusa and Categorized sheets, the chat overlay and modelling
screen, the Blender keeper service and bridge, the harness client (timeouts,
caps, no spinning read) and the Smart Infill package import/activate paths.

Closed since: the Orca sheet's unclamped edit window (the ranges now clamp every
numeric field once they arrive, reading the settings fresh so a concurrent edit is
not undone) and the OctoPrint webcam's stale last frame (a cleared frame clears
the image). A Compose test now pins the AML button's two settings keys, verified
to fail against the old single-call form.

Still open from this pass: the Orca fields' provenance label is computed from Cura
state (`source()` reads the Cura override set), so it can claim "Imported Cura
value" on an Orca setting - fixing it properly means tracking Orca overrides, a
persistence change worth doing deliberately; and whether deleting a Blender export
can unlink one the engine is still writing is unproven - it needs a confirmed
window before it is worth guarding.

## Lint triage

A full `:app:lintDebug` run was triaged at the same time. Fixed: one
`SuspiciousIndentation` (mis-indented annotation wiring in `EnderSlicerApp`).
Rejected with reasons: `ApplySharedPref` - all five `commit()` calls either check
the result for durability (the OctoPrint secret store, the pending-export store)
or return it (`AppStateStore`), so `apply()` would weaken them; `ObsoleteSdkInt` -
the only hit is the `mipmap-anydpi-v26` folder, which is a resource layout, not a
dead check; `TextConcatSpace` on an `ImageVector` path string, where `3zM18` is
valid path data; `ViewConstructor`, which only matters to the layout editor since
the view is built in code; and the `MissingPermission` / `StaticFieldLeak` /
`UnspecifiedRegisterReceiverFlag` hits, which are all in the vendored
`org.libsdl.app` sources. The seven `SetTextI18n` strings in the two WebView
activities are done as well: they read string resources now, so that category is
empty and the lint triage has no open items.

## Closed during the pass

- **Resolved: the engines' failure messages promise an export the app now has.**
  Eleven messages end with "Export the error log for details" and no export action
  existed: the log path sat unread in `MainUiState.sliceLogPath`, and the
  `latest-*.log` copies were kept for the action dropped in an earlier refactor
  (this pass had deleted its orphaned implementation as unused code). The More
  sheet's **Export diagnostic log** writes the setup plus the engine's log through
  the same one-shot export machinery as the G-code and configuration exports;
  `MainUiState.sliceLogPath` is what enables it, so the state is read now.

## Deliberately deferred

- **The OrcaSlicer fields' provenance label.** `source()` reports the Cura
  override set, so an Orca field can read "App override" or "Imported Cura value"
  from unrelated Cura state. Reporting it truthfully per field means tracking
  Orca overrides, which is a persistence-format change and the user's call.
- **Deleting a Blender export while the engine is writing it.** `File.delete()`
  succeeds on an open file and the store has no in-use check, but whether the
  window is reachable at all is unproven; it needs a confirmed case before it is
  worth guarding.
