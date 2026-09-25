# Changelog

All notable changes to TrioSlicer are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **The Blender engine's MCP token can be copied from the app** (Blender menu → *Copy MCP token*). The token lives in app-private storage and the engine refuses every command without it, so on a phone without root there was no way to hand it to a modelling agent: the file cannot be read from outside, which is what the token is for. Copying it marks the clip sensitive, so Android keeps it out of the clipboard preview.

- **Import model, and 3MF models at that.** The Import menu only ever read STL: a 3MF was accepted by the picker and then failed in the STL parser, and the label said "Import STL" as if that were the only model format. The entry is now *Import model*, and a `.3mf` is read properly — mesh, components, build items, their affine transforms and the file's unit — and staged as STL so every downstream path (resolved Cura profiles, the texturizer, Smart Infill) keeps one model format. Painted support facets inside the file are read back too, so a painted model round-trips through export and import.
- **Non-planar slicing on OrcaSlicer.** Enabling non-planar with that engine sets its own `zaa_enabled` (Z-layer contouring); the sheet says so, because the relief-field and hot-end clearance options belong to the CuraEngine pipeline.

### Fixed

- **Painted supports were silently dropped on PrusaSlicer and OrcaSlicer.** Paint became CuraEngine modifier volumes, and those two engines were handed the transformed STL, which cannot express per-facet paint: the paint showed in the viewer and never reached the G-code. A painted model is now staged as 3MF with the `slic3rpe:custom_supports` attribute both readers consume — one hex nibble per painted facet, `4` for an enforcer and `8` for a blocker.
- **Non-planar and conical slicing no longer fail silently off CuraEngine.** Non-planar is refused on PrusaSlicer, which has no such feature, and conical is refused on both other engines because it is the app's own G-code transform, wired into the CuraEngine pipeline. Both used to slice flat while the UI still said the mode was on.

## [1.3.6] - 2026-09-23

### Added

- **Saved profiles are engine-specific.** *Profiles & filament* saved one list that was written from and applied to Cura's settings, so under PrusaSlicer or OrcaSlicer a "print profile" captured a model those engines never read and applying it changed nothing visible. A preset now carries the engine it was saved for: the sheet lists the active engine's presets under its name, names and the 100-per-kind limit are counted per engine, each engine keeps its own active print and filament marker, and applying a preset whose engine is not active is refused with a note to switch back. PrusaSlicer and OrcaSlicer presets carry their own categories — layer heights, extrusion widths, perimeters, infill, skirt and brim, support and print speeds for a print profile; temperatures, cooling, retraction, flow and extrusion multiplier for a filament — behind the same type-and-range gate as Cura's, with an "automatic" extrusion width stored and restored as such. Machine geometry, the selected vendor presets and the engine's remaining catalog keys are never part of one. Presets saved by 1.3.5 or earlier load as Cura's, which is what they were.

### Security

- **The published APK is a non-debuggable release build.** Every release so far shipped the **debug** variant, so the installed app carried `android:debuggable` — `adb run-as` into its private data, heap dumps, the WebView's remote-debugging hook, and the debug-only certificate trust anchors. The published artifact is now `app-release.apk`, signed with the same committed key as before, so it installs over an existing install and keeps its data. (The key itself is still public: see [`keystore/README.md`](keystore/README.md) — closing that means a private key and one uninstall on every existing install, so it stays a deliberate trade.)
- **The harness launch token is no longer published, and the app no longer reads `auth.json`.** The launcher used to write the per-launch `?token=` URLs — plus a `go.html` sign-in page carrying the same token — into the harness's served directory. Every file there is public, so anything that could reach the port (any tailnet device, any local process) was handed a working sign-in for a harness that runs code on its host. The launcher now prints the URLs and publishes nothing - `dsh-url.sh` (and `dsh-url.ps1` beside the Windows launcher) prints the current ones again on demand - and `DSH_PUBLISH_AUTH_JSON=1` (`-PublishAuthJson`) restores the file only for a client that cannot paste a URL. The app stores the pasted launch token and the 30-day cookie it mints, both encrypted with a device-bound Keystore key, and the cookie is reused across launches, so a harness restart no longer costs a re-paste.
- **Plain HTTP to another machine is refused unless it is accepted for that address.** The chat warned that a non-HTTPS address sends the session in the clear and then used it anyway. It now asks once per non-loopback `http://` address, remembers the answer for that address only, refuses the connection in the client itself (not just in the UI), and keeps an "Unencrypted connection" notice on the chat while one is in use. Loopback needs neither the question nor the notice.

### Changed

- **Connecting the chat now takes the whole launch URL.** Paste what `dsh-launch` (or `dsh-launch.ps1`) printed, `?token=…` included — it is the only copy of the credential, and the address field is reduced to its origin afterwards. A stored 30-day cookie means the token is not needed again until it expires.

## [1.3.5] - 2026-09-22

### Added

- **Smart Infill's boundary conditions are drawn on the model**: the surfaces the part rests on tint green, the ones carrying a load orange, and the condition waiting for a tap yellow. The tint rides the per-triangle colour buffer the support brush already uses (the Smart Infill overlay wins over paint on a shared triangle, and only the triangles that changed are re-uploaded), so switching a condition on or off costs no mesh rebuild.
- **A load or support is edited, not just added**: every condition row carries its own values — the force, moment or bearing vector in X, Y and Z newtons, a pressure in MPa, a bedding modulus in N/mm³, a prescribed displacement in mm, a point mass in grams — and its title is rebuilt from those values, so the "Force 100 N down" quick-add becomes "Force (0.00, 0.00, -250.00) N" as soon as the numbers are typed in.
- **The Smart Infill panel now carries the material and the goal**, not just the print assumptions: Young's modulus, Poisson ratio, density, tensile/layer/shear strength, whether layer shear is scored, and self-weight, with presets taken from the pinned upstream material library (PLA, PETG, ABS, ASA, plus mild steel, 6061-T6 aluminium and SLA resin — the isotropic three carry no build direction, so their safety factor is scored against the material criterion rather than the layer one). The fields are filled from the engine's own reported defaults when the analysis opens.
- **Every boundary-condition kind the engine takes is now addable from the panel**: frictionless and cylindrical supports, a moment, a bearing load, a prescribed press and a remote point mass join the fixed, elastic, force and pressure quick-adds, and each one's values are edited in its row. A point mass's centre of gravity snaps to the area-weighted centroid of the surface it was picked on (the rule the WebView's bridge applied) instead of hanging off the origin.
- **Smart Infill says what its Check and Solve found**: the check reports whether the part is one constrained, loaded body or which bodies can still move freely — the case that used to make Optimize fail with nothing on screen — and the solve reports its iteration count, convergence and maximum displacement beside the buttons that produced them.
- **The optimized result is drawn on the part**: After an optimization the model is tinted by the infill density under each surface triangle — blue at the sparse base, through cyan and yellow to red at the dense cores — using the same blue→cyan→yellow→red ramp as the legend beside it, which lists each density with its share of the optimized volume. The bins come from the engine's own binned design field (the printed skin is not a design cell, so the lookup walks the surface normal to the material it covers), and the tint is dropped as soon as the setup changes, because it describes the run it came from. The lookup marches the surface normal up to 8 mm to the material a triangle covers — measured on a 225 143-triangle 3DBenchy, that lifts the tinted share of the surface from 83 % to 86 % (a 3 mm march left the underside of an organic part plain), and costs about a second on a 2½-minute run. A re-run with a different density split repaints every region: the colour buffer is rebuilt when the palette values change, not merely when the number of bins does, so a second optimization cannot keep showing the first run's colours.
- **The optimization goal is selectable**: stiffest within the budget, as stiff as a uniform print at the same mean infill, or the lightest design that reaches a safety-factor target (with the target and the material/layer/both measure beside it), plus self-supporting infill with an overhang limit and the analysis grid's target cell count. A resolution change rebuilds the voxel grid on a worker thread under the busy state, so it neither freezes the UI nor races a pick.
- **A volume that does not fit now names itself**: the envelope preflight labelled every Smart Infill and adaptive-wall modifier as "Model vertex N", so a rejected modifier read as a broken model — it cost a debugging session to find the real file. Each volume now carries its own label in the message.
- **The result legend says what the view does**: with the region shells drawn the surface is no longer tinted, so "the model is tinted to match" became "the regions are drawn on the model in these colours".
- **Applied Smart Infill results now land on the plate**: the package contract says a filaSim result is expressed in filaSim's local frame — centred on X and Y, grounded at Z — and the store restores the analyzed model's centre and base when it stages the volumes for the slice. The native engine works in the analyzed STL's own, already placed coordinates, so its volumes were shifted a second time (a 30 % region reached X = 277 mm on a 210 mm bed) and the slicer refused the model as outside the build volume. The native export recenters both the modifier archive and the Part Topo body into the contract's frame, and the store's staging — unchanged and still shared with the WebView path — puts them back exactly on the part. (The Part Topo import happens to recenter before it places, so its own path was already robust; writing the body in the contract's frame keeps both producers identical, and a host test now proves the volume survives staging exactly where the engine put it.)
- **Region shells no longer fight the surface tint**: once the shells are drawn they carry the densities on their own, so the per-triangle tint steps aside (picked supports, loads and the armed surface stay tinted) instead of colouring the surface twice.
- **The optimized regions are blended over the model as translucent volumes**: an offscreen preview of a real part showed that outlining the region meshes only paints the patches that happen to lie on the outer surface — the regions of a part are volumes *inside* it — and that the voxel shells are far too dense to read as a wireframe. They are now drawn the way filaSim's own density view draws them: coloured from the same ramp as the legend, blended at 40 %, with depth testing off so a nested core stays visible through the part.
- **Apply no longer crashes on a result**: the exported modifier archive was written to a cache directory the app's file provider does not serve, so handing it to the slicer threw "Failed to find configured root" and took the app down. The export now goes to the directory the provider serves (the same one the WebView handoff uses), both sides share one constant, and a test turns a file in each served directory into a real content URI — through the packaged provider configuration — so a writer that drifts off it fails the build instead of the phone.
- **The Plate panel is covered by JVM UI tests**: Robolectric renders the Compose panel in the normal unit-test run, so the wiring a device would exercise — which button opens the analysis, what the quick-add rows build, that a running session disables every edit, what an armed condition tells the user to tap, and what a failed check, a solve and the density legend actually read — is checked on every build without a phone. SDK 35 is pinned because Robolectric only drives 36/37 on a Java 21 test JVM, and the module's unit tests run on 17.
- **The packaged licence notice now covers the compiled engine**: the APK ships filaSim's pinned core as a native library, so the source notice inside it and the third-party notices name that binary, the pinned commit, the new `native/filasim/` session layer and its build script as its corresponding source — and the APK verification fails if the licence text or that notice is missing, for debug and release alike.
- **The native bridge no longer trusts a raw pointer**: the JNI handles are addresses, so a second destroy would have freed the same memory twice and a call after closing would have read it. The shim now keeps a registry of live handles — creating registers, destroying frees only what is still registered, and every entry point refuses an unknown or already-destroyed handle with a plain exception — and the app clears both handles before destroying them, so a late call is refused instead of touching freed memory.
- **Opening the analysis can no longer publish a stale session**: preparing a real part takes tens of seconds of voxelizing, and if the plate changed during that wait the session was still published — it would have tinted the new part with the old part's picks and exported a package the store then refuses. The model is now checked again before the session goes live, and the user is told to start it again.
- **A closed Smart Infill session can no longer be used**: closing the panel (or loading another model) while an optimization runs destroys the native session only after the blocking call returns — now covered by a test that holds a run inside the engine and checks the session survives until it comes back, and that closing twice destroys once. The same pass closed a real hole: a closed controller still accepted work and asked the destroyed session, and the tap path called the engine unwrapped, so a native error during a pick would have crashed the app instead of reporting itself.
- **Stopping a run is now covered by a test, and it fixed a stale state**: a cancelled optimization is verified to give up promptly, report that it was stopped, and leave the engine's progress snapshot saying it is no longer running. It used to keep saying `running: true` after any failed or cancelled run — cancellation, an under-constrained model, an assembly failure — so the app could keep polling a run that was over. Typing `NaN` or `Infinity` into a Smart Infill number field is refused instead of crashing the app when the settings are serialized.
- **Every JNI entry point is checked against the app's declarations**: a test reads the Kotlin declaration file and compares each native method's name, argument types and return type with the exported function, since JNI links by name alone and a drifted signature would compile on both sides and then crash on the phone. Writing it removed one export the app never declared or called.
- **The native engine is verified on the phone, not just on the host**: the aarch64 build runs the beam and a real 17 658-triangle model through the graded, strength-goal and Part Topo paths on the device, and reports the same masses, densities, surface bins and solver iteration counts as the host, so the arm64 codegen is numerically identical.
- **The app-to-engine JSON contract is tested from both sides**: the payload the panel writes for material, resolution, acceleration and solver limits (`configure`) round-trips through the engine's own settings read-back, all ten boundary-condition payloads the app can produce are accepted (an unknown kind, a missing triangle list and an empty selection are refused), and a force payload is checked to arrive as a load through the assembled system rather than merely parsing. The engine's JSON parsing now sits in the session layer next to the boundary-condition parser, so these payloads are testable without a JNI environment.
- **The native engine's three output modes are smoke-tested, not just the graded one**: `filasim-smoke` takes a mode (graded, binary or Part Topo), exports the artifact for it and reads that artifact back — the same ZIP reader the 3MF import uses — checking the modifier entry names, that the densities rise above each other and the base, that every entry is a binary STL whose header matches its byte length, and, for Part Topo, that the single body has triangles. The `filasim-engine-android` workflow runs all three modes and fails on the `verify:` line each one prints.
- **The native Smart Infill engine reports its own settings** (`effectiveConfig` over JNI: Young's modulus, Poisson, density, the strength allowables including the derived shear value, layer-shear scoring, resolution, solver limits), so the Plate panel can show the engine's real defaults rather than a second copy of them kept in Kotlin.
- **The Smart Infill engine runs in the app, not in a WebView**: the pinned filaSim structural solver — voxel meshing, the HEX8 force/stress solve, the density optimizer and the safety-factor criterion — is cross-compiled for arm64 Android as `libfilasim_jni.so` and driven from Kotlin, with cancellation and live progress over a control handle. The engine is built from the same pinned upstream source as the WebAssembly workspace (`scripts/build-filasim-engine-android.sh`, `native/filasim/`), so a native optimization produces the modifier package the existing slice integration already validates; `filasim-smoke` runs the whole pipeline on a desktop for verification.
- **The failure messages' "Export the error log" promise is kept.** Eleven messages across the three engines ended with it, but no export action existed - the log path sat unread in the state, and the engines kept a latest-log copy for a feature dropped in an earlier refactor. The More sheet now has **Export diagnostic log**: it writes the setup the last slice ran with (app, device, machine, profile, settings, overrides, model, duration, estimate, warnings) and the engine's own log to a text file the user picks, through the same one-shot export machinery the G-code and configuration exports use. It is enabled once a slice has run, and says so rather than failing when the log has already been reaped.
- **CuraEngine slices report progress too.** The top bar's "Slicing… 42%" and its determinate ring were wired to the percentages PrusaSlicer and OrcaSlicer report; CuraEngine reported none, and the reason is upstream: it computes the percentage and sends it over its command socket - that is what feeds Cura's own progress bar - while the terminal equivalent, the '-p' flag its own header documents ("Message progress over the CommandSocket and to the terminal (if the command line arg '-p' is provided)"), is a stub. `CommandLine::sendProgress` measures the value and drops it, and the flag's handler is a commented-out FIXME. The Android build now implements what the header promises (see `scripts/build-curaengine-android.sh`): '-p' turns the logging on and the engine prints one `Slice progress: 42%` line per whole percent, which the app passes, parses and shows. The engine's stage lines start with "Progress:" as well, so the parser matches the whole phrase.

### Fixed

- **The native engine's CI job finds its NDK again.** The job wrote its NDK path in shell
  syntax (`${ANDROID_HOME}/ndk/28.2.13676358`), which GitHub Actions passes through
  literally in an `env:` block, so the engine build died in its first step with "Invalid
  Android NDK path". It now uses the same `${{ env.ANDROID_HOME }}` expression as the four
  other engine workflows, and the host smoke step — which never reads the NDK — no longer
  sets it.
- **An edit made before the OrcaSlicer engine's limits were read is brought into them.** The sheet parses the engine's own min/max catalogue off the main thread; until it arrived an edit was saved unclamped, so a value the engine would refuse - an infill density of 150 against a declared 0..100 - could be persisted and sent with the next slice. When the ranges land, every numeric field is clamped once, and the settings are read fresh at that moment so an edit made while the parse was running is not undone.
- **A cleared webcam frame clears the image too.** `produceState` remembers its value while its producer restarts, so a cleared frame left the last snapshot on screen under a state that said there was no frame.
- **Starting the Smart Infill analysis no longer freezes the plate.** The session's preparation - building the voxel grid and the pick adjacency - ran on the app's main dispatcher, which is tens of seconds of native work: the UI was frozen for the whole of it and the "preparing" spinner could not even draw. It runs on a worker now, as the configuration and optimize calls already did.
- **A Smart Infill session opened while the screen is going away is closed.** The controller is built inside a cancellation-aware block, so a rotation mid-open left the Rust session (mesh and grid) alive for the life of the process with nothing referencing it.
- **"Stop Blender engine" during startup is no longer undone.** The startup coroutine suspends twice - the one-time resource extraction and the wait for the addon to serve - and resumed straight into booting the engine after the stop had already reported it stopped. Each start now claims a generation and re-checks it after every suspension.
- **Switching the OrcaSlicer preset vendor no longer lists the previous vendor's machines.** The loaded list is remembered across the key change while the new one is read, so the old machines stayed under the new vendor's name and a tap in that window picked a machine that vendor does not have. The list is cleared as the new read starts.
- **The "official AML start G-code" button registers the switch it turns on.** Only the key a settings call is made with is registered as an override, and a restored state re-applies the overridden keys alone - so enabling custom start G-code through that button left the flag unregistered: it worked until the next launch and then silently reverted, keeping the AML script stored but unused.
- **Choosing a different PrusaSlicer printer empties the quality and material lists.** They are read from the bundle on a worker while both pickers are active immediately, so a profile picked in that window was stored for a printer that does not offer it.
- **A mixed-case engine setting shows its saved value again.** Extra settings are stored under a lowercased key, but the all-settings sheet looked the value up under the catalogue's own spelling: OrcaSlicer's `required_nozzle_HRC` never showed its value, reopened blank and skipped its numeric validation.
- **Interrupting the process streaming wait restores the interrupt flag.** `runStreaming` cleared it where `run` keeps it, so a caller that catches `InterruptedException` and then checks `throwIfInterrupted()` - as all three engines do - would not see the cancellation.
- **A disposed modelling preview client cannot open a new socket.** A render blocked in a socket read outlives disposal; the retry that follows the failure reconnected, and nothing was left to close that connection.
- **The end-script restores go before the right script again.** With a custom end G-code enabled, CuraEngine was given the resolved script but the post-processor was told the raw template, so it searched the file for a script that is not there: the M220/M221 restores landed at the ";End of Gcode" comment and the user's own end script ran under an active speed and flow factor. Both now take the value of one shared helper, and a test pins that the command carries exactly what the post-processor is told to search for.
- **The Smart Infill region overlay survives a GL context loss.** `onSurfaceCreated` remembers that a new context invalidates the mesh and colour buffer names, but kept the region program and its two VBO names: after the context was recreated the region path skipped buffer creation and drew with objects from the dead one. It resets them with the others now.
- **The colour fallback path no longer reads from the wrong buffer.** When the colour buffer could not be created, the client-side pointer was handed to GLES while the mesh VBO was still bound - GLES reads such a pointer as a byte offset into the bound buffer, so the colours came out of the mesh data. It unbinds first.
- **A Smart Infill import can no longer be half-applied by a rotation.** The store commits the active package to disk as part of the import, but the in-memory handoff and the published-slice invalidation ran in a composition-scoped coroutine, so a rotation mid-import cancelled them: the disk and the runtime disagreed, and G-code sliced before the modifiers were applied stayed exportable. The commit section is non-cancellable now.
- **Changing tab closes the screen that belongs to the other tab.** The printer screen, the all-settings sheet and the model tools overlay are composed inside one tab's branch, but their flags survived a tab switch - the title bar kept saying "Printer" over the plate and the first back press was eaten by a handler for a screen that was not on screen. The flags are cleared on the tab change, and the layer-events and AI-chat back handlers now require the same condition as the surface they close.
- **A session value editor no longer loses an in-progress drag.** The editor was remembered by its `SessionEditor.Number` instance, which the rail rebuilds (with a fresh set lambda) on every recomposition, so any unrelated state emission reset the slider to the committed value and the drag's commit wrote the reverted number. It is keyed by the value now.
- **The session value tiles are disabled while a slice runs.** The settings setters refuse edits while the view model is busy, so the tiles accepted taps that silently did nothing; they are gated like the other rail actions.
- **The Smart Infill panel's material rows and toggles honour the busy gate.** They defaulted to enabled while every sibling row was gated, so a preset or a material number could be tapped during a running check/solve/optimize and was silently dropped by the controller.
- **The Smart Infill panel's numeric fields can be typed into again.** Every keystroke commits a value and the row re-renders from it, and the row meant to stop the field being rewritten while focused had a flag nothing ever set - so typing 3,5 into the tap radius gave "3,5.00" and committed nothing, in every numeric field of the panel. The field now tracks focus, keeps the keystrokes while it has focus, and shows the committed value in its canonical form again when it loses focus. The regression test types a value one keystroke at a time and fails without the fix.
- **A long engine log is exported truncated rather than refused.** The diagnostic export read the log through the import reader, whose cap refuses anything larger - so the export failed exactly when the log was longest. It now keeps the first 8 MiB and says in the file that the rest was dropped.
- **Cancelling a Smart Infill apply no longer reports a write failure.** The apply path wrapped its write in a catch-all that also caught cancellation, so leaving the screen mid-write showed "Unable to write the Smart Infill result" and swallowed the cancellation; it rethrows cancellation now, as the file's other coroutines do.
- **An unreadable OrcaSlicer estimate falls back instead of reporting zero.** The header parser returned 0 seconds when it could not read the engine's estimate, which skipped the fallback to the engine's own summary that PrusaSlicer uses - the status line could say "estimated print 0 min" for a finished slice.
- **The OrcaSlicer settings sheet no longer parses its catalogue during composition.** The engine's own min/max per key come from 200 KB of JSON, and reading it in the composable blocked the first frame of the sheet; it is parsed on a worker thread now, and until it arrives a key simply declares no range, which is the same answer the sheet already gave for a key the catalogue does not list.
- **The OrcaSlicer printer picker no longer freezes the sheet while it reads.** A vendor's preset index is megabytes and every machine in it is checked against its own profile file, and both reads happened during composition - opening the picker, or changing vendor, blocked the UI for as long as the largest vendor took to parse. The list is now read on a worker thread and the dialog says it is reading, and the profile behind a chosen machine is read the same way.
- **Every engine's diagnostic logs are reaped, and their headers survive.** PrusaSlicer and OrcaSlicer wrote a per-request log on every slice and never deleted one, so the logs directory grew with each attempt; both now reap their own logs after a day, exactly as CuraEngine already did. Their request header - the id, the model, the printer and the resolved settings - was also being wiped the moment the engine's output started streaming into the same file, because that stream opened the file fresh; it now appends, as the CuraEngine path does.
- **Back leaves the paint brush and the annotation tools.** Both toolbars own the model's gestures while they are open, but neither registered a back handler, so the system back gesture left them behind (and could leave the app instead of closing the tool). Back now closes them the way their own Close action does.
- **The gesture-help banner stays dismissed across a fold.** Its flag was remembered inside the layout branch that draws it, so folding or unfolding the device - which swaps the branch out of the composition - brought the banner back. The flag lives with the other panel flags now.
- **The nozzle-path preview's move count is the count it actually draws.** PrusaSlicer marks the end of the print with `;TYPE:Custom`, OrcaSlicer's Bambu envelope with `; FEATURE: Custom`; the pass that sizes the even sample stopped at the first spelling while the pass that emits moves stopped at both. On a Bambu-envelope print the sample was therefore sized from moves it never reached - it kept fewer moves than the cap allows and dropped the final move, which the sampler always keeps.
- **A failed Blender handoff no longer leaks its staged model.** The import copies the engine's export into app storage before parsing it; a corrupt or oversized export, or a failed workspace save, left that copy on disk. The engine writes a new export on every generation, so a broken loop leaked a model file per iteration.
- **A failed OrcaSlicer slice names its error log.** The failure path read the log path out of the Cura and PrusaSlicer exceptions but not OrcaSlicer's, so an Orca failure carried no log path for its diagnostics.
- **A Blender export can no longer be parked where nothing will pick it up.** The handoff claims an export before dispatching it, and queues it when no UI listener is attached yet - but the attach and the queueing were decided outside the lock the listener setter drains with, so an export claimed in that window stayed queued forever and never reached the plate. Both sides now decide under the same lock.
- **Every picked import document is capped while it is read.** The PrusaSlicer config and OrcaSlicer profile imports already refused more than 8 MiB, but the TrioSlicer configuration snapshot was read whole with `readText()`, so an accidentally picked large file would be loaded into memory before anything looked at it. All three now share one reader that enforces the cap as the bytes arrive.
- **Importing a PrusaSlicer profile no longer deletes the stored G-code templates.** A profile whose sections carry no `start_gcode`/`end_gcode` imported as an empty string and wrote that over the app's stored ones, silently losing custom start/end G-code. The importer now reports an absent template as null (a key that is present but empty still means "no template"), and the import keeps what it did not receive.
- **A machine limit the user wrote in their own start G-code survives the slice.** The PrusaSlicer family's base profile emits M201-M205 and the sanitizer drops them so the firmware keeps its own defaults - but it dropped every one, including a limit the user had deliberately written into a template. The templates are now passed to the sanitizer and their M201-M205 lines are kept.
- **OrcaSlicer's Bambu layer envelope gets its adaptive mesh leveling.** The injector knew `;LAYER:` and `;LAYER_CHANGE`; OrcaSlicer writes `; CHANGE_LAYER` for a Bambu Lab vendor, so a Bambu-envelope slice found no first layer and never got the AML block. The marker spellings now come from the shared dialect definitions.
- **The session value editor follows the state it edits.** A choice applies at once and keeps its dialog open, but the dialog rendered the value captured when the tile was tapped, so changing the infill pattern left the check mark on the old one while the plate behind it changed. The rail now remembers which value is being edited and reads it again on every recomposition.
- **A paint stroke and a settings edit inside the same debounce window no longer lose the model.** The plateau's paint persistence waited 400 ms and then saved the state captured when the stroke landed - including the profile and settings of that moment. A setting changed inside the window was therefore saved under the old fingerprint, which the next launch reads as "settings changed" and answers by dropping the model. The debounce now saves the state as it is when it fires.
- **Every input of a slice now drops the slice it produced.** Support paint and the active Smart Infill package are both modifiers on the engine command, and changing either left the previous G-code exportable - the panel offered a file that no longer matched the plate. The invalidation is one `MainUiState.withoutPublishedSlice` that the seventeen settings, engine, printer and model call sites share, and the paint brush and the package import/remove paths now call it too (an unchanged stroke, or a brush that is already empty, does not).
- **Smart Infill's print assumptions are refused while its run is in flight.** The optimizer reads its options once, at the start; editing line width or the budget from the panel mid-run still stamped the new values onto the finished result's metadata - the text the store validates the exported package against. The controller now refuses those edits, the panel disables the rows while the session is busy, and the metadata is taken from the run's own options rather than from whatever the panel holds when Apply is pressed.
- **A PrusaSlicer or OrcaSlicer timeout is reported as a timeout.** Both engines waited their 60 minutes and then threw the same `InterruptedException` a cancellation uses, so the status line said the slice was cancelled and the engine's log was never named. Both now raise their engine's `SliceException` carrying the log path, exactly as CuraEngine's timeout does.
- **A configuration snapshot keeps the model on the plate across a restart.** The import wrote its settings, custom G-code and baseline, but not the profile name the state displays, and it never re-saved the model's workspace record - so the next launch computed a different workspace fingerprint and dropped the loaded model with "Settings changed since ... was saved". The import now names the profile it came from and re-persists the workspace, as a Cura project import already did.
- **A Smart Infill package is refused, not ignored, on PrusaSlicer and OrcaSlicer.** Both would slice the model without the package's modifier volumes and settings, silently producing a different part; the Slice action now says to switch to CuraEngine or remove the package.
- **An asterisk in G-code free text is payload, not a checksum.** `M117 Loading * * *` was truncated at the first asterisk and then refused by the custom-G-code policy as framed G-code ("re-slice unframed"), which blocked a slice over a message. Framing is now read only where the protocol puts it: `*<digits>` at the end of a line that also carries an `N` line number.
- **A print-time estimate of 24 hours or more survives the sanitizer.** PrusaSlicer and OrcaSlicer grow a day field past 24 hours; the sanitizer kept its own copy of the parser, which had no day field, so it discarded the engine's estimate and wrote its own guess into the file. It now shares the runners' parser, which also saturates instead of wrapping when a hostile comment carries absurd fields.
- **Choosing a surface for a Smart Infill load or support selected the entire model.** A tap assigned the hit triangle's crease patch, and the crease segmentation is a face finder, not a selection: any smooth or flat surface is a single patch — one 3DBenchy hull is 70 % of its 225 000 triangles in one patch — so a tap on it took the whole part. A tap now takes the connected surface within a radius the panel controls (`Tap radius mm`, sized to the model when the analysis opens), a second tap adds another spot to the same condition, and a per-condition **Face** button widens the selection to the whole flat surface when that is what was meant.
- **The native session could be entered from two threads at once**, or destroyed while a solve was still running: a tap during an optimization reached the same session the worker owned, and closing the analysis freed the session under it. Every controller entry point that touches the session is now refused while a call runs (the panel disables those controls), and the session is destroyed only after the running call returns. Opening the analysis also voxelizes, welds the pick adjacency and reads the engine's settings on a worker thread instead of the UI thread.
- **The plate has one expanded-layout decision again.** `EnderSlicerApp` asked "is this a fold?" twice: once from the window (which is what chooses the session rail and the bottom bar) and once from the Scaffold's content box, which the rail has already narrowed by 80dp. On a window of roughly 600-680dp the two could disagree, and the model tools were laid out for a phone beside a rail meant for a fold. The inner measurement is gone, and with it the two identical `ViewerPanel` calls it guarded: the phone and expanded branches differed only by a redundant wrapping Box, so the viewer is composed once.
- **A test that asserted nothing now asserts what its name promised.** The Smart Infill controller's `equalityIsByContentSoTheViewerSkipsRedrawsPlaceholder` was a stub - a green test checking nothing. It now checks the real guarantee: writing an unchanged state back does not emit again, because the state is a data class compared by content, so the sheet's collectors and the tint they drive are not woken for nothing.
- **A blocked-slice reason has to stand before the rail shows it.** That red line is there to explain a Slice button the user cannot press, but one of its reasons is transient: a restored workspace has Smart Infill validating its package for a fraction of a second at launch, which held Slice and painted a red line that vanished again before it could be read. The line now waits 600ms before appearing and disappears at once when the reason clears, so a launch-time validation never flashes and a reason the user has to act on is there when they look.

### Changed

- **The two write-only `PaintColorBuffer` flags are documented for what they are** - the classification the tests assert - rather than claiming to decide the renderer's shader path.
- The mis-indented annotation wiring that lint flagged as `SuspiciousIndentation` is indented correctly.
- **An unused layout parameter is gone.** `EnderSlicerApp`'s `plateOverlayActive` promised, in its own documentation, that the session cards make room on a fold; nothing consumed it. Removed rather than left implying behaviour that does not exist.
- **Smart Infill's native engine is built and checked like the other four**: `scripts/build-filasim-engine-android.sh` builds `libfilasim_jni.so` from the pinned upstream source (fetching that commit when no prepared tree exists), `scripts/fetch-filasim-engine-android.sh` stages it from a release asset or a CI artifact on a machine without Rust, `:app:verifyFilaSimEngineLibrary` and `:app:verifyDebugApkFilaSimContents` fail a build whose library is not an AArch64 JNI library with its entry points intact, and the `filasim-engine-android` workflow builds the engine, runs the session tests and smoke-tests the whole pipeline on a generated beam.
- **Two redundant layout wrappers are gone.** The Cura and PrusaSlicer nozzle-path screens wrapped their body in a @BoxWithConstraints@ whose constraint scope nothing read - lint's @UnusedBoxWithConstraintsScope@ was the only error-level finding in the app's own code. A plain @Box@ lays out identically.
- **The last hardcoded UI strings became resources.** The two WebView activities set their toolbar titles (and two buttons) with literals, which lint flags as untranslatable; they read a string resource now, and the buttons use the framework's own @android.R.string.cancel@. Lint's @SetTextI18n@ category is empty.
- **Every shared constant has one home again.** The two nozzle-path previews and their renderer companions declared the same values several times over: `WINDOW_VERTICES`, `BUILD_PROGRESS_STRIDE`, `TURN_SPLIT_DOT` and `CHAIN_EPS` already lived in `RibbonPathGeometry`, and the ribbon's speed ramp existed in both views. The duplicates are gone - three of them were not read anywhere at all in the Prusa view, and `THIN_LAYER_HEIGHT_MM` was a second name for `FINE_LAYER_HEIGHT_MM` - and every remaining use points at the one declaration. The values are unchanged, so nothing renders differently.
- **The two nozzle-path previews share their drawing code.** CuraEngine's preview and the PrusaSlicer/OrcaSlicer one build different ribbons, but they handed them to the same three shader programs through the same matrices with byte-identical code: the GL line-width call, the tap-picking projection and the three draw calls (lit triangles, coloured lines, solid lines), plus the nine GL fields they use. All of it now lives in one `NozzlePathRendererBase`, together with the light directions and the unit-vector helper they need - 115 duplicated lines removed from the Cura view and 114 from the Prusa one. What genuinely differs between the engines (how the ribbon is built, how the camera is framed, how a tap picks a move) stays in each renderer. The draw calls themselves are unchanged, so both previews render exactly as before - they can no longer drift apart.
- **The three engines share one copy of their "latest log" step.** CuraEngine copied its request log with `copyTo`, PrusaSlicer and OrcaSlicer wrote theirs with `writeText(readText())`, each to its own name. One helper keeps the copies byte-identical, and the names are unchanged.
- **Dead weight removed.** Three private constants nothing read (the Prusa nozzle-path view's log tag, MeshPicker's epsilon, the Cura nozzle-path view's path width), a constructor parameter the progress-counting stream never looked at, and a third copy of the GL line-width query that the layer preview kept beside the two nozzle-path views - it is the shared helper now, since its body was identical. A repository-wide sweep for unreferenced internal and public declarations found nothing else.
- **The two nozzle-path previews share the code that is not engine-specific.** CuraEngine's preview and the PrusaSlicer/OrcaSlicer one draw different ribbons, but they frame the camera, answer taps and talk to the same shader programs the same way: the pointer focus, the point-to-segment distance behind a tap, the plate grid's spacing, degree wrapping, the direct float buffer, the GL line-width query and the speed-to-colour conversion were byte-identical private copies in each view. They live in `NozzlePathSupport.kt` now - 72 duplicated lines removed from each file, and covered by unit tests, which the private copies never were.
- **A collapsed Plate card is now only its chevron.** The printer card, the status banner and the three session cards used to keep a one-line header while folded, which still laid a bar of text across the model. Folded, each is a 48dp chevron tab sitting against the edge its card belongs to - the printer card and the banner against the left edge, the three session cards stacked into one rail against the right - and the tab is the whole tap target, so nothing but the arrow takes touches away from the camera. `CollapsibleCard` moved into its own file for this and is covered by a JVM UI test: folded shows neither the title nor the content and the chevron is what opens it, open shows both and folds again from the header. The trade is deliberate: the Print session card's Ready chip and Hide action, and the banner's status line, are visible while those cards are open.
- **The status banner moved under the printer card.** It used to be the plate's bottom-left corner, where it landed on top of the Smart Infill workflow; the bottom now belongs to that workflow and the Slice block. Folded, the plate's left edge is a column of two chevrons.
- **The Smart Infill workflow is two cards on a foldable or a tablet**, one against each side of the screen with the model between them: what acts on the part on the left (the boundary conditions, the tap radius, the material), what is asked of the optimizer and what it answered on the right (the goal, the print assumptions, the run buttons, the results). Both forms render the same section list, each card taking one half of it, and the 600dp that splits them is the app's own expanded-layout threshold, so a phone - or a split-screen window narrower than that - keeps the single card. Each card is capped to stay clear of the session cards on the right, and opening the workflow on a fold folds those three cards to their chevrons, which is what frees that column; each comes back with one tap.
- **The navigation is a rail on a foldable or tablet.** Plate, Settings, Print and More are Material 3's adaptive navigation suite now: a phone keeps the bottom bar, and a window wide enough for one gets a rail down the side instead, which hands the plate back the strip the bar was taking. The suite reads the window's own size class to decide.
- **Smart Infill's split follows Material's adaptive rules.** The two-column form needs an *expanded* window - the 840dp width class the adaptive scaffolds treat as two-pane, so a 600..840dp window keeps the single card - the band between the columns is the adaptive directive's own 24dp spacer, and when the device reports a fold the layout has to avoid, that band is placed on the fold itself rather than in the middle, measured from the panel's position in the window so it stays on the crease with the new rail beside it. Both come from `androidx.compose.material3.adaptive`, versioned by the Compose BOM already in the build.
- **The XYZ marker sits on the build plate now.** It used to be an 84dp widget floating in the plate's top-left corner; the renderer draws it on the bed's own front-left corner instead - three coloured arrows in the scene, so orbiting shows the plate's directions the way the grid does and the model hides them when the corner is behind the part. Each arrow carries its own letter - X, Y and Z drawn as line shapes in the same camera-facing plane as the head, squared to the camera's up so they read upright however the plate is turned, and set well clear of the head - so the marker says which axis is which without any text floating over the view. Their length is recomputed from that corner's depth every frame, so they keep the same size on screen however far the plate is zoomed.
- **The Plate's whole panel is in the navigation rail on a foldable or tablet.** The four destinations, the slice state ("Not sliced" / "Ready"), the layer count, the print time, warnings, the four values that used to be cards over the plate (layer height, infill, supports, adhesion) and the Plate's three actions - Slice, Export, Model tools - are one column at Material's own 80dp rail width. Tapping a value opens the editor that changes it, and the actions are the same ones the phone's bottom bar runs: Slice slices with whichever engine is active (Cura, PrusaSlicer or OrcaSlicer), Export saves that engine's G-code, Model tools opens the same hub. The Quick settings and Actions cards are gone, so the plate has no panel over it at all; a phone keeps the suite's bottom bar and its layout is otherwise unchanged. The status banner now opens folded, one tap away.
- **The printing session is in the navigation rail on a foldable or tablet, and the Print session card is gone.** The slice state ("Not sliced" / "Ready"), the layer count, the print time, any warnings and the three headline values - layer height, infill, supports - sit in the rail itself, under the four destinations, stacked because the rail is Material's own 80dp wide. Tapping a value opens the same editor the Quick settings card opens. Material's navigation suite cannot host extra content in its rail, so a wide window draws the app's own rail; a phone keeps the suite's bottom bar and its layout is otherwise unchanged. The panel's Hide action moved to the Quick settings card, which is now the first of the two that are left.
- **The slice's status is in the top bar, where no card can fold over it.** The Plate's panels - the printer card, the notice banner, the rail - all collapse, so the one line that is always drawn now carries the state: "Not sliced", "Slicing…" with a spinner, or "Sliced" with the layer count and the print time once a wide window has room for them. Warnings this slice raised are named beside it in red. PrusaSlicer and OrcaSlicer report how far along they are, so their slices read "Slicing… 42%" with a determinate ring and the app's new `sliceProgressPercent`; CuraEngine's JNI path reports nothing, so it keeps the indeterminate spinner rather than inventing a number. The rail's own chip says "Slicing…" too, so the two cannot disagree while the engine works.
- **Quick settings can be changed where they are.** Tapping a value on the Plate opens its editor - a slider for the layer height or the infill density, a list for the infill pattern, the adhesion or the supports - instead of sending you to the Settings tab. The editors write the settings of whichever engine would slice (Cura, PrusaSlicer or OrcaSlicer), and the sidebar's values open the same editors, so what a value reads is what it changes. The Settings tab is still one tap away under the values.

## [1.3.0] - 2026-09-19

### Added

- **OrcaSlicer 2.4.2 as a third slicing engine**, headless: a console built against libslic3r alone, the vendor profile tree it resolves presets from, a settings sheet and a teal accent. The port carries no GUI dependency, so the engine links and runs on the phone without wxWidgets, GLFW or OpenGL.
- **Cura's own printer catalogue**: the APK ships Cura 5.14.0-alpha.0's complete definition tree (1250 files) and offers its **636 machines** in a filtered picker instead of slicing everything as the Ender 3 chain. The chosen machine's definition chain is resolved by `CuraMachineCatalog` and layered under the app's printer envelope, start/end G-code and settings.
- **PrusaSlicer's own preset repository**: its 32 printer configurations, their quality profiles and **288 materials** are pickable. The bundle is a conditional system, so the app resolves it the way PrusaSlicer does - `PrusaPresetCondition` parses its 138 conditions and `PrusaPresetCatalog` walks the variant trees - with the shipped MK4 0.4 snapshot as the fidelity oracle: resolving that machine from the repository reproduces its 24 printer, 95 print and 39 filament values exactly.
- `scripts/generate-app-icon.py` draws the launcher icon from one geometry definition, emitting the SVG of record and both Android vectors, plus a supersampled preview.
- **Import an OrcaSlicer profile into the Orca settings sheet**: the `.json` the desktop app's *Export Config* writes, and the preset bundles its own import dialog accepts (`.orca_printer`, `.orca_filament`, `.orca_bundle`, `.orca_process`, `.zip`). A key the app has an editor for sets that field, every other key the engine's own `--dump-settings` catalogue declares is carried as an override, and a printer profile also sets the machine envelope. When a profile names the preset it inherits from, that base preset is selected with it, because the sheet offers the process and filament a machine preselects rather than the vendor's whole list. G-code templates, and values that cannot travel as one ini line, are refused and reported rather than spliced into the app's own output.

### Changed

- **The app is TrioSlicer.** It stopped being a duo when OrcaSlicer joined CuraEngine and PrusaSlicer, so the name, the mark and the README follow: three hotends now, one per engine, each in that engine's own accent, with a finned heatsink, heater block, darker nozzle cone and solid tip. The package id stays `com.tomppi.enderslicercura` so existing installs update in place, and the `;ENDERSLICER_*` G-code markers stay so exported G-code still parses.
- **Development moved from Windows to Debian.** `main` is the Linux line; `windows-build` keeps the frozen Windows tree it was ported from. The staging scripts still run under Git Bash or WSL.
- The Plate screen's **Print session** and **Quick settings** cards follow the active engine: an OrcaSlicer slice shows OrcaSlicer's layer height, infill, adhesion and supports, and the supports switch writes to the engine whose sheet edits it. They used to ask only "is this Prusa?", so an Orca slice showed the Cura numbers beside G-code it had not produced.
- The README names all three engines and their profile systems, and no longer documents raising the Android heap through `resetprop`.
- **The Plate screen fits a phone**: the printer, session and quick-settings cards collapse, the Slice and action block collapses to one row, and Model tools floats over the viewer as a compact bar with a small panel per group instead of a full-height sheet (its annotation entry is gone). Each axis's six rotate steps share a single row, the notice banner no longer sits under the tools bar, and the Cura profile and project (`.3mf`) imports moved into the Cura settings sheet, leaving the Import menu with just "Import STL".

### Fixed

- **GitHub Actions was failing before any job step ran**: `android-actions/setup-android@v3` installs the legacy `tools` package first, which the cmdline-tools repository no longer carries, so the APK build and the CuraEngine, OrcaSlicer and both PrusaSlicer engine builds were red. All five workflows now use the SDK the runner image already provides through `.github/actions/android-sdk`, which also pins `ANDROID_NDK_HOME` to 28.2.13676358 rather than the image's default 27.3.
- The OrcaSlicer dependency build applies the tracked Android patches **before** configuring, so `-DSLIC3R_GUI=OFF` is honoured and the configure no longer fails looking for OpenGL.
- The PrusaSlicer resource fetch installs PyYAML with `--break-system-packages`, which Ubuntu's externally managed interpreter requires.
- The Cura definition digest in `build.yml` moved with the catalogue, and a `fetch-orca-engine-android.sh` step keeps the CI APK complete.
- **The PrusaSlicer All-settings sheet listed nothing.** It read `assets/prusa/all-settings.json` inside `runCatching`, and no script had ever written that file - the shipped 3.0 launcher has no `--dump-settings`. `scripts/generate-prusa-all-settings.py` now derives the 352-key catalogue from the resolved configuration the app ships and the preset repository, the fetch script regenerates it, `checkPrusaSlicerPackaged` asserts it in the APK and `PrusaAllSettingsCatalogTest` pins it to `prusa3-base.json`.

## [1.2.0] - 2026-09-13

### Added

- **Modelling from scratch**: a full-screen destination reached from Plate ▸ Blender, holding the model, a chat and an exit button. It runs its own harness conversation with its own session, split away from the photo-to-3D chat. The engine starts on its default scene, so a first visit loads the cube it already has.
- **One camera, shared with the agent.** The view is the engine's own render rather than a second viewport, so what the user sees is what the agent sees - same scene, same camera, same shading - and there is no coordinate frame to translate between. Orbit with one finger, pinch to close in on a detail, two fingers to move the point being orbited. The camera belongs to the agent; **Take camera** is locked while it works, and sending a message hands it back.
- The frame size is published alongside the camera, so the agent renders the user's exact picture rather than merely pointing at the same place.
- **Workbench and EEVEE render in the embedded engine.** They never could before; see Fixed.

### Changed

- The chat takes a share of the screen rather than a fixed 260 dp strip, and collapses entirely when the model wants the room.
- The user's own messages are read from the session log, so long prompts are shown in full rather than clipped to a hundred characters.
- `adb` reaches the phone over the tailnet, which works anywhere, rather than only on the same WiFi.

### Fixed

- **GPU rendering in the embedded engine.** Any render with a GPU engine killed the app outright - no reply, no log, no tombstone, and a crash report with an empty backtrace. Five faults sat in a row, each hiding the next: `GHOST_SystemAndroid` dereferenced a null `android_app` in its constructor, before EGL was reached at all; `GHOST_ContextEGL` never pushed the `EGL_RENDERABLE_TYPE` its own comment required and pushed `EGL_SURFACE_TYPE` twice; all three Android context factories asked for desktop GL 4.x, which no Android EGL can bind; `WM_init_opengl()` reported no GPU backend because nothing on that path runs backend detection; and the first GPU render then aborted in Boost.Locale, which falls back to ICU data this engine does not ship. Workbench now renders at about 10 ms a frame. The patches and the reasoning are in `native/blender/patches/`.
- Blender's crash handler writes a crash file with an empty backtrace and then exits, so Android never wrote a tombstone either; the wrapper now passes `--disable-crash-handler`, which is what made the above findable.
- Uploading a model to the engine only copied the file into the import directory, so the engine kept whatever it already held; it now loads it.
- A restarted engine put back the file the user sent rather than the newest thing the engine produced, discarding everything done to it since.

## [1.2.1] - 2026-09-14

### Fixed

- The Blender engine fetch staged the binary, the Python stdlib, the Blender
  scripts and the datafiles but not the license texts, so a build from the
  released `blender-engine-arm64-v1.2.0.zip` produced an APK without the GPL
  texts and CI's own asset check failed on them. The script now stages them
  from the engine package, falling back to the tracked copy in
  `native/blender/assets/licenses`, and fails loudly when neither exists;
  `verifyDebugApkContents` checks they reach the APK.
- **AGP's default asset filter drops every directory whose name starts with
  `_`.** In the embedded CPython tree that quietly removed `numpy/_typing`,
  `numpy/testing/_private`, `setuptools/_distutils` and `pkg_resources/_vendor`,
  so `import numpy.typing` failed inside the engine, and it took one license
  text with it. The packaged assets now keep `_`-prefixed directories; dotfiles
  and VCS metadata are still ignored, `__pycache__` is pruned rather than
  shipped, and the APK check covers both the recovered package and the license.
- **The engine package shipped the engine without the libraries it loads.**
  `blender-engine-arm64-v1.2.0.zip` carried `libblender_exec.so` and the assets
  but none of the 120 runtime libraries beside it (bundled CPython, ffmpeg,
  OpenVDB, USD, OpenImageDenoise), and `app/src/main/jniLibs` is delivery rather
  than source - so a build from the release, the CI one included, produced an APK
  whose Blender engine could not be loaded at all. The package now carries
  `jniLibs/`, the fetch script stages it (falling back to
  `native/blender/blender-jniLibs`), and both the fetch step and
  `verifyDebugApkContents` fail when the runtime libraries are missing.
- **Builds were signed with a throwaway key.** AGP generates a debug key when
  none is configured, so every CI run produced an APK that refused to install
  over the last one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and left uninstalling -
  and losing the app's data with it - as the only way forward. `keystore/debug.keystore`
  is committed and wired into `signingConfigs`, so CI and local builds share the
  key the 1.2.0 APK was published with and upgrades work in place.
- **A `shutdown` command - or a port already in use - killed the whole app.** The
  MCP addon runs as `blender -b --python`, so *returning* from that script ends
  Blender's background main, and Blender's teardown calls `exit()`. Sending the
  documented `shutdown` (reproduced on the device) or starting the engine while
  9876 was held took the app process with it, losing whatever was unsaved; Android
  restarted the keeper service, which made it look like a restart rather than a
  death. The addon now parks instead of returning, records a failed bind in a
  status file, and serves again when the app asks through
  `blender_mcp_restart.txt`.
- **`native/blender/assets/startup/blender_mcp_slim.py` was never in the
  repository.** The MCP server that ships inside the app existed only in the
  delivery tree and the release archive, so nothing in git described what the
  engine actually runs - and a fix could not have reached a clone or CI. Both
  startup scripts are tracked now, and the fetch script lays them over the
  package's copies.
- **"Stop Blender engine" did nothing.** It set a native flag nothing read, so the
  engine thread, its socket and the loaded scene kept running while the UI said
  otherwise. Stop now sends the socket `shutdown`, cancels the exports watcher and
  logs what actually happened; the engine's memory is released when the app exits,
  which is the only thing that can release an in-process engine.
- **The engine's socket was open to every app on the device.** Any co-installed app
  could reach 127.0.0.1:9876 and run Python as this app's uid - reading the app's
  private files out of that socket was one request away. The app
  now generates a token, writes it next to the addon and sends it with every
  request; the engine refuses everything else. An engine started by hand with no
  token file stays open, which is the development path.
- The MCP server only understood one request per read: two requests arriving
  together were never parsed, never answered and never dropped, and the receive
  buffer had no ceiling. Requests are now newline-delimited as well as
  unterminated, the buffer is bounded, and a request that arrives while a command
  has been running for more than ten seconds is told the engine is busy instead of
  waiting out its own timeout.
- **The preview never returned to full size.** After a gesture - or a rotation, or
  collapsing the chat - the picture stayed at the 512-pixel interactive frame: the
  re-render wrote an equal camera back into snapshot state, which is not a change,
  and the render loop waits for a change. The render trigger is now its own state,
  so `Copy`-equal writes cannot swallow it.
- The published handoff was never imported if the engine was still booting when
  Modelling opened: a failed `isOnDefaultScene` read as "the scene has content",
  and the import was single-shot. The question now has three answers - yes, no,
  could not ask - and the import waits for the engine in the third case.
- An engine restart re-imported the export it had already handed over (replacing
  the model the user had just sent) and left another poller and FileObserver
  running: the newest export is claimed like any other, the watcher is cancelled
  and replaced, and the poller is owned by a job that can be cancelled.
- An export that arrived while the app was busy was claimed and then dropped, so
  the model was lost with no message. The newest one is now taken as soon as the
  running operation finishes.
- Camera writes ran on the UI thread, one file write and rename per pointer event,
  and an agent camera write could be adopted mid-drag, cancelling the gesture and
  jumping the view. Writes go to a serialised background dispatcher, and the agent's
  camera is adopted between gestures.
- A socket kept the timeout it was created with, so an import could be cut off at
  20 s or a render left blocking for 180 s; a reply split across a read boundary
  decoded into U+FFFD. Both are fixed at the connection.
- One transient engine-start failure disabled the engine for the life of the
  process, with no retry and nothing said; startup failures now re-arm.
- **PrusaSlicer prints over ~100 g were rejected after slicing.** The G-code policy
  bounded `M74 W` to 0..100 as a percent, but PrusaSlicer emits filament weight in
  grams (`GCode.cpp` `w = volume * density * 0.001`, and the shipped profile emits
  `M74 W[extruded_weight_total]`), so the app's own sanitizer threw on its own
  output.
- **Adaptive bed mesh was lost after any layer-event edit.** The base G-code was
  copied out before the AML block was injected, and re-applying layer events rebuilt
  the published file from that base, so the `C29` region quietly disappeared.
- **"All settings" values were ignored whenever an imported profile was active.**
  Only the standalone settings transport accepted them; the resolved-profile
  transport dropped them silently while the UI listed them as used by every slice.
- "Interface thickness" did nothing without an imported profile: the standalone
  transport overwrote the user's value with `layer height x 4`.
- The Prusa nozzle-path preview kept every move of a large print instead of
  sampling to the cap the Cura twin honours, and labelled the result as sampled.
- An extra "all settings" value was never validated: one blank or malformed entry
  was persisted and re-sent on every later slice, failing them all with a generic
  engine error.
- `PrusaConfigWriter.MANAGED_KEYS` had no reader and disagreed with the list the UI
  annotates from, so the "(managed by the app)" hint was wrong on both sides.
- A failure between starting PrusaSlicer and opening its log sink leaked the child
  process while its workspace was deleted underneath it.
- `scripts/setup.sh` - the advertised clean-clone path - built CuraEngine without
  `APP_JNILIBS_DIR` (so nothing was staged) and never fetched the Cura definitions
  (which are not in the repository at all), so it could not produce a working APK.
- The Gradle APK checks were orphaned: `verifyDebugApkContents` verifies CuraEngine
  only, and the Prusa and Blender checks that hang off it are run by nothing - while
  the README and CI present that command as the three-engine check.
- `fetch-prusa-engine-android.sh` died on an unset `ANDROID_NDK_HOME` halfway
  through a local staging run, and never recorded which run or branch its engine
  came from.
- The PrusaSlicer dependency cache was keyed on a static string, so a change to the
  deps build silently reused the old bundle (the workflow asked a human to bump a
  `-vN` suffix by hand).
- `.build-artifacts/prusa-engine/` (92 MB of a 2.9.6 engine) was committed to the
  repository and read by nothing; the workflow that wrote it now uploads an
  artifact instead.
- The Cura-resource version check in CI could not fail - the fetch script writes
  the file it greps - and the skills publish script only looked for addresses it
  already knew, reported "clean" for anything new, and printed the address it
  found into the log.
- Stale documentation: the front page still said 1.1.0, BumpMesh/filaSim were said
  to be prepared before `preBuild` (they hang off `mergeDebugAssets`), the notices
  pointed at an untracked directory and claimed a trademark licence that does not
  ship, and two runbooks described menu paths the 1.1.0 redesign removed.
- **The engine could still be left dead after a stop.** The addon cleared its
  running flag on `shutdown` but never closed the listening socket, so the park
  loop's rebind of the same port could fail with `EADDRINUSE` - and the app kept
  `started = true`, so it never asked again. The socket is closed now, the app
  waits for the engine to answer before believing it is back, and re-arms when it
  does not.
- A Blender export that arrived while the app was busy was stored from an IO thread
  and cleared on the main thread with a check-then-null, which could drop it after
  the engine had already claimed the revision.
- The exports `FileObserver` was never stopped, so every engine restart left
  another watch and thread behind, and camera publishes could reach the file out of
  order, leaving the agent reading a stale camera.
- **Model paths are escaped before they reach the engine.** They were pasted into
  a Python raw string, so an apostrophe in an agent-named export made every import
  a syntax error (and the retry loop then kept at it for two minutes), and a
  crafted name was Python running inside the app process.
- **The model VBO leaked on every placement change.** `glGenBuffers` ran on every
  rotate, scale, move, lay-flat and import while nothing in the app ever called
  `glDeleteBuffers`: a 200k-triangle model orphaned about 14 MB a tap, and once
  `glBufferData` failed the viewer drew from an empty buffer and the model vanished
  until restart.
- Smart Infill's mismatch guard could never fire - it asked the runtime that had
  just been cleared - so a stale package was never reported or dropped and later
  slices silently lost its density modifiers; its validation flag could also stick
  true and disable Slice until restart.
- The modelling standing brief was never delivered: it was sent in the same frame
  as the asynchronous connect, failed with "Connect to the harness first", and was
  never retried. Rotating on the modelling screen also lost the transcript and any
  in-flight reply.
- The model position fields were unusable in comma-decimal locales (pre-filled
  "12,5" and rejected as input), PrusaSlicer's "auto" extrusion width of 0 printed
  "flow Infinity%", and a rejected "all settings" value explained itself only on the
  Plate tab while the Add button lives on the Settings tab.
- The OctoPrint API key was deleted whenever a decrypt failed - a keystore that was
  briefly unavailable cost the user a credential only OctoPrint's web UI can
  reissue; the harness config was included in cloud backup although it is this
  device's business alone; and the harness launch token was stored, encrypted, for a
  request path that never read it - it is not stored at all now, and a ciphertext or
  Keystore key an earlier build left behind is deleted on the next save.
- Chat prompts were paired to turns by position whenever the counts matched, so a
  turn with no user message next to one with two showed the wrong prompt above an
  answer and dropped another.
- The webcam loopback guard tested an impossible byte pattern, so an address like
  `http://[0:0:0:0:0:0:0:1]` was neither rejected nor rewritten and the app could
  fetch its own loopback; the nozzle-path pan constants were about 2% off the eye
  distance the renderers actually use.
- A unit test covering the probe-points setting had no `@Test` annotation and never
  ran, the "real CuraEngine tests ran" CI proof also matched all-skipped suites,
  and the Blender addon had no automated check at all - CI now compiles it and runs
  a stubbed test of the token, framing, busy and shutdown-socket paths.
- **A failed engine start still ended the app process.** The park decision was read
  off the server's own `headless_driver` flag, which is set only once the bind
  succeeds - so a start that failed, a port still held most likely, looked like the
  desktop case and returned from the startup script. That return is the one path
  that ends Blender's background main, and Blender's teardown calls `exit()`, which
  takes the app and everything unsaved in it. The branch now keys on
  `bpy.app.background`, which is true whether or not the port was free.
- **Send to Blender could never load the model.** The hand-off client was built
  without the engine's token, so the engine refused the import and the retry loop
  kept at it for its full two minutes before reporting that the engine would not
  load it. The client carries the token now, so the import lands on the first
  attempt.
- **"Stop Blender engine" froze the menu.** Once a stop really reaches the engine it
  does socket work - up to two seconds to connect and five to read the reply - and
  that is longest exactly when the engine is busy, which is when a user reaches for
  the button. It ran on the main thread; the click now records the intent and the
  socket work runs off it. The keeper service no longer holds its wake-lock for the
  life of the process either: it takes a ten-minute lease that every engine command
  re-arms, so the engine keeps the CPU awake while it works and lets the device
  sleep when it does not.
- **"Octet" infill printed hollow parts.** The dropdown stored `octet`, which is
  not a value Cura's `infill_pattern` knows: the engine maps an unknown pattern to
  no infill at all and still reports a successful slice, so a part the user expected
  to be filled came off the printer as walls and skins. Cura calls that pattern
  `tetrahedral`, and that is what the dropdown stores now. Honeycomb and octagon
  spacing also ignored Cura's density-dependent factor, so those two patterns
  printed at a density of their own - and `infill_pattern` / `support_pattern`
  could still be set from the "all settings" extras after the line distance had been
  derived from them. They are refused there now, and a persisted value that
  contradicts the derived distance is filtered out on restore.
- **The geometry maths behind three features was wrong.** The conformal vertex key
  packed three quantised axes into one integer without masking them, so a negative Y
  sign-extended into the fields above it and two points that differed only in X
  packed to the same id - and those ids are the builder's only connectivity input,
  so unrelated facets were welded into one region and the wrong boundary measured,
  on any mesh whose coordinates cross the bed centre. Each field is masked to its 21
  bits now. The 3MF transform was checked on a single axis, so a
  degenerate scale on either of the other two reached the plate placement; it is
  checked on every axis. And the orthographic preview derived its pan scale and its
  projection from two different half-heights, so a drag did not match the finger and
  toggling Ortho/Persp rescaled the part under the user; both come from one shared
  value.
- **A save, an export and a slice could each lose work.** The debounced
  support-paint workspace save ran on the main dispatcher, and the painted-mesh
  descriptor has a hard size limit a large paint job reaches - an over-limit save
  threw an uncaught `require()` and took the process with it, so the save runs on IO
  and reports a failure like any other. An engine export that arrived after the UI
  went away was claimed by the departing view model's listener and then dropped with
  its scope; the listener is cleared with the view model now, which leaves the
  export queued for the next one to replay. Saving non-planar or conical settings
  deleted the published `slice-results/` directory on the UI thread, under a slice
  that might be reading it; the eviction goes through the publisher's own lock on
  IO. And a pending document export that failed deleted its file, which may well be
  the only copy the user has - it keeps the file and says the export failed.
- **UI state that did not stick, or stuck too long.** The Prusa setting writes were
  not ordered, so an older snapshot could land after a newer keystroke and revert
  it; they are chained now. Smart Infill's validation flag was owned by the package
  id rather than by the run, so re-validating the same package after moving it could
  clear the *new* run's flag and disable Slice; it belongs to the run. A failed
  settings commit was silent - the values looked saved and vanished on the next
  launch - and is reported now. `MeshPicker.invalidate` had no call site at all, so
  a large off-heap mesh stayed alive after **Clear plate**. And the modelling
  owner's camera write was the one publish that did not go through the serialised,
  rev-guarded writer, which is the write that could land out of order.
- **Nothing that arrives from outside is unbounded any more.** Harness responses
  were read whole, with no ceiling and no check against `Content-Length`; they are
  capped at 16 MiB now. The engine reply had no ceiling either, and was re-decoded
  and re-parsed once per 16 KB chunk, which was quadratic work for a peer holding
  the port open; it is capped at 1 MiB and parsed only once the last significant
  byte can close the object. Cura formulas could nest without limit, and a rejected
  one poisoned the whole profile instead of naming the setting; there is a depth
  limit and the setting is named. The OctoPrint file list recursed into folders
  without limit, the Prusa `.ini` read had no ceiling, and an absurd estimated-time
  comment in imported G-code threw `NumberFormatException` out of the parse - all
  three are bounded, and the comment is parsed defensively.
- **The platform surface shipped more than it needed to.** Auto Backup and device
  transfer carried the extracted engine tree (MCP token included), the models, the
  sliced G-code, the FEA reports and the imported project bundle - hundreds of
  megabytes against a 25 MB quota, all of it regenerated on the new device; they are
  excluded now. The WebView hosts answered any `http(s)` request from anywhere,
  subframes included, and handed any navigation to the browser; they 403 anything
  off the asset origin and pass only main-frame navigations on. `ACCESS_NETWORK_STATE`
  was requested and never used, so it is gone. And the WebView tools laid out under
  the system bars, where the clock and the gesture bar are; they apply the insets
  now.
- **A bare 403 cost the user the OctoPrint API key.** Any 403 erased the stored
  credential, including one from a proxy or a permission the key had nothing to do
  with - and only OctoPrint's own web UI can reissue it. The key is erased only for
  a same-origin API error that names it. The harness address also accepted a
  cleartext URL in silence, though the session cookie and any photo sent with a
  prompt travel in it; the chat says so now.
- **The release APK had no gate that ran in CI.** `verifyReleaseApkEngines` existed
  but CI called only the debug one, so the release variant was never assembled or
  content-checked by a pipeline - and a hand-cut release is exactly the build that
  ships. Running the gate also exposed that `assembleRelease` could not build at
  all, because release lint read the assets directory without depending on whatever
  prepares it. CI runs both gates now.

## [1.1.0] - 2026-09-12

### Removed


- Bead-angle overhangs, wall-anchored infill and bead-chain overhangs were
  removed from the engine, the app and the settings UI; masonry-bonded walls
  remain (their generator keeps its home in BeadAngleOverhang for that).
  Nothing depends on the removed settings; presets/imports carrying the old
  keys ignore them.

### Added

- Printer & onboarding (P5) and foldable layout (P6): More > Printer is now
  a full-screen destination with back navigation, a persistent safety
  checklist (build volume, nozzle, hotend limit, G-code, remote printing;
  PrinterChecklistStore) above the machine profile; a one-shot skippable
  first-run onboarding sets the machine values before the first slice; the
  Plate tab splits into viewer + session pane (summary chips, quick settings,
  actions) at 600 dp+ widths, e.g. on an unfolded foldable.
- Model storage off-heap and expanded-layout cleanup: STL meshes at or above
  200k triangles are parsed straight into a direct native FloatBuffer
  (VertexData), so the vertex data of a multi-million-triangle model no
  longer counts against the 512 MB app Java heap; every consumer (viewer,
  mesh picker, transforms, STL writer, envelope checks) keeps the same
  index/size API, and the parser thresholds are unit-tested
  (VertexDataOffHeapTest). On unfolded/foldable widths (>= 600 dp) the bottom
  Slice/Export action bar is hidden - the session pane owns the actions - so
  the expanded layout no longer shows two Slice buttons; the slice-blocked
  reason is shown in the session pane instead.
- Renderers now draw from GPU memory (VBO), like a game: the model mesh
  uploads once per model (positions+normals interleaved, plus the paint
  color buffer on change) and the nozzle-path geometry uploads once per
  path/color-mode (positions, normals, colors, ambient, travel) into vertex
  buffer objects; frames are pure GPU draws instead of client-side
  re-reads of the CPU arrays, with silent fallback to client pointers if a
  driver allocates no buffer ids. CPU-side native copies stay for mesh
  picking, STL export and transforms.
- Engine switcher with per-engine identity: the user picks the slicer
  engine (Settings > Slicing engine): Cura (blue theme) or PrusaSlicer
  (orange theme) - the entire app recolors instantly and profiles stay
  strictly per-engine (never merged). Persisted via SlicerEngineStore;
  the per-engine palettes live in EnderSlicerTheme.
- Nozzle-path renderer and camera overhaul: beads shade per face with
  analytic normals under a fixed three-light rig (key + fill + rim) plus
  per-vertex ambient occlusion at the bead base, so the path reads as a solid
  printed part from every orbit angle without the zoomed-out moire that
  interpolated normals caused; 4x/2x multisampled EGL config with fallback;
  an orthographic true-width camera mode, zoom level reporting, an explicit
  Fit control, tap-to-inspect move picking, and a shared bead-width resolver
  (renderer and inspector readout use the same flow math, now unit-tested in
  NozzlePathBeadWidthTest).
- UI/UX overhaul (round 1): pinned brand theme (amber engineering-cockpit
  palette, light+dark) replacing wallpaper dynamic colors; persistent bottom
  navigation with four destinations (Plate / Settings / Print / More) replacing
  the menu-driven single screen; Print settings and OctoPrint moved from modal
  sheets to full-screen tabs; new More hub grouping profiles, printer,
  configuration snapshots and experimental tools; the model-viewer turntable
  orbit is restored when the Plate surface view is recreated (see
  docs/ui-style-guide.md and docs/ux-redesign/DESIGN_PROPOSAL.md).

- Support painting now combines with both non-planar pipelines: painted
  enforcer/blocker prisms are warped with the same transform as the model
  (relief-field flatten for CurviSlicer, cone warp around the model centre for
  conical slicing) so CuraEngine generates supports against the warped solid
  and the G-code transform restores both together.
- The OctoPrint webcam card opens the snapshot in a fullscreen viewer with
  pinch zoom (1x-6x), drag-to-pan and a double-tap reset; OctoPrint flip and
  rotation settings are preserved and the view stays live while open.
- UI polish pass: the model summary card uses label/value rows instead of a
  text dump; gesture help is dismissible and separate from status; a swatch
  legend replaces the view-mode explanation paragraph; the layer timeline
  is easier to grab; travel moves are dimmer in the path view; position
  numbers are locale-safe (no dangling decimal separator); the rotate sheet
  labels its fine step row; Start is only offered on an operational printer;
  disabled export explains itself; shared spacing tokens and a style guide
  (docs/ui-style-guide.md) standardize new work.
- The nozzle-path view is now physically based: each move is rendered as a
  3D bead whose width follows the sliced flow (deltaE x filament area /
  length / layer height) and whose height follows the layer height, so
  slicing at 0.12 mm vs 0.20 mm visibly changes the geometry. The palette
  is desaturated with shadowed side walls instead of the glowing outline,
  and the parser now captures per-move flow and layer height for this.
- Nozzle-path beads are shaded with a fixed directional light per side
  face (in the bead's own hue) plus a subtle odd-layer tint, so layers
  separate visually and angled segment joints blend instead of showing
  flat dark triangles. Side walls now use a flat, 15% darker tint of the
  bead colour instead of directional lambert variation - the directional
  range made bead rows crawl into corduroy stripes and chevrons when
  zoomed out, the flat tint keeps every zoom clean. Sub-0.05 mm micro
  segments emit with zero width (their side walls painted tiny dark
  specks) and the side tint is 0.90x.
- The nozzle-path camera now uses the model viewer's turntable controls:
  rotation/zoom/pan orbit around the printed-part centre instead of a
  touch-dependent orbit pivot, with the same sensitivity constants,
  camera fit and a double-tap reset.
- Pinch zoom in the nozzle-path view is now anchored at the point between
  both fingers instead of the first finger's touch point: the world point
  under the pinch focus stays pinned while zooming (pan compensation on
  the gesture focus plane).

### Simplified

- Single source of truth for the non-planar preparation shared by both engine
  transports (NonPlanarPreparation), shared G-code formatting/quantization,
  atomic publication, and cooperative cancellation (GcodeTransformSupport),
  a shared machine-key emission table (MachineCuraKeys), shared Smart Infill
  width keys on the contract object, a shared hex-digest helper, and one
  shared settings-field scaffold for all settings sheets (SettingsFields).
- Removed the abandoned NativeSlicer JNI bridge (Kotlin stub, C++ adapter,
  CMake target) — the APK execs the packaged CuraEngine binary — the unused
  printer metadata fields, the unreachable inward-cone branch in the conical
  transformer, dead built-in G-code/printer assets, the superseded
  fetch-curaengine script, and two low-value tests (a data-class no-op and a
  near-duplicate layer-event test; the unique tab-separated safety case moved
  to the processor suite).

### Added

- Support painting now combines with both non-planar pipelines: painted
  enforcer/blocker prisms are warped with the same transform as the model
  (relief-field flatten for CurviSlicer, cone warp around the model centre for
  conical slicing) so CuraEngine generates supports against the warped solid
  and the G-code transform restores both together.

- **AI assistant:** a floating chat on the Plate tab that talks to a
  [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) over a
  tailnet. The harness protocol (auth bootstrap from `auth.json`, the
  double-wrapped request envelope, session lifecycle) lives in `harness/` and
  was verified against a live server rather than inferred. Sessions are rooted
  at the configured workspace, because skills are discovered from the session's
  working directory and one created elsewhere cannot load them; a stored id is
  checked against the session list before it is adopted, because prompting an
  id the harness has forgotten is accepted and then answers nothing; and the
  conversation is rebuilt when a rotation or process death drops the in-memory
  client, which otherwise leaves an empty chat with the setup panel already
  dismissed. Replies are read from the session log rather than the list
  projection, which clips every turn to about a hundred characters.
- **Photo to 3D:** *Build from image* uploads a photograph and asks the harness
  to model it. The upload is staged and then named in the prompt as a file
  part, which is the only way staged bytes reach the agent - uploading without
  it leaves the agent with a message that merely mentions an image. The
  finished STL arrives through the same export directory as any other Blender
  export, so it reaches the plate with no interaction.
- **Point-to-point annotation:** paint a line, or a chain of them, onto the
  model to show the assistant what to work on. Points are placed on a
  horizontal work plane, which removes the depth ambiguity of a flat tap, and
  can be dragged in z alone without disturbing the other two axes. Handles stay
  grabbable independently of where a segment was drawn from, so an existing
  point can be adjusted without starting a new one, and marker size follows
  perspective so nearer points read larger.
- **Blender export folder manager** (Plate menu, Storage, Blender files): lists
  what the engine has delivered, distinguishes what the app can actually read
  from what it cannot, and clears the folder.
- **Stop** in the chat cancels the running turn *and* tells the agent to drop
  the work a cancel does not reach: `session/cancel` stops the agent, never
  the processes it started, and a background job that finishes afterwards
  delivers a notice that starts a fresh turn on its own.

### Fixed

- The arc/wave overhang generators never triggered on-device: the pinned
  Cura definitions default bridge detection off, and the app never enabled
  it. The standalone transport now sets bridge_settings_enabled when an
  overhang feature is on, and the resolved transport sets it at model-mesh
  scope, so unsupported bottom skins are classified as bridges and the
  overhang fills can replace them.
- Support painting could make slicing take tens of minutes or time out:
  painted triangles become eight-triangle prisms, and the per-layer support
  computation grows superlinearly with the painted region. Painted regions are
  now capped at 5,000 triangles (about 40k prism triangles, a few seconds on
  the host engine) with a clear fail-closed error, painted modifier meshes
  skip the meshfix union pass, and conical prism refinement is capped at one
  level.
- Conical slicing with supports (automatic or painted) lifted the entire model
  off the build plate: support tower bottoms back-transformed below the bed
  and dragged the whole file upward. Support moves are now anchored to the
  plate and below-plate support layers are skipped.
- Conical preparation was effectively uncancellable: the cooperative
  cancellation checks never fired (interval mismatch) and the refine/warp
  loops lacked per-triangle checks; interrupted STL reads now surface as a
  clean cancellation instead of a ClosedByInterruptException.
- Importing a new model could persist the previous model's painted supports,
  which came back as phantom enforcers/blockers on the new model after a
  restart.
- Paint changes were only persisted alongside unrelated saves; recent paint
  could be lost silently on process death. Paint is now persisted with a short
  debounce.
- Strokes painted while a slice was running silently diverged from the
  exported G-code; painting is now ignored while the app is busy.
- Conical slices now auto-select the nozzle Path preview like CurviSlicer
  slices do.
- The Curvi G-code transform is now cooperatively cancellable (per-line and
  per-segment checks), resolved Cura requests reject duplicate modifier mesh
  names, and the settings-leak contract covers painted-support x non-planar
  combinations.

## [1.0.0] - 2026-08-17

First stable release.

### Added

- EasyConical conical slicing: STL cone warp with G-code back-transform for
  tilted-nozzle 4-axis printers (OUTWARD cone direction).
- Support painting: per-region support enforcers/blockers with brush picking,
  transported to CuraEngine as modifier meshes.
- Thickness-adaptive walls: automatic wall reinforcement at tight bends via
  modifier volumes.
- CuraEngine upgraded to 5.14.0-alpha.0 with pinned Cura resources; engine-drift
  defaults seeded for imported flattened project definitions.
- Arc-overhang and wave-overhang engine paths (experimental, off by default),
  smart overhang strategy, and CurviSlicer non-planar slicing.
- Smart Infill workspace with offline filaSim thermal FEA (experimental).
- Settings-leak validation suite pinning that advanced features, when disabled,
  leave core Cura slicing untouched in both engine transports.
- Real Gradle wrapper (9.4.1); JVM unit tests now run on a clean checkout
  without the native engine, downloaded assets, or Rust/wasm-pack.

### Fixed

- Adaptive-wall modifier slabs extended 0.1 mm below the build plate (and above
  the gantry on full-height parts), tripping build-volume validation on every
  adaptive-walls slice.
- APK packaging could ship the x86-64 `libcura-formulae-engine.so` from a shared
  Conan cache; the AArch64 library is now selected by ELF machine and the build
  fails loudly when it is missing.
- Conical slicing: back-transform bed contact, Z radius computed against the
  correct centre axis, adhesion/priming handled per EasyConical requirements.

### Changed

- Conical INWARD cone direction disabled: its warp geometry dips below the
  build plate for any real model; persisted selections are coerced to OUTWARD.

### Known limitations

- Single printable model, single extruder; no duplicate/auto-arrange workflow.
- Smart Infill, thermal FEA, arc/wave overhangs and the smart overhang strategy
  are experimental and need broader physical print validation.
- Non-planar slicing buffers the full transformed G-code in memory; very large
  or dense prints may need a raised Java heap (see README).
