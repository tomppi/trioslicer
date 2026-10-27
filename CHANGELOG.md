# Changelog

All notable changes to TrioSlicer are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.9.14] - 2026-10-27

### Added

- **A native BumpMesh texture studio, built like Smart Infill: the BumpMesh engine drives a native panel instead of its own web page.**
  Pick a texture, tile size, displacement height, mapping mode, smoothing and refine length, choose **Whole object** or **Painted
  region**, and press Apply - the result replaces the selected plate object in place, ready to slice. The engine runs headlessly in
  its existing bundled workspace (no vendored file changed), all **101 BumpMesh textures** are included (17.5 MB, +16.7 MB APK), and
  the old full-screen **BumpMesh texturizer** web view stays exactly where it was under *More -> Experimental*.
- **Undo in the side rail while a texture is applied.** The rail shows *Undo <texture>* after an apply; one press restores the
  previous mesh (and the pre-texture paint), removes the row and invalidates the slice. Separate from the placement undo.
- **The side rail's navigation block collapses with an arrow.** On a wide window the Plate/Settings/Print/More/Ask AI icons fold away
  behind a chevron so the action buttons get the height; the choice is remembered across launches, and a pending agent question keeps its
  badge visible even when the menu is collapsed.
- **Honest cost tiers on the Apply button.** Fast (<= 800 k triangles), Detailed (<= 2 M) and Extreme (2-8 M with a memory warning and
  a confirm); the studio estimates the result before it runs and refuses Extreme settings below the measured-safe refine floor. The
  mesh triangle limit is shown and can be raised from the studio itself, because a textured model is a much larger mesh than its source.

### Tested

- Measured through the real engine on 3DBenchy (225,154 triangles, limit raised to 8 M): Fast/Knurling = **415,619 triangles in 18.3 s**;
  the Armor library texture = **406,242 in 18.1 s**; a painted region (35,929 faces) = **271,436 in 11.6 s** with every untouched
  vertex preserved below the painted area; Extreme (tile 6 mm, 1.2 mm) = 2,260,270 triangles in 112.6 s with a 3.57 GiB renderer peak.
- Device walk on the dev phone: studio at Fold width (two panes) and phone width (one column), gallery *101 of 101*, estimate within
  +3.9 % of the result, apply 22.9 s, **Sliced - 242 layers**, rail *Undo Armor* restored the mesh, and the *More -> Experimental*
  web view row still opened the full BumpMesh page.
- Suite: **2,178 tests, 0 failures** (35 skipped) before the rail-collapse change.
## [1.9.13] - 2026-10-27

### Changed

- **The CAD agent sheet is two panes on a foldable or tablet and one column on a phone.** The
  sheet measures its own available width (`BoxWithConstraints`, 600 dp); at or above it the
  document and engine sit in the left pane and the conversation in the right, with the composer
  pinned to the bottom of its pane while the transcript scrolls. Below it the single scrolling
  column keeps its order and content. Every card is one composable called by both layouts, so
  the two arrangements cannot drift apart - the rule the Smart Infill workbench already follows.
- **The CAD sheet got the plate's width instead of Material's 640 dp phone cap.** Only the CAD
  sheet passes a `sheetMaxWidth` to its `ModalBottomSheet`: below a 600 dp window it keeps
  Material's 640 dp default (a phone is narrower anyway), at or above it uses
  `min(windowWidthDp - 24 dp, 960 dp)` - the window-minus-margins frame the Smart Infill panel
  fills. On the Fold's inner display the sheet is 714 dp instead of 640 dp (that window reports
  738 dp), so each pane is ~37 dp wider; a wide window in landscape gets 902 dp. Every other
  `AppBottomSheet` caller keeps the Material default.
- **The sheet's copy is one line per section instead of paragraphs.** The engine/authorship
  sentence, the network-off line, the export and open-file hints, the API-key handling, the
  web-tools trust line and the driving line are all short now; the facts that would become a
  lie if dropped (the agent is still the only author, the key is still sealed in the device
  keystore, web text is still untrusted, Convert is still the human's press) are kept.

### Tested

- `./gradlew :app:testDebugUnitTest --rerun-tasks` - 2,131 tests, 0 failures, 0 errors
  (35 skipped) across 385 classes.
- On the dev phone, the sheet was checked at the Fold inner display (2160x1812 @ 373 dpi,
  two panes with the composer under the transcript) and at 1080x2400 @ 440 dpi (one column);
  screenshots are in `.build/walk-131/`.

## [1.9.12] - 2026-10-27

### Changed

- **The bundled SolidCraft engine moves from 0.22.1 to 0.24.0.** A fresh arm64-v8a build of
  upstream tag v0.24.0 (commit 5965439a, MIT OR Apache-2.0, 62.6 MB / 65,657,208 bytes, AArch64
  ELF) replaces the old binary, and the `solidcraft-engine-v0.24.0` release asset is pinned by
  SHA-256 `82039615e5af8dd4c62cd95217d3baa500306439e70815230545f5c816699120` in the fetch script.
  **The app's contract against it is unchanged, re-verified on the phone:** the MCP tool surface
  is the same 19 tools by the same names; `select.set` with a face item, `model.faces` (box and
  cylinder, including the `cylinder` block), `solid.offset_face`, per-body `file.save_mesh`, and
  every `plastic.*` recipe answer exactly as they did on 0.22.1 - and every plastic volume, face
  and triangle count measured for the runbook is identical on 0.24.0.
- **Design files are now format 39, and the downgrade is one-way.** 0.24.0 opens every earlier
  design (a 0.22-era design from the app's workspace opened and measured unchanged), but 0.22 and
  0.23 refuse a design 0.24 saved: *this design was saved by a newer SolidCraft (file format 39;
  this version reads formats up to 37)*. The app bundles 0.24 only, so a design saved by this
  build cannot be opened by an older app - documented in `docs/SOLIDCRAFT_ANDROID.md` and the
  runbook, with "export STEP/STL/3MF" as the way to move a design to an older install.
- **The runbook teaches what 0.24 adds, measured on the phone.** The catalogue grew 461 -> 474
  commands in the same 39 families: `sketch.arc3d` (a 3D arc through three world points - measured
  r10 / 90 degrees / exact centre), WELDMENTS `frame.member` (two measured rect-tube members:
  44,400 and 35,520 mm3, 10 faces each), left/right-handed `solid.thread` (the modelled left M8
  is the mirror: 824.834 mm3, 5,337 faces, 52,088 STL triangles), `file.save_mesh quality`
  (45,456 / 181,920 / 727,392 triangles for a 100 mm sphere), and read-only `document.revision`.
  `plastic.snap_fit` gained `base_height` (measured: it extends the base below the picked face
  and can pierce a 2 mm shell floor) and `new_body` (the 144 mm3 catch becomes its own part). The
  nine new `view.*` and the component-colour commands are documented as engine-viewport only -
  they do not render on the plate.
- **No app behaviour changed besides the engine and the regenerated runbook asset.** The app's
  cached engine version constant, the notices/runbook/assets and the tests that pin the engine's
  identity now say 0.24.0; the app's code paths are the same.

### Tested

- The full `:app:testDebugUnitTest` suite: **2,131 tests, 0 failures, 35 skipped** (the 35 are
  the suite's usual device/engine-gated skips). `AgentToolCatalogTest` gained a synthetic
  `tools/list` test proving a tool the app has never heard of (`weldment.create`) reaches the
  model's tool definitions while the five desktop-only tools stay hidden, and
  `SolidCraftBridgeTest` proves the bridge forwards an unknown new tool unchanged;
  `SolidCraftSessionTest` and `FakeSolidCraftEngine` now answer as engine 0.24.0.

## [1.9.11] - 2026-10-27

### Fixed

- **A release build without the signing secrets fails instead of shipping a throwaway-signed
  APK.** A tag build (or a `workflow_dispatch` with `release: true`) with a missing or empty
  `KEYSTORE_BASE64` secret used to sign the APK with a generated throwaway key, skip the
  release-certificate pin and upload it; the update is then refused by every phone that has the
  published app (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and only an uninstall gets past it. It
  now stops at the keystore step with that reason. A branch or pull-request build keeps the
  warning and the throwaway key (a fork has no secrets), and the upload step never attaches a
  throwaway-signed APK as a release artifact.
- **The SolidCraft engine gate checks the architecture, not just the file size.** The fetch
  script, the CI fetch step and the two Gradle verify tasks now require a 64-bit little-endian
  **AArch64** ELF (`e_machine == 0xB7`), so a host x86-64 build (or a foreign or truncated
  binary) that used to stage cleanly fails loudly instead of installing an engine the phone
  cannot run.
- **A release tag must be on `main` and match the version.** Tag builds now fail when
  `versionName` differs from the tag, when `CHANGELOG.md` has no section for that version, or
  when the tagged commit is not an ancestor of `origin/main`; the checkout fetches the full
  history so the ancestry check can run. (The withdrawn v1.9.5 tag had been force-moved onto a
  commit that is not on main.)
- **Build artifacts are named per ref, and engine-asset tags no longer run the app pipeline.**
  The APK and build-log artifacts carry the ref (e.g. `enderslicercura-apk-v1.9.11`), so a
  release assembled by hand from an artifact can be traced to a run; `solidcraft-engine-v*`,
  `cad-payload-v*` and the other engine tags skip the app job instead of building an APK nobody
  can use.
- **`docs/SOLIDCRAFT_ANDROID.md` matches the code again.** The web tools are documented as at
  most 10 results with 2,000-character snippets/summaries and a 32,000-character fetched page,
  the removed eight-tool-round limit (and the "continue" sentence it produced) is gone, and the
  plate render is documented at its 1,536 px long side.
- **The plate's CAD document survives its own bursts, replaces and conversions.** The design's
  writer never cleared its job, so every edit inside the 1.5 s window was dropped and a burst
  could leave the file at its first edit; it is one self-clearing loop now, with a test that
  drives a burst. "New design" saved to `designs/<name>.solidcraft` where every new design is
  named "Untitled", overwriting the previous design's only source of truth - it takes a free name
  now. Connect agent no longer republishes a converted or split plate (it used to undo the
  conversion or silently drop a split); Convert and Convert again refuse a split layout by name
  instead of duplicating the part; the derived-layout guard is persisted, so a relaunch keeps the
  split and offers Rebuild plate; Snap Apply's objects join the set Rebuild filters; Convert
  clears the placement undo; a deleted design on a converted plate is named; and bodies whose
  readable slugs collide no longer share a file.
- **A face pick reaches any part of the document.** The pick was gated on the selected model and
  the selection was forced to the first part, so on a two-part document the lid's faces were
  unreachable with no message; the plate's hit is object-local and the view model subtracted the
  scene offset a second time, which broke every part but the first. The amber tint now stays on
  the face's part while that part is on the plate (not only while it is selected), Clear plate,
  Convert and arming another tool drop the face mode, and the render's tint scan looks for the
  tint mix itself instead of any warm pixel.
- **The question path says what happened and opens where the question is.** An open-chat request
  is consumed exactly once (a rotation no longer replays the last notification tap), asking for
  attention switches the chat to the in-app source, the card's history distinguishes *Answered*,
  *Answered from the options*, *Dismissed* and *not answered*, the notification body is neutral
  and is cancelled when its question is gone, and a document change resets the transcript, the
  agent's memory and a waiting question with a sentence that says so.
- **The agent cannot leak the turn or grow without bound.** Closing the activity cancels the turn
  (releasing the keeper, the wake lock and the document's authorship), the plate render uses the
  live plate view instead of one captured when the turn started (so a rotation no longer kills
  renders for the rest of the turn), a turn keeps only the latest four renders and has a
  per-request ceiling, a stopped turn is remembered so "continue" has an antecedent, tool rows
  carry conversation-wide ids, and an image refusal is only believed when the endpoint names
  image input and a capability.
- **Security and backups.** The SolidCraft MCP token and the converted STLs are excluded from
  Auto Backup and device transfer while the designs stay restorable; the published bridge caps
  concurrent pipes before authentication; and the untrusted-web fence carries a per-request nonce
  so a page cannot forge its closing line.

### Tested

- `ReleasePipelineContractTest` pins the release-pipeline contract read from the workflow and
  build files: the tag/ancestry/version gate, the release-ref-only keystore failure and the
  throwaway-artifact guard, the AArch64 `e_machine` checks, per-ref artifact naming and the
  engine-tag skip, plus the raised agent caps and the withdrawn-version note in the docs and this
  changelog.

## [1.9.10] - 2026-10-26

> **1.9.8 and 1.9.9 were built but never released** (their tags and GitHub releases were
> withdrawn); their changes are part of 1.9.10, and their sections below are kept for the record.

### Fixed

- **The transform gizmo's arrows are grabbable over any part.** The gizmo is drawn on top of the
  model already - the overlay pass disables depth testing and runs after every object - but its
  hit test also asked the geometry under the finger: a handle deeper than the model's surface at
  that point (beyond a 1.5 mm tolerance) was rejected as "behind the model", and the touch fell
  through to the camera. On a two-part plate that is every touch on the X and Y arrows of a
  selected lid, because those arrows sit at the lid's own mid-plane: the +Z arrow poked above
  the lid and worked, and dragging X/Y orbited the camera instead. The hit test is now screen
  distance alone (depth only breaks a tie between two handles on almost the same pixel), and a
  finger-down is offered to the gizmo's handles - and the joint's arrows - before the camera,
  the object pick, the face pick or any painting: an affordance drawn over the model belongs to
  the user who can see it. A touch that lands on no handle behaves exactly as before. The
  drawing order and the depth-test-free overlay pass are pinned by a test so they cannot drift.

### Added

- **Drop sits beside Done in the model tools card.** The app's drop-to-plate action (the one the
  undo line names "Model dropped to the build plate") is now a secondary button next to Done in
  the floating transform card, for every object the card can act on - imported meshes and CAD
  document parts alike. It is disabled with a named reason when there is nothing to drop (no
  selection, or an operation running) instead of a dead button, and it keeps the existing undo.

### Tested

- `GizmoOverlayOrderTest` pins that the gizmo pass runs after the model with `GL_DEPTH_TEST`
  disabled (and that no depth-range trick is the mechanism), that `ACTION_DOWN` offers the touch
  to the gizmo's handles before the joint picker, the face picker and finger-move, and that the
  gizmo hit test no longer consults the model hit ray. `GizmoDragTest` now covers a handle drawn
  over the model being chosen by screen distance even when nearer geometry exists, with depth
  only breaking a screen tie. `TransformGizmoOverlayTest` (Robolectric compose) covers Drop
  beside Done, the named-reason disabled state, and the undo row that names a model drop.
## [1.9.9] - 2026-10-26

### Changed

- **The in-app CAD agent has no tool-round limit.** It used to stop after eight rounds of tool
  calls with a named error and a "send 'continue'" sentence. That count was a guess dressed as a
  safety rule: a legitimate build - sketch, extrude, fillet, a dozen bodies, a save, an export, a
  slice check - can want more rounds than any number picked in advance, and the limit made the
  agent give up in the middle of one. A turn now ends when the model answers, when the user
  presses Stop, or when the endpoint or network gives up. What bounds the cost is that the loop
  runs in front of the user: every tool call is a row in the transcript, Stop is one tap away,
  and the turn holds a foreground notification. A long turn logs a heartbeat every tenth round so
  "still working" is distinguishable from "stuck".
- **Conversation memory is forty exchanges, not ten.** The context window sent on every round is
  still capped - the model's own window is the real bound and an unbounded conversation would be
  re-sent on every round - but ten was short enough that the agent could forget the start of a
  build inside one session.
- **The turn's wake lock is held for the turn, not leased for thirty minutes.** With no round
  limit there is no honest figure for how long a turn may last; the lock is released on every
  exit path (answer, error, cancel, service stop) and dies with the process, and the foreground
  notification is the visible proof it is held.
- **The other caps are raised.** Web search passes on up to **ten** results with **2,000**-character
  snippets, and a fetched page is cut at **32,000** characters instead of 8,000. An `ask_user` card
  takes **twelve** options and a **1,000**-character question. The agent's plate render goes from
  768 px to **1,536 px** on its long side (and a higher JPEG quality), so it can actually read the
  part it is looking at. The only caps left are the model's context window, the endpoint's own
  limits, and the image/response byte guards that keep a phone from running out of memory.

### Fixed

- **The bridge-proxy release test could fail the whole build.** `AgentBridgeProxyTest` asserted
  that the port was bindable again immediately after the listener was closed, and the kernel can
  take a moment to release a listening socket - 1.9.8's release build failed on exactly that
  ("Address already in use") while every local run passed. The check now waits up to two seconds
  for the OS to let go: a genuine leak still fails, a slow release does not fail the release.

## [1.9.8] - 2026-10-26

### Added

- **The in-app CAD agent can ask the user a question, and the app makes sure it is seen.**
  `ask_user` is the app's own tool (like `plate_screenshot` and `web_search`, not an engine
  tool): a question string, optional short options, and `allow_text` (default true). Executing it
  shows the question as its own **card** in the in-app conversation - not a normal agent bubble -
  with one chip per option, the composer for a free answer when allowed, and *The agent is
  waiting on you.* It also **ends the turn cleanly**: there is no blocking wait anywhere, nothing
  is spinning, the engine is not held, and the tool result says the answer arrives as the next
  user message and starts the next turn. The runbook gains the rule for when to ask (ambiguous
  requirements, a destructive or irreversible edit, a dimension or material the user has not
  given) and that asking ends the turn.
- **Attention when the chat is closed** - the user's "blinking ai bubble if its closed and the
  agent wants some answers". While a question is unanswered and the chat is not open, the rail's
  **Ask AI** action carries the one badge it can have: a small pulsing dot (idle it stays a plain
  action with no badge). If the app is in the background, a notification - *CAD agent is asking a
  question* - opens the chat on tap; if POST_NOTIFICATIONS was denied the rail pulse is the
  fallback, so a question is never silent.
- **No stuck wait state.** The pending question clears on answer, on Dismiss on the card, on any
  new prompt, and when the CAD document is closed or converted; once cleared nothing is left
  waiting or spinning.

### Tested

- New `AgentQuestionTest` (required question, trimmed/bounded options, closed-set `allow_text`),
  `CadAgentAskUserTest` (the tool ends the turn with one completion and no second request, the
  question is exposed with its options, no ask tool row, and a malformed question is a tool
  failure the model can correct), `AskUserFlowTest` (Robolectric compose: the card and chips and
  waiting line, a chip answers and clears the wait, Dismiss clears it without a prompt, a closed
  option set closes the composer), `CadQuestionNotificationTest` (posted once, title and text,
  cancelled on answer, can post the next question), `AskUserSourceTest` (only the ask_user path
  sets the waiting flag; every dismissal path clears it; the rail badge and notification
  conditions; the shipped runbook explains when to ask) and the catalogue test for the tool
  definition and its "END THE TURN" description. Transcript copy includes the question and its
  options; the rail badge is asserted shown only when asked. The harness's own ask-user remains
  untouched - this is the in-app agent's version of the same idea.

## [1.9.7] - 2026-10-26

### Added

- **A CAD document's parts are separate plate objects.** The plate used to draw the whole live
  document as one composed mesh: a case and its lid could not be moved, rotated, painted or
  converted apart. It now publishes one plate object per engine body - the engine's own body
  name as the plate name, `cad:<body>` as its identity, at the document's own coordinates - so
  every plate function (move, rotate, scale, split, snap fit, paint, arrange) works per part. An
  agent edit repaints only the bodies it named and re-applies each part's own placement and
  paint, so a part that grows keeps the place and the turn the user gave it. One body is one
  object, so a single-part design looks exactly as it did.
- **Convert to STL is its own press.** Slice takes STL triangles, so a live CAD document now
  greys the Slice button and tapping it names the reason - *Slice needs an STL - convert the CAD
  document first* - and a **Convert to STL** action appears beside it (and in the new SolidCraft
  menu). Convert exports one STL per part into the SolidCraft workspace's `converted/` folder and
  replaces the document's plate objects with those meshes, at the arrangement the user had given
  the parts; Slice can then run. Converting never happens as a side effect of Slice, Export or a
  repaint: after a conversion an agent edit marks the plate stale with a named reason and offers
  **Convert again**, and converting again replaces its own meshes in place.
- **A SolidCraft menu in the top bar**, beside Blender and CAD: New design, Open
  STEP/IGES/3MF/OBJ/glTF..., SolidCraft files, Save design, Export STEP/3MF/STL, Convert to
  STL/Convert again, and the engine's state line with connect, copy-token and stop. The
  SolidCraft items moved out of the CAD menu into it.
- **A SolidCraft files screen**: every design in the workspace with its name, part count,
  modified time and size, and two actions - **Load to plate** (asking first when another
  document or plate content would be replaced) and **Delete** (confirming by name, and refusing
  with *this design is on the plate; close or convert it first* when it is the plate's document).
  The empty state says *No designs yet - New design, or open a STEP file.* Nothing on this screen
  touches the plate except Load to plate.

### Fixed

- A converted plate keeps its STL parts after the CAD document is closed, instead of deleting
  models the user converted on purpose.
- The face-selection scene offsets and the plate render follow the per-part objects: a face is
  still resolved by name after an edit, and the HUD names the part it belongs to.

### Tested

- New `CadPartPublishTest` (one object per body, per-part repaint, placement carry, paint rules,
  id collisions, legacy object replacement), `CadConvertTest` (N converted mesh objects and no
  document objects, arrangement kept, Convert again reuses its ids, removed parts dropped),
  `CadSliceGateTest` (the three states and the stale reason), `CadDesignLibraryTest` (listing,
  sidecar, on-plate refusal, deletes leave converted STLs), `SolidCraftFilesScreenTest`
  (Robolectric compose: empty state, row, asking before Load, confirm-then-refuse Delete) and
  `SolidCraftMenuSourceTest` (the menu's items, the plate's gate read from `MainUiState`, and
  that Slice never converts). The workspace descriptor round-trips the per-part and converted
  state and still reads a 1.9.6 descriptor.
## [1.9.6] - 2026-10-26

### Added

- **Tap a face on the plate and the agent knows which one.** With a live CAD document on the
  plate, the CAD agent sheet's **Select a face on the plate** arms a pick mode: the tap goes
  through the plate's existing surface-pick hook and `MeshPicker` (one pick per gesture), the
  plate object's placement is undone to the document's coordinates, and the point is resolved
  against the engine's own `model.faces` report - planar when it lies on the face's plane,
  cylindrical when it lies on the cylinder, otherwise the nearest area centre. The face is then
  selected in the engine with `select.set {items:[{type:"face", body, index, point}]}`, so the
  engine's selection and the plate agree. A tap on a different object is named instead of being
  treated as the document. Select mode exists only for a CAD document: on an imported mesh the
  sheet says by name that an STL has triangles, not engine faces.
- **The selected face is tinted in the plate's own renderer.** `SelectionTintBuffer` is one byte
  per scene vertex - the paint buffer's shape, a buffer of its own - and the mesh shader mixes a
  warm amber over the shaded colour of every triangle that satisfies the face's predicate. It is
  outside the support palette (interface cyan, supports magenta) and the layer preview's range,
  it rides the face through an orbit, and it is independent of painting in both directions:
  selecting never writes a paint buffer, a paint stroke never tints, and arming either mode
  disarms the other. A HUD chip over the plate names body, face and index, surface, area and
  which way it faces from the camera, with **Clear** one tap away.
- **The next prompt carries a SELECTED FACE block.** Body, face name and index, surface, area,
  centroid, outward normal (from the picked triangle, so a hole faces into the hole), the point on
  the face that a face command takes, and a view-relative description ("top", "the face facing
  you"). The shipped runbook gains the rule: act on that face - `solid.offset_face`,
  `solid.shell`, `plastic.*` - rather than guessing from a description, and quote its name and
  area. A message that carries a face selection carries a plate render of it too, behind the
  sheet's new **Lend the agent my view** switch (default on).
- **The agent can see the plate.** `plate_screenshot` is the app's own tool beside the engine's
  (`AgentToolCatalog`/`CadAgent`, no engine call): `ModelSurfaceView.capturePlate` asks the
  renderer for the next frame, the frame is read back from the plate's own frame buffer at the
  user's camera, scaled so the long side is at most 768 px and encoded as a JPEG. The picture is
  the plate view alone, the amber selected-face tint included - never the app around it and never
  the engine's `screenshot`, which is still refused. Its result is one line ("rendered the
  plate: 1 body, 12,000 triangles, current camera; the image follows") and the runtime attaches
  the image to the next user-role message of the same turn as the standard OpenAI content part
  (`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}`), with a caption saying
  the selected face is tinted when it is.
- **Vision capability is handled honestly.** A 400 whose body names the image (image/vision/
  multimodal) means the model cannot see pictures: the app strips the images, re-sends the same
  request once text-only, records *this model cannot see images; sent the geometry description
  instead* in the transcript and remembers it for the session - no further render is attached and
  the transcript never claims one was seen. A 400 that is not about the image keeps its own named
  failure. **Copy transcript** never carries an image payload: a render is named by its size and
  dimensions, and a data URL that ever reached a message is replaced with a marker.

### Fixed

- **A selected face survives the agent's own edits.** A repaint re-tessellates the document and
  renumbers every triangle, so the selection is not carried as indices: the face's engine NAME is
  looked up again in the new body's `model.faces`, its triangles are re-tinted and the engine's
  selection moves to its new index. A name the edit removed drops the selection with a sentence
  that names it; a new document, an import, a split, a snap fit or a Rebuild plate drops it the
  same way. Orbiting never does - a face is not a screen position.
- **The sheet's endpoint save no longer resets the new switch.** `saveAgentSettings` carries the
  stored **Lend the agent my view** choice forward, the same way it carries the web-search one.
- **Rotating the phone no longer deletes the conversation.** The CAD conversation, the modelling
  chat's messages and their status/busy flags were composable `remember`s, and the manifest has no
  `android:configChanges`, so the activity recreate behind a rotation threw them away. They are
  ViewModel state now (`MainViewModel`: `cadConversations`, `cadBusy`/`cadStatus`, the harness
  chat's own `harnessCadBusy`/`harnessCadStatus`, `modellingMessages`,
  `modellingBusy`/`modellingStatus`, the web-tool notice and the chat's selected source), which
  the Activity's ViewModelStore keeps across the recreate: the sheet and the chat overlay read the
  same state, so the transcript, the **Working…** state and the selected source are still there
  after a rotation. The two backends' statuses are separate, so a harness reconnect after a
  rotation cannot show its failure in the in-app conversation's status line. The in-app turn itself now runs in the ViewModel's
  scope instead of the composition's, so a rotation neither cancels it nor loses the tool rows it
  already produced; the harness chats are re-adopted from their stored session ids and reload their
  transcripts from `messages()`, and the in-app conversation is left untouched. This is
  configuration-change survival only - deliberately not persisted to disk, so a process death
  starts an empty conversation. The harness connection flags, the sheet's unsaved endpoint fields
  and its draft also survive a rotation (the API-key field deliberately does not: a typed
  credential is not put in saved instance state).

### Tested

- The face mapping (a tap on a known triangle resolves to the expected face name and centroid with
  the plate placement undone), the tint covering exactly the face's triangles, the tint buffer
  being separate from the paint buffer, `select.set` being called with the body/index/point, the
  prompt block's numbers, the no-paint rule, the repaint re-resolution and the dropped-face
  sentence, the plate render attached as a content array with a `data:` URL, the text-only
  fallback with one retry and one transcript line, and the transcript copy carrying no base64 -
  all hermetic against `FakeOpenAiServer`.
- Walked on the dev phone against a scripted endpoint (version 123, the walk in
  `.build/walk-123/`): a tap on a face, the HUD chip and the amber tint, "make this face 2 mm
  thicker" answered with the face's name and area and the plate showing the thicker face, the
  image arriving at the endpoint (decoded back to a PNG in that directory), and the text-only
  fallback once.

## [1.9.5] - 2026-10-26

### Added

- **The plate's model can be a live CAD document.** SolidCraft 0.22.1 - a pure-Rust parametric
  CAD engine (MIT OR Apache-2.0) - is packaged as `libsolidcraft_exec.so` the way the OCCT
  console is, started as a child process, and driven over its own stdio MCP server. The app
  exposes that session to an AI agent on loopback port 9878 behind the same kind of token as
  the Blender (9876) and Python CAD (9877) engines. The model screen is still the app's
  renderer: the engine's own viewport and `screenshot` tool are never used.
- **Every agent edit repaints, and only what it changed.** The engine answers a feature command
  with the bodies it created, changed and removed; the app exports just those bodies, patches
  their triangles into the plate object in place, and keeps the object's identity, its
  placement and the camera. There is no debounce and no file-loading step: a box, a fillet, a
  cut and a parameter change each land as the model changing shape. Undo, redo, a parameter
  rebuild and an opened file have no body report, so the whole document is re-read body by
  body - still the same scene, not a reload.
- **The human keeps every plate function.** Move, rotate, scale, lay flat, drop to plate,
  reset, split, snap fit, support paint, multi-object tools, Slice, Export and the
  Model/Layers/Path viewer switch all act on a derived mesh. A document change re-applies the
  plate transform (a growing part keeps its turn and its place) and keeps paint while the
  triangle count matches; a painted mesh that no longer matches is named as cleared. A split
  or snap layout made from the previous shape marks the plate stale with a named reason -
  Slice refuses it - and **Rebuild plate from the document** takes the new shape.
- **CAD files open as documents.** Import > **Open CAD document** opens STEP, IGES, 3MF, OBJ
  and glTF through the engine's own io crate as a live document the agent can edit. **Import
  model** keeps every existing route, including OpenCASCADE's STEP/IGES to mesh conversion;
  which item was used is which engine answered, and the status line says so.
- **Slice writes the print STL.** Pressing Slice on a CAD document exports a fresh STL into
  the workspace and slices it through the unchanged pipeline; an unchanged document reuses the
  recorded export and the status line says which happened. STEP/STL/3MF export stays a
  separate, explicit action in the CAD agent sheet.
- **An agent sheet on the plate**: the sidebar's CAD agent button, engine state, connect/stop,
  the biometric-gated MCP token copy, a composer that asks the harness agent for changes, the
  STEP/STL/3MF exports, and Close/Rebuild. It is the only human surface that asks for a
  document change; the document itself has no editing UI.
- **Every agent bridge can be reached on the network, and the app prints the line to paste.** A
  **Reachable on the network** switch in the CAD agent sheet, off by default, has the app
  republish all three of its agent engines on the device's own network address: Blender MCP
  (9876), the Python CAD engine (9877) and SolidCraft (9878). The engines and their payloads are
  untouched - each still binds 127.0.0.1 - and a small forwarding proxy (`agent/AgentBridgeProxy`)
  listens on the device address at the same port number and copies bytes both ways, so the line
  stays simple: `tcp://<this device's address>:9876` (and `:9877`, `:9878`). No adb forward, no
  serial and no tailnet serve rule is needed. The sheet lists one row per engine with its
  copyable line and its state - **app bridge up** or **engine not running** - so a stopped engine
  reads as a stopped engine, not as a dead network. The address is chosen from the device's own
  interfaces, not from a device or network name: a tailnet address (100.64.0.0/10) first, then a
  private LAN address (10/8, 172.16/12, 192.168/16) on a real interface, then the mobile data
  address only when there is no Wi-Fi or Ethernet one, and a named sentence - naming both
  ranges - when there is none. The choice is stored in the app's own preferences file, the
  listeners follow a changed address, and turning the switch off closes every listener before it
  returns. The proxy is transport only: each engine's token, file and per-message check are
  unchanged, the proxy checks no token and logs no message content, and the sheet says that what
  turning it on exposes is bounded by the network (a tailnet's ACL, or the LAN) plus those tokens.
- **One Copy is the whole hand-over: the line and that engine's token.** Each row's **Copy** in
  the CAD agent sheet now puts a single ready-to-paste line on the clipboard -
  `tcp://<this device's address>:<port> token=<token>` - instead of the address alone, so
  connecting an agent is one fingerprint prompt and one paste instead of copying the address and
  the token separately and combining them by hand. Each row reads that engine's own token file
  (Blender's, the Python CAD engine's, SolidCraft's); a row whose engine has never run has no
  token file yet, so it says **start the engine first - its token is created when it starts**
  and its Copy is disabled - it never copies a line without a token. The token is read only
  after the prompt, the clipboard item is marked sensitive so Android does not preview it, the
  toast names the engine and never the token, and the token is never rendered in the sheet or
  logged. The token-only **Copy MCP token** button remains only as the fallback when no
  published row can offer the one-line form (the switch is off, or there is no network
  address); while a row is copying the address and the token together it is hidden rather than
  being a second path to the same credential.
- **The CAD document has its own agent inside the app - no computer in the loop.** The
  "Ask the agent to change it" box in the CAD agent sheet now talks to an
  **OpenAI-compatible chat-completions endpoint straight from the phone**, and the SolidCraft
  tools run **in process** against the engine session the app already started: no harness, no
  MCP bridge, no token and nothing to connect on a desk. The endpoint defaults to DeepSeek
  (`https://api.deepseek.com`, model `deepseek-chat`) and both fields are editable in the
  sheet; the API key is sealed with the device keystore through the same `SealedValue`
  arrangement as the Moonraker key, is sent only in the Authorization header, and is never
  rendered in the sheet (a mask), never logged, never part of the chat transcript and never in
  an error line - server text is redacted against it. The tool catalogue is the **engine's own
  `tools/list`**, read over its stdio and converted to OpenAI function tools each turn, so the
  in-app agent and an external bridge agent are offered the same list from one owner; the five
  tools that do not apply on the plate are hidden (screenshot - the plate is the viewport -
  crash_reports, running, pending, skill_reference). The system prompt is the app's SolidCraft
  runbook, `docs/skills/solidcraft-engine.md`, copied into the APK at build time by
  `:app:copyAgentRunbook`, plus the live rules only the app knows: the agent is the document's
  only author, the user is a spectator, every edit shows on the plate, never use the engine's
  screenshot, and the camera is the user's. The loop is bounded (8 tool rounds) and cancellable
  (Cancel disconnects the request in flight); HTTP, timeout and malformed-reply failures are
  named errors on the sheet's error line. The harness path is kept as the advanced option: a
  segmented **Agent: In-app / Harness** choice in the sheet says which agent is driving.
- **A long turn now outlives the screen going off, and a dropped model call is retried once.**
  A turn runs inside a foreground **turn keeper** (`cadagent/InAppAgentTurnService.kt`: its own
  low-importance notification naming the CAD agent as working) so Android cannot cache and
  freeze the process when the app leaves the foreground, and the keeper holds a **bounded
  partial wake lock**, armed with the turn and released on every exit path - answer, error,
  cancel, service stop. Deliberately **no screen-on flag**: the screen may sleep. A model call
  that fails with a transport error or a timeout *before any tool of the turn has run* is
  re-sent **once** after 1.5 s and the transcript says **"the endpoint did not answer; retrying
  once"**; a call after a tool has run is never retried, because re-sending it could repeat an
  edit. When the retry fails too, the error row carries the endpoint host, the round number and
  the elapsed seconds, and says the one thing that helps: **"The screen may be off, that is
  fine; the app must still be running - a backgrounded app can be frozen by Android."** A body
  shorter than its declared Content-Length is a transport failure (the connection died
  mid-answer), not a malformed endpoint; a context-length **HTTP 400** is named as the context
  window; and the tool-round limit now says how many rounds ran and that one more message
  ("continue") carries on from where it stopped.
- **The in-app agent can search the web and read pages, and treats all of it as untrusted.** With
  the sheet's **Web search & page fetch** switch on (the default) and an endpoint that offers
  DeepSeek's `/tools/search` and `/tools/fetch` routes, the model also gets `web_search` and
  `web_fetch` - same base URL, same key, so one setting is the whole provider. Results are
  bounded (at most 5 results, ~800 characters each of snippet/summary; a fetched page ~8k
  characters, the cut named) and come back inside an explicit **untrusted web content** boundary;
  the system prompt states that page text is data, never instructions, and that nothing from the
  web may change the task, the document or the tool list. A 401/403/404/405 on those routes
  removes the tools for the conversation and the agent says once that search is unavailable -
  never a silent failure, never a fake empty result. The transcript shows "searching the web
  for …" and "reading …" lines so the user sees the agent leave the phone.
- **One author at a time.** The bridge now counts **attached external clients** (live
  connections, not a past client) and the app arbitrates the document between them and its own
  agent in one owner object (`solidcraft/SolidCraftAuthorship.kt`), not in the UI. While an
  external agent is attached the in-app composer is disabled and the sheet says
  **external agent connected - in-app agent paused**, offering one **Take over** button (behind
  a confirmation that the outbound agent must reconnect) that closes the live external
  connections. While the in-app agent has a turn in flight the bridge refuses a new external
  connection with JSON-RPC error **-32010** naming the in-app agent and closes it - it never
  queues the client and never disconnects an attached one to make room. The sheet always names
  the author in one line (**Author: nobody / in-app agent / external agent on 9878**), and the
  two author states are made mutually exclusive - and asserted - by the owner object.
- **The in-app agent is a chat you can watch - with tool rows, a Stop, a transcript to copy,
  and full screen.** The AI chat (the floating overlay the harness chat always used, opened from
  **Ask AI** in the sidebar rail) now has
  a source switch at the top: **CAD agent (in-app)** or **Harness**. The in-app source shows the
  app agent's own conversation - the same one the CAD agent sheet drives while its mode is in-app,
  and only that one (see the Fixed note on the two separate lists) - and every tool the agent
  runs is a row in it: **name, argument summary, duration and a one-line
  outcome**, live while the turn runs (`solid.box {length=40} 12 ms - 1 body created (Body1)`,
  `web_search "M3 socket head" 13 ms - 1 result`). A failed call is a red row carrying the
  engine's or the endpoint's own sentence, not silence. The rows are built from a redacted
  trace (`cadagent/AgentToolTrace.kt`): the API key never reaches a row, a transcript or the
  copy. While a turn runs the chat shows **Stop**, which is the same cancel the sheet's Cancel
  uses (the request in flight is disconnected and the loop stops at the next boundary - not the
  harness's session cancel, not an engine stop); the sheet's compact ask box keeps working and
  shows the prose only. **Copy transcript** puts the messages and the tool rows on the clipboard
  as plain text ("You: …", "tool: solid.box … 12 ms · 1 body created (Body1)", "Agent: …") so a
  bad turn can be handed to a human or another agent; no credential is in it. The header always
  names the author (`Author: nobody / in-app agent / external agent on 9878`), and an attached
  external agent reads as **in-app agent paused** there too. The chat has **three sizes**: the
  bubble, the floating panel, and **full screen** via the new expand button (diagonal arrows) -
  collapse it back with the header's collapse button or the Back gesture, which returns to the
  panel rather than closing the chat (a second Back closes it). In full screen the chat is one
  surface over the running plate with the source switch, the author line, the transcript filling
  the height (a readable column on a wide window) and the composer and Stop pinned at the bottom;
  a resize never stops the engine or the turn and never resets the scroll. The chosen size is
  remembered for the session: a chat left full screen reopens full screen, one left as a panel
  reopens as a panel, and the bubble is a size, not a close.
- **The AI chat has its own door in the navigation rail.** **Ask AI** now sits in the sidebar
  rail's own list, beside Plate / Settings / Print / More, in both shapes the rail has: the fold's
  permanent column and the phone's collapsible sidebar. It is an action, not a destination: the
  row is drawn exactly like its neighbours - same width, padding, icon and label - but it carries
  no badge and no selected state, and tapping it opens the AI chat overlay - over whichever tab is
  showing - with the size the chat was last left at (bubble, panel or full screen) without
  changing the selected tab; a turn that
  is already running is simply there when the chat opens, with its tool rows, Stop, Copy
  transcript and Author line as they were. The Blender menu's **Ask AI** entry is gone - the
  Sparkle glyph now means the chat itself and the rail is its one primary door - and the strings
  that used to point at "the sparkle button" (the CAD agent sheet's harness note and the modelling
  and CAD harness hints) now name **Ask AI in the sidebar rail**. The CAD menu's CAD-agent entry
  keeps its own item: it opens the document sheet, not this chat.
- `scripts/build-solidcraft-engine-android.sh` (cargo cross-build, strip, stage, optional
  release tarball) and `scripts/fetch-solidcraft-engine-android.sh` (SHA-256-pinned release
  asset, or a local tree). `docs/SOLIDCRAFT_ANDROID.md` records the architecture and the
  per-function behaviour; `docs/skills/solidcraft-engine.md` is the agent's runbook - now
  delivered twice from one file: to external agents as the skill, and to the in-app agent as an
  APK asset copied at build time.

- **The other half of the engine is now in the runbook.** SolidCraft's catalogue is 461 commands
  in 39 families, and the app had been using about twenty of them. The plastic family is
  measured on the device and written into the runbook the agent reads: **plastic.snap_fit** (a
  cantilever hook and its catch - +132 mm3 in 52 triangles and 0.17 s on a shelled case),
  **plastic.boss** (screw boss with a hole, fillets and optional ribs - +791.6 mm3, 1,380
  triangles), **plastic.lip** and its groove, **plastic.rest**, and the **rules** that carry a
  material's wall thickness, clearance and draft (ABS 1.5 mm: 1.5 thick, 0.1 clearance, 2 deg)
  - assigning a 1.5 deg rule turned a cylinder boss into a cone and removing it rebuilt the
  cylinder, because the rule owns the geometry. Alongside them: **mesh.convert** (a box STL's
  12 mesh faces became 6 planar B-rep faces in 0.15 s; a sphere is refused by name), **modelled
  against cosmetic threads** (5,337 faces and 52,242 triangles against "the B-rep is untouched
  and the export is the plain cylinder"), rib, web, emboss and deboss, **inspect.*** (minimum
  radius, clearance, interference - and the honest note that inspect.draft returns no per-face
  numbers), timeline rollback and suppress, materials with mass properties, and surface
  thickening. The runbook states the rule plainly: on a CAD document use the engine's plastic
  features, not the plate's mesh-level snap panel - that panel is for imported meshes - and
  sheet metal stays out of scope.

### Fixed

- **A new design no longer leaves the previous model on the plate.** new_design (and open) on
  an empty document dropped the engine's bodies, but the plate kept drawing the old picture
  while the status line said the engine had no bodies yet. The document's plate object is now
  dropped, imported meshes stay, a published slice is invalidated, and the status sentence is
  kept; a re-read that *fails* is now distinguished from a document that *is* empty - it keeps
  the last picture and says the engine did not answer instead of claiming the design is empty.

- **An in-app chat can no longer show the harness session's transcript.** The two CAD backends
  were writing one shared `cadMessages` list, so a conversation had with the harness agent (a
  case discussed on the user's PC, in the report) turned up under **CAD agent (in-app)** - which
  also made it impossible to tell which backend a transcript belonged to. `ui/CadConversations.kt`
  now holds two lists and every write is one-sided: the in-app loop appends only the in-app
  messages (tool rows included), the harness poll repopulates only the harness list from
  `cad.messages()`, and a tool row can only exist on the in-app side. The chat's source switch
  and the CAD agent sheet's mode chips are **one shared selection**, so typing in either box
  reaches the backend the chat shows, and the sheet renders the active backend's list. The header
  names the backend and its state - **In-app - `<model>` at `<host>`** or **Harness - session
  connected / not connected** - with the Author line unchanged underneath, and each empty
  transcript says why: **No in-app conversation yet - ask for a change** / **No harness session -
  connect the harness from the Harness tab**. The in-app conversation is not persisted, so it is
  cleared when the CAD document is closed or a different document is opened - a transcript from
  another document is worse than an empty one. The overlay's Harness source is now the CAD harness
  session the sheet's Harness mode drives; the old general Ask-AI overlay chat (and its
  Build-from-image upload) went with the Blender menu door the rail replaced.
- **DeepSeek's search and fetch are found when the base URL is the API host.** The shipped
  default base is `https://api.deepseek.com`, which answers chat completions, but the search
  and fetch tools live under `/v1/tools/...`: a base URL without `/v1` made the first
  `web_search` come back 404 and removed the tools for the conversation. `WebToolClient` now
  tries `<base><path>` and then `<base>/v1<path>` - only 404/405/501 are retried, so a wrong
  key is still a 401 named immediately - and remembers the form that answered, so a session pays
  the first 404 at most once. Tests pin both candidates, the remembered prefix, and that a base
  already ending in `/v1` is not doubled.

### Notes

- **The in-app agent's loop and the chat surface were walked against a scripted
  OpenAI-compatible endpoint, not a real provider.** No key was entered for this build: a real
  hosted chat completion and DeepSeek's `/tools/search` have not been called from the app. The
  endpoint's own errors are named on the sheet (and in a red tool row), so a wrong key or URL
  reads as 401 or 404 rather than as silence.
- The engine binary is a release asset published separately from this commit (CI has no Rust
  cross toolchain): publish `solidcraft-engine-arm64-v0.22.1.tar.gz` at the tag the fetch
  script names, as the OCCT engine's is.
- Display and print tessellations are the same SolidCraft STL export - 0.22.1's `file.export`
  has no tessellation-quality parameter - so "display" and "print" name when it is taken.
- The plate's layout after a split or snap joint is not re-split automatically when the agent
  changes the document: the stale state is named and Rebuild is the deliberate way forward.

## [1.9.4] - 2026-10-25

### Fixed

- **Build-up drew a slice of the print instead of the print itself.** The Layers tab plans the
  layers whose geometry it holds in the vertex buffers as a window, and that window was the
  selected layer plus eight below and two above - right for **Current layer**, which only ever
  shows one layer, but Build-up draws the accumulation, so a 586-layer print showed a thin band
  near the scrub and stopped there. Build-up now holds **layer 0 to the selected layer**: at layer
  301 of 587 the footer reads 301 layers in the buffers, and the stack stands on the bed. Stepping
  the scrub up **appends** the layer it adds (about 2 ms) instead of rebuilding the stack (about
  200 ms), the budget comes from the device's own profile rather than a constant, and a device that
  genuinely cannot take the whole stack holds the layers under the scrub that fit and names both
  numbers.
- **The model was painted in the support interface's colour.** The layer view colours model paths
  by speed on a hue sweep from 240 degrees (slow, blue) to 0 (fast, red), and the 180 to 240
  degree section - cyan towards blue - was written with its neighbour's formula. A 200 mm/s path,
  which is what every wall and infill line of a normal Cura slice carries, therefore came out
  magenta: the exact colour the palette reserves for the support interface, so the whole model
  looked labelled support interface while the counts beside it said 728,314 model paths against
  1,460 interface paths. The ramp is one function now (PreviewColors) with its sections in order,
  and the model reads cyan at normal print speeds.

### Notes

- **No printed pair has been clicked together yet.** The snap joints are verified in the app, in
  the geometry they build and in the meshes they produce - not in plastic. Treat the first print
  as a test: **Engage**, **Wall** and **Depth** are the numbers to move, and a drooping undercut
  prints deeper than drawn.
- **The Cura slice pipeline still parses its layer preview with the conservative 800,000-segment
  cap** rather than the device profile, so a Cura slice whose paths exceed it stores folded runs
  (the footer says so: nothing is dropped). PrusaSlicer and OrcaSlicer already use the device's
  share; matching Cura to them is a separate change.

## [1.9.3] - 2026-10-13

### Added

- **The plate's sidebar holds the settings you choose.** The rows under the plate's quick settings were a
  fixed four - layer height, infill, supports, adhesion. They are now yours, **per engine**: an **Add**
  button on the sidebar itself (and the same control in Print settings) opens a searchable picker over
  that engine's own settings, with the chosen rows listed in order to move up, move down or take off.
  Cura, PrusaSlicer and OrcaSlicer each keep their own list, exactly as their profiles and sheets are
  separate. Every added row has a working editor built from the setting's own metadata - a number gets a
  slider over the range the engine declares, a switch gets an off/on - and it writes the same setting the
  settings sheet writes. A setting that cannot work on the sidebar says so (a text setting is not a
  number or a switch; one the app manages is named as such) instead of being added dead. The sidebar
  scrolls when the list grows, and the Add row stays at its foot wherever you have scrolled to.
- **A settings snapshot for PrusaSlicer and OrcaSlicer.** Cura profiles already recorded what an import
  set, and offered to put it back. Importing a PrusaSlicer preset or an OrcaSlicer profile now does the
  same: the profile block shows the imported file as a **configuration snapshot**, and **Reset all app
  overrides** returns to it. That is deliberately not the same as **Reset to engine defaults**, which is
  still there and goes back to the engine's own values; the confirmation says which one you are doing.
  The snapshot survives a relaunch and a restored workspace, and a setting the engine will not take is
  named rather than dropped.
- **Two settings menus on a fold, and a settings panel on a brick phone.** On a wide screen the Print
  settings screen is two independently scrolling menus: the left carries the profile block and the first
  half of the sections, the right carries the rest, so twice as many settings are in front of you and
  nothing is listed twice. On a brick phone the same rail is available as a panel that folds out over
  the plate.

### Changed

- **The Plate's floating cards moved to the right and went translucent**, so they sit clear of the
  sidebar and stop cutting the model in half - the bed and the model read faintly through them. The
  menus that open from the top bar (Import, Plate, Blender, CAD) draw over them, proved on the device.
- **The folded action bar is an arrow and nothing else.** Collapsed, the bar is not drawn at all: the
  viewer runs to the bottom of the screen and a small circular arrow sits over the model to bring the
  actions back. The strip the bar used to leave belonged to the model.
- **All three engines' settings sheets look alike.** OrcaSlicer drew bare section headings where Cura
  and PrusaSlicer drew cards, so one engine's menu bars were black and the others grey. All three now
  share one section card and one layout, at both widths.

### Fixed

- The sidebar picker's own list of chosen rows grew without limit and began pushing the search field and
  the catalogue off the bottom of the screen; it is capped and scrolls inside itself.

### Notes

- **No printed pair has been clicked together yet.** All three joint styles - the cantilever placed at a
  tap, the wall lip's ring and its cuts, and the library hooks - are verified in the app, in the geometry
  they build and in the meshes they produce, not in plastic. Treat the first print as a test: **Engage**,
  **Wall** and **Depth** are the numbers to move, and a drooping undercut prints deeper than drawn.
- **The library hooks are scaled.** They are designed at about 20 mm with deliberately tight fits;
  scaling them down scales those clearances with them, and below 1:1 they stop being the joint the author
  tested. The panel warns under 0.15 mm, and the two round shapes need a round seam of about their own
  size - refused on a solid one, by name and with the volume it measured.
- **A preview costs about 792 bytes of graphics memory per drawn move.** The app asks the device what it
  can afford and says which profile it is in: a phone with RAM to spare holds the whole path, and one
  without draws a band or a window around the scrub. No move is ever skipped at any profile.

## [1.9.2] - 2026-10-12

### Added

- **A library of ready-made snap joints, placed by tapping.** The snap panel's Setup column gains a
  third style beside the cantilever and the wall lip: **Library hook**. Eight shapes ship with the
  app - cantilever01/02, cantileverRidgid, separable, separable02, annular, annularContinuous and
  torsion03 - from kaje's *Snap-Fit Joint Test Prints* (Public Domain), the prints that came out of
  MIT's *Snap-Fit Joints for Plastics* design guide. Pick a shape, tap the model, and the pair lands
  with its own mating plane on the seam's; the half you choose takes the hook and the mate takes the
  catch, grafted **locally** - the housing clipped to the hook's own reach, the mate's material
  inside that box replaced rather than buried. One **Scale** slider moves both pieces together, so
  the library's register survives every size; the pair's own smallest clearance is measured at load
  and reported at the scale in force, with a warning under 0.15 mm where no FDM printer will hold
  it; and the panel says plainly that the spring is the author's, so no strain figure is invented
  for a shape the app did not design.
- **Wall cuts: the ring can be opened at the bends.** A cut is a slit through the lip half's ring at
  one place - the wall's material and the tooth on it - **placed by tapping** ("Cut wall" arms it,
  the tap drops the point onto the nearest place on the seam, a bend taken across its own bisector)
  or **with one swipe**, whose own direction orients the slit. **Cut every bend** puts one cut on
  every corner in one go, sharpest first; a contour with no real bends gets exactly two, half the
  ring apart. A **Cut depth** row says how deep the slit runs, and a slit stops at the part's own
  surface - twenty microns above it - whatever depth is asked, so the roof the emboss stands on is
  never cut into. The readout says what the ring is now: the cuts, the arcs, the runs between them,
  and, when the root below the surface keeps the ring joined, that it is **grooved rather than
  parted** instead of counting arcs the mesh does not have.
- **The joint move is a gizmo, one axis at a time.** Pressing **Move** raises the same axis arrows
  the plate's own move draws on the selected joint; pressing it again puts them away. A drag counts
  only if it began on an arrow, and then **only that axis moves** - X in X, Y in Y, Z in Z - with
  the held arrow lit and the others stepped back, driven by the state rather than the drawing. **Z
  is the joint's depth along the seam's axis**: a value of the joint's record, read by the build
  through the same per-joint parameters as every other value, named in the panel, and clamped to
  the material so a plane cannot be carried out of the part.
- **The ring's own wall and depth are yours to set.** For the wall lip, **Wall** is the thickness
  the ring leaves where the part is solid - thin bends more easily, thick is stiffer, and the strain
  line is computed over whatever it ends up being - and **Depth** is how far the recess floor is cut
  past the step the tooth and its ramp need. Both are per joint and both round-trip in the record.

### Changed

- **Every G-code reader that draws now draws everything it read.** The app reads G-code for display
  in four places and none of them samples any more: the Path tab's Cura parser, the Path tab's
  PrusaSlicer and OrcaSlicer parser, and the Layers tab's parser for all three dialects. A budget
  may only **fold** a move into the run it genuinely continues - the same kind of move, the same
  direction, within a tenth of a millimetre of the run's line - and every source move is covered
  exactly once. A skipped move is a hole in a wall, which is what this rule exists to prevent; it
  now holds for the move a run *closes* at as well as for the ones folded inside it.
- **The previews hold what the device grants, not a constant.** **Auto**, the default, is unlocked:
  capacity is derived from the device's free RAM and heap class, so a phone with memory to spare
  folds nothing and can hold the whole path - the reported 0.08 mm PrusaSlicer print is 2,398,458
  moves and 2,353,134 paths, held whole on an 8 GB phone. **Low memory** keeps the old conservative
  figures (400,000/1,000,000 moves, a 200,000-window band, an 800,000-segment cap) and is chosen
  automatically on a low-RAM device. The readout names the profile in force and what it bought, and
  a tap changes it. A driver that refuses an allocation halves the band or the window until
  something fits, remembering what fitted; only a refused floor ends in a named refusal, and that
  sentence carries both numbers. Whatever the profile, no move is ever skipped: a low-memory phone
  gets a coarser preview, never a lossier print.
- **The whole-path buffers are gone.** The ribbon is built a chunk at a time into small direct
  buffers and uploaded to the GPU as it is built, so the Java heap no longer carries a print's
  geometry. The Path tab used to die on a single 168 MB allocation for a 586-layer print; the
  graphics memory that replaces it is what the profile is sized against.
- **A PrusaSlicer or OrcaSlicer slice publishes from this app.** Object labels are text: a quoted
  name is no longer scanned for parameters, and **M486** accepts Marlin's whole set - so the engine's
  own firmware labelling passes the published-G-code policy, the labels reach the file, and that
  setting no longer has to be changed by hand before slicing.

### Fixed

- The wall lip's **Wall** and **Depth** were wired everywhere except the parameters the build reads,
  so the sliders moved, the readout kept the old joint, and Apply staged geometry nobody asked for.
- The wall lip's step line printed its format string instead of its numbers.
- A cut ran **along** the wall instead of across it at most bends, leaving the ring joined exactly
  where it was meant to be opened.
- A cut landed on the half whose ring is the part's own outer skin; cuts now sever only the ring that
  is a protrusion on the embossed insert, and the other half comes out of the tool untouched.
- A library hook could be placed where it could not fit - a 10.2 mm hook on a 10.0 mm face, hanging
  five millimetres into air. It is now refused by name with the shape's numbers, the place's numbers
  and the scale that would fit.
- The Path tab drew slivers across the part and a fan of chords at one place: folded runs closed at
  moves that did not continue them, so beads spanned travels and chords cut corners.
- The Layers tab drew one path in three spread across the whole print past its cap, so a single
  layer came out sparse - the reported print's densest layer showed 1,700 of its 5,001 paths - and
  the renderer sampled a second time on top of it. Every count the tab shows is now the source count.
- **Build-up collapsed to one layer.** The layer window was remembered between selections as a
  session ceiling, and the window near the start of a print is small because the print ends there -
  that small number then capped every later selection while gigabytes were free. The window is
  re-planned for every selection, only a refused allocation halves it, and a reduced window says so
  with both numbers.
- The workspace descriptor dropped the wall lip's sizes, cuts and directions, so a relaunch silently
  re-tuned a joint.

### Notes

- **No printed pair has been clicked together yet.** All three joint styles - the cantilever placed
  at a tap, the wall lip's ring and its cuts, and the library hooks - are verified in the app, in the
  geometry they build and in the meshes they produce, not in plastic. Treat the first print as a
  test: **Engage**, **Wall** and **Depth** are the numbers to move, and a drooping undercut prints
  deeper than drawn.
- **The library hooks are scaled.** They are designed at about 20 mm with deliberately tight fits
  (0.003 to 0.067 mm between the pieces at 1:1); scaling them down scales those clearances with them,
  and below 1:1 they stop being the joint the author tested. The panel warns under 0.15 mm. The two
  round shapes need a round seam of about their own size - the app refuses them on a solid one, by
  name and with the volume it measured.
- **A preview costs about 792 bytes of graphics memory per drawn move.** The app asks the device what
  it can afford and says which profile it is in: a phone with RAM to spare holds the whole path, and
  one without draws a band or a window around the scrub.

## [1.9.1] - 2026-10-11

### Added

- **The wall lip: a second kind of snap joint, and the whole seam is the hook.** The snap panel's
  Setup column now carries **Style: Cantilever | Wall lip**. The cantilever is the joint as it is
  today - a beam placed at a tap, with its rungs, teeth, facing and socket. The wall lip is the
  seam itself: one **tooth ring** swept around the lip half's own edge and one **ledge ring**
  around its mate's, each one closed ring mitred at every corner, so the hold runs the whole way
  round instead of standing at a single point. Only the wall flexes; the mate is solid where the
  ledge sits. Nothing is placed by hand - choosing the style builds the ring from the seam's own
  contour - and the tap controls (Add joint, Move, Auto spread, Clear) belong to the cantilever
  and are hidden for the lip.
- **Two sizes, and they are the hold.** **Engage** (0.80 mm) is how far the tooth and the ledge
  overlap; **Tooth** (1.20 mm) is how tall the tooth is built, on the existing 45-degree ramp
  parameter (90 degrees makes both lead-in faces square). The step the two halves give the ring is
  sized from those numbers rather than from the cantilever's pad: pad = tooth + ramp's run +
  2 x clearance, which is 2.80 mm on the defaults, and the recess a clearance deeper again. The
  panel reads the step it grew to and why.
- **The wall is read, not assumed.** The lip stands on the ring's wall, so the app measures the
  material's own band at every sample around the ring and how far it runs back from the seam
  before it joins anything, then reports the shortest free length, the worst strain and where
  along the ring it is - "wall 2.0 mm thick, 28.0 mm free", "worst wall strain 0.4% at 20 mm along
  the ring", against PLA's own 2% figure. A ring that crosses a place where the wall cannot bend
  (a corner, a boss) or a mate that is hollow just inboard of the wall is **built as asked and
  warned about**, with the number and the place on the ring; only a seam with no wall at all, a
  folding inset, or a mate hollow the whole way round is refused.
- The joint keeps its style and both sizes in the record, so a lip joint survives a relaunch and
  can be re-tuned after a print. Cantilever records keep their old shape, and a style the build
  does not know reads as the cantilever.

### Fixed

- The wall lip's step line printed its format string instead of its numbers
  ("pad %.2f mm: a %.2f mm tooth and its ramp"). The sentence is built with the joint now, where a
  test reads it, and the panel prints that.
- The wide panel clipped the same line off the bottom: its joint list carries a height cap sized for
  joint rows, and the lip's readout is longer than a row. In the wide panel the readout is drawn under
  the list, where the cap cannot reach it, so the step and any warning are both readable.

### Notes

- **The wall lip has not been printed.** It is verified in the app, in the geometry it builds and
  in the meshes it produces - the ring closed at every corner, the tooth's holding face and the
  ledge's bearing face measured in the applied pair, the engagement read off the phone - but no
  printed pair has been clicked together. Its fit, its click and the wall's behaviour in plastic
  are unproven, and the first print is the test. **Engage** is the number to move if it is too
  tight or too loose, and a drooping undercut prints deeper than drawn, so a printed joint tends
  to come out tighter than the drawing.
- The cantilever snap fit is unchanged and is likewise not yet verified in a printed part; see the
  note under 1.9.0.

## [1.9.0] - 2026-10-11

### Added

- **Placing a joint is a deliberate press again.** The snap panel's **Add joint** arms the next
  tap and the line under the title says so ("Tap the model to place it"); with nothing armed a tap
  on the model selects the joint nearest where the finger landed, so turning or moving a pair no
  longer leaves a hook behind on every touch that crossed a half. Move arms a tap to move the
  selected joint, and the two are mutually exclusive: one tap cannot both place a joint and move
  one. Auto spread and the joints a pair remembers are unchanged.
- **Adopt PC Klipper's configuration, whole.** The Machine tab, on the Phone Klipper route, reads
  the computer's printer.cfg over Moonraker and brings the entire file here - pins, steps,
  kinematics, the probe, the macros and the files it includes, walked to the end of the include
  closure so KAMP's own settings file brings its macros - and shows what would change and what is
  lost with the file before anything is written. Only the parts that describe the computer are
  replaced: the serial port points at the app's pty, the virtual SD card at the app's files, a
  second `[mcu ...]` section is removed, `restart_method` and `baud` are removed (a pty has no
  baud rate and klippy refuses an option it never reads), an include that cannot be fetched is
  commented out with a line saying so, and `[include app.cfg]` is added for the app's own
  sections. Companion files are written under the paths the configuration names them by, the file
  that was replaced is kept as printer.cfg.previous, and the source marker makes the result the
  user's: no app update replaces it afterwards. The host restarts to read it, and the Z probe
  screen then shows the offset the computer had saved.
- **Prepare the engines, once, on purpose.** Every payload used to unpack lazily on first use -
  the CAD tree 533 MB, Blender's Python 140 MB, Orca's profiles 80 MB, the Klipper Python 27 MB -
  so a fresh install met them one surprise at a time with no way to tell working from stuck. The
  new step 3 of the first-run setup, and the same screen from Settings, prepares the engines you
  tick, **one at a time** so the peak is the largest single tree rather than the sum, and shows
  what the selection needs against what is free before anything starts. Every row ends ready or
  failed with the reason. **The action follows the state**: while anything selected is unready it
  is *Prepare selected engines*, and once every selected engine is ready it becomes **Done**,
  which finishes the step - a finished run is accepted, never offered a "skip". It is skippable
  while there is work left, because preparing can be hundreds of megabytes, and each engine's own
  screen says what it is waiting for and where to prepare it.
- **The CAD engine no longer seeds a cube, and the scene keeps what you leave it.** It used to
  put a 20 mm cube in the scene at startup - and clear the scene before doing it - so a scene
  emptied on purpose could never stay empty, and a start after a model was loaded could replace
  the user's work with a default part. The engine now starts on exactly what the user left it
  with, which on a fresh install is nothing: the empty scene says "Engine ready - nothing in the
  scene yet", and **Add a 20 mm cube** is the way to a starting primitive, at any time and as
  often as it is asked for. A default that has to decide when it is welcome is a default that
  will eventually overwrite someone.
- **A joint on a split pair is editable after it has been applied, and a relaunch remembers it.**
  The snap fit used to be one-way: Apply baked the beam and its pocket into the two halves' meshes
  and dropped the joints, so the only way to change one was to split again - which destroys the
  joint. The pair now keeps its own RECORD: the two unjointed halves every joint was built from,
  in the pair's assembled frame, the panel values, and the joints, each anchor stored in that frame
  rather than on the plate the packer rearranges between sessions. Opening the tool on such a pair
  brings the joints back, listed and selectable and editable exactly as ones placed by tapping are,
  and Apply rebuilds them onto the source rather than onto the meshes that already carry them - so
  applying the same joints twice leaves the geometry identical, and deleting every joint and
  applying puts the plain halves back. A record whose source files have gone is dropped with a line
  saying why: the pair stays, and never the plate.
- **The long press on a model removes it, or splits it.** The hold-down card that carries
  Rotate, Move and Scale gains the two whole-model actions: **Split at a plane**, which opens
  the same cut tool Model tools > Actions opens and closes the joint maker, and a red X that
  takes the object off the plate (labelled "Remove model", and confirmed - it deletes the
  file). Committing a split lands in the **Snap fit joint** panel with the pair the split just
  made already chosen, because the split is what defines the assembly axis: hold, split,
  place the joint, join. A split that is refused or fails opens nothing.

### Fixed

- **The camera card no longer reports a failed request as a fact about the host.** The list of
  cameras was fetched once per session, before the connection was made, and every failure - a
  timeout, a refused connection, a Moonraker still coming up, a tailnet not up yet in the first
  seconds after a cold start - came back as an empty list, which is the same value as a host that
  publishes no cameras. The card then read "No camera is configured on this host." for the rest of
  the session while the camera was fine on the computer. The list is now asked for **after** the
  connection is up, in a few bounded tries with a pause; "could not be read" is carried as its own
  state rather than as none; the card says **"The camera list could not be read."** with a **Check
  again** beside it, and asks again by itself while the screen is open; and Moonraker's own
  webcams-changed push re-reads it, so a camera configured or plugged in after the app connected
  appears without a relaunch. A frame that stops arriving is its own state and is unchanged.
- **Every joint on a seam is in the geometry, and a joint that cannot be built says so on its own
  row.** Each joint was built on the pair's own untouched halves and its result then REPLACED the
  running mesh, because a placement carries the mesh it was built from - so two taps at two places
  on the seam listed two joints, showed one hook, and an apply wrote a pair carrying only the last
  joint, silently. Every joint is now built on the halves the joints before it left, so N joints
  put N hooks in the halves that are applied. And a joint the material refuses no longer takes the
  whole preview down with it: it is named on its own row with the reason, the joints that were
  built stay on the plate, and Join refuses - naming it - until it is moved or removed, because a
  pair joined with fewer joints than the list shows is not the pair that was previewed.
- **The comparison between the two configurations says what it compared.** "Nothing was copied:
  the two agree already" was read - correctly - as the app claiming the two files match, when all
  it meant was that the settings one button moves do. The card now says how many lines the two
  files differ on at all, the empty copy names the settings it moves (calibrations, rotation
  distance, pressure advance, limits), and the Z probe screen only says the printer has no probe
  when a configuration has really been read for the connection in use: with none read it says it
  cannot tell yet, which is the state a starting host leaves it in. The copy towards the phone is
  gone with it - copying only the printer's own values into a configuration that did not describe
  the printer was how a phone host ended up unable to run it - while copying towards the computer
  stays, because what klippy saved on the phone exists nowhere else and the printer is on the
  phone's cable.
- **The phone's own Klipper host can start again, which is why it could not before.** Every
  configuration this app prepared carried `restart_method: command` in [mcu], and klippy reads that
  option only when a baud rate was set - which on the app's connection it never is, because a pty
  has no baud rate and the vendored mcu.py treats a port resolving into /dev/pts as a pipe, exactly
  as upstream treats /dev/rpmsg_. An option nothing read is refused outright: "Option
  'restart_method' is not valid in section 'mcu'", a fatal configuration error before a printer is
  touched, so Phone Klipper said only "Host not reachable". The option is removed rather than
  written now - klippy's own default with no baud rate is 'command', the reset this connection needs
  - the shipped configuration no longer carries it, and both the prepare path and the import path
  take it (and a baud rate) out of a file that already has one, so an installed app is healed by the
  update rather than needing anything imported again.
- **A dropped connection to a computer no longer reports the print as interrupted, and one host's
  state can never be shown as another's.** On the PC route the phone's websocket closing is not the
  print dying - the printer is a different machine, and it is usually still printing - but every
  drop ran the local-host path, which posted "The print was interrupted", appended a history record
  and reset the state the next snapshot is read against: one print collected five of them in the
  user's own Moonraker log. Only the host this app owns is treated that way now, and even there the
  print is settled by what the next connection shows - still printing is the same print, a state
  klippy names for itself is reported by the ordinary transition, and a host that came back with no
  print at all is reported once, as interrupted, naming the restart. The other half of the same leak
  is the state: switching hosts cleared only "connected" and "error", so the Phone Klipper tab
  rendered the PC's cancelled print, its host name, its paths, its boards and its camera - and
  greyed out Start on the screen that could have started the phone's own host. A host change now
  starts from a state that describes no printer and names the host being connected to, the state
  carries the identity of the host it describes, every write is checked against the connection it
  came from so a reply from a connection the app has left is dropped, and a session closes the
  socket it opened instead of abandoning it for the next reconnect to replace.
- **Loading a file back into the CAD or Blender engine no longer kills the app.** Both file screens
  ran the load on a background coroutine and reported the result with a Toast from that same
  thread, and a Toast from a thread with no Looper throws before the message is ever shown - so the
  one tap that loads a file crashed on the way to saying whether it had worked. The result is shown
  on the main thread now.
- **The CAD engine now starts on a fresh install.** It borrows the Klipper payload's CPython as
  its interpreter, but only a printer attach ever extracted that payload - so opening CAD on a
  wiped install extracted 533 MB of OCCT, refused to start with "the app's Python is not
  extracted yet", and left the screen on that sentence for good. The extraction is now shared
  with the Klipper service through one stamp, and the CAD path performs it itself when it is
  missing.
- **The CAD status advances.** While a tree is being written the screen shows files, megabytes
  and elapsed seconds, ticking once a second; a failure names the reason; and the viewport stops
  re-asking an engine that has said why it cannot come up rather than leaving "Rendering..." over
  a dead port.

### Notes

- **The printed snap fit has not been verified yet.** Everything up to the G-code is: the boolean
  in the pair's assembled frame, the joints accumulating in the running halves, the record that
  survives a relaunch and comes back editable, the byte-identical re-apply, and the two hooks a
  two-joint pair really carries. What has not happened is a pair printed and clicked together -
  the first attempt was lost to an extrusion problem at the printer, well before the halves were
  finished. The clearances (a 0.20 mm seat, the mating faces left 0.20 mm apart) are defaults and
  are unproven in plastic, so treat a printed pair as something to tune: Tight or Loose on each
  joint, then Thick, then Scale, and apply again.
- **A profile cannot yet state what its hotend melts.** There is no maximum-volumetric-speed
  setting, so a print speed the slicer accepts can ask for more plastic than the machine can
  deliver - 250 mm/s over a 0.4 mm line at a 0.2 mm layer is 20 mm3/s on every long move - and the
  result is extrusion that thins and breaks on long fast lines while the perimeters, which never
  reach full speed, look right.

## [1.8.9] - 2026-10-10
- **First-run setup is now two steps and neither can be skipped.** Printing setup offers Continue
  and nothing else - no Skip, and Back says the setup has to be finished instead of quietly
  leaving. The second step shows the plate as it will actually look, with the interface-scale
  slider applying **live** while you drag, so legibility is judged on the real screen. Only **Done**
  opens the app. Anyone who already finished onboarding is never re-onboarded.

### Security

- **Copy MCP token now asks for the device's own authentication** - fingerprint, or the PIN/pattern
  as fallback, the way a banking app does. The token grants code execution as this app, and on an
  unlocked phone it was two taps away. The token file is not even read until authentication
  succeeds, nothing is copied on cancel, and the three refusals each say something different: no
  screen lock at all tells you to set one, a lock without a fingerprint falls back to the PIN, and
  a missing token file keeps its own message.
- **The CAD engine's token is no longer swept into Android backups.** `files/blender/` was
  excluded from backup; `files/cad/` was not, so a bearer secret could have ridden into a cloud
  backup or a device-to-device transfer.
- **A threat model for the token**, `docs/MCP_TOKEN_SECURITY.md`: what it is, that holding it means
  running arbitrary Python as this app rather than "making models", the blast radius bounded by the
  app's actual permissions, why a plain file is acceptable, how it is read with and without root,
  why the shape check cannot tell a wrong value from a right one, rotation, and the options
  considered and declined with their reasons.

### Fixed

- **Saved presets survive a restore.** `files/persistent-state/` was excluded wholesale, which
  took the preset library with it on a device migration. The exclusion is narrowed to eleven exact
  paths: the workspace descriptor (absolute model paths that do not exist on the receiver) and the
  seven `current-cura-import.*` copies, up to 128 MiB against a 25 MB Auto Backup quota - an
  over-quota backup fails whole, so excluding them is also what lets the presets fit. The preset
  library and its `.previous` rollback copy now travel.

## [1.8.8] - 2026-10-10

### Added

- **Multi-object settings from the hold-down menu.** Long-pressing a model opens the transform
  card; it now offers a **Multi-object settings** button on its own line (the Move/Rotate/Scale row
  is already full), opening the same sheet the engine card opens.
- **A model may be moved below the bed.** The geometry under the plate is discarded and the
  cross-section becomes the new bottom - the slicers do not clip at Z=0, so the app clips the mesh
  itself at the staging step, keeping the viewer's copy intact so you can see what you are cutting
  and drag it back up. The cut is reported in the app's caution amber, not its refusal red:
  *"2.00 mm of this model is below the build plate; that part will not be printed."*
- **Copy MCP token in the CAD menu**, matching the Blender menu's, so the CAD engine can be driven
  from outside the app on a phone without root. Both clip labels name their engine.

### Fixed

- **Dragging one object no longer previews the whole plate moving.** The renderer built one
  scene-times-local matrix for all objects, so a drag (and a rotate, and a scale) animated every
  part while the commit moved only the selected one. The matrix is per object now, so neighbours
  are pixel-identical for the whole gesture.
- **The below-bed warning was reported for the selected object only**, so a plate whose
  below-bed part was not selected said nothing at all - a restored plate could hide a part hanging
  under the bed. It now answers for the whole plate and names the deepest part when that is not the
  one selected.

### Documentation

- Corrected the instructions against the code: OrcaSlicer reads `paint_supports` and has no
  `slic3rpe:custom_supports` string at all; the PrusaSlicer 2.9.6 build script is superseded by the
  3.0.0-alpha11 one the app ships; the README no longer says the plate holds a single model; the
  renamed UI components, the engine counts, the broken skill links and the sequential-printing
  refusal are all right again.
- The scripts are executable in the tree now (they were committed `100644`, including two the
  clean-checkout instructions run), so a fresh clone no longer needs a `chmod` step.

## [1.8.7] - 2026-10-09

### Added

- **Multi-object printing.** The build plate holds several models: an import appends to whatever
  is already there, each part is selected, moved, turned and painted on its own, and one slice
  covers the whole plate. The app arranges the plate itself - none of the three engines arranges
  for the CLI paths this app drives, and both Slic3r forks will happily slice one part through
  another - with **Auto arrange** / **Manual** placement, a gap setting, **Arrange now**, and a
  **Multi-object printing** menu on the engine card.
- **Sequential printing** ("print one object at a time") on all three engines. None of them
  validates the head clearance in that mode - PrusaSlicer's check is commented out, the Orca
  console never calls it, CuraEngine never had one - so the app refuses a plate whose parts would
  collide with the print head or the gantry, before slicing it.
- **Object labels in the G-code**, so a printer can cancel one object on its own: PrusaSlicer and
  OrcaSlicer write per-object M486/EXCLUDE_OBJECT markers, and the G-code policy no longer
  rejects the third object's id (it failed *after* a successful slice).
- **CAD and Blender handoff for several parts.** Every file those engines export lands on the
  plate as its own object, and each engine screen has **All to plate** to export the whole scene
  in one action.
- The saved workspace remembers the entire plate; a descriptor written by an older build still
  restores as a one-model plate.

### Fixed

- **Support paint is drawn on every object.** It was only visible on the highlighted model,
  because the viewer kept one colour buffer sized to the selected object and indexed by that
  object's triangle numbers. One scene-wide buffer now carries every object's paint at its own
  triangle offset, with the selection tint still on the highlighted part.
- **The brush follows the finger that paints.** A stroke could only ever paint the highlighted
  object: the viewer dropped any stroke that began on another one, dropped every later sample
  that landed on one, and expanded the brush against the selected object's mesh regardless. A
  stroke now paints the part it is on, and that part becomes the selected one.
- **Two dozen defects found by two rounds of audit** of the multi-object work, each verified
  before it was touched. The three that mattered most:
  - the third and later objects lost their paint on **OrcaSlicer** (it reads `paint_supports`, and
    its shipped console has no `slic3rpe:custom_supports` string at all) and their paint was
    dropped on **PrusaSlicer** unless the file carried the stamp that routes it to the legacy
    loader - so paint never reached either engine on any path, in either the single- or
    multi-object case;
  - a handoff of **three or more objects** lost the middle ones: a claimed export that arrived
    while the app was busy went into a single slot and the next overwrote it;
  - **"All to plate" could crash** the app when an engine was booting, stopped, or answering
    nothing - nothing caught the client's throw.
  Also fixed: arrangement ignored an elliptical bed (parts landed off it and CuraEngine refused
  the slice); arranging and removing had no busy gate, so a plate could change under a running
  slice and the finished G-code was then published for a plate that no longer existed; a
  successful OrcaSlicer profile import left the app "Working..." until a restart; two copies of
  one file produced two identical object names, which a Klipper host cancels as one object;
  "All settings" overrides reached only the last object (or a modifier volume) on the
  multi-object CuraEngine path; an unreadable saved plate vanished in silence; a single object
  with "one at a time" on and an imported Cura profile refused every slice; the Blender handoff
  re-imported the previous session's export as a duplicate part after a restart; export names
  that sanitise alike overwrote each other; empty meshes and mirrored objects exported wrongly;
  and the model tools bar could not scroll, so the close button could be measured off a narrow
  screen.

## [1.8.0] - 2026-10-07

### Added

- **Parametric CAD, with build123d on OpenCASCADE.** The official OCP bindings
  (OCCT 7.9.3, 204 MB) run under the app's own CPython - the interpreter the Klipper host
  analytic geometry - real cylinders, real fillets, a STEP file that carries design intent -
  rather than from a mesh. It speaks the same local socket protocol as the Blender engine, and
  has its own **CAD** menu in the top bar, because which engine the assistant is driving is not
  something the user should have to hold in their head: opening a menu starts that engine and
  tells the agent which one it has.
- **The CAD engine renders, so the assistant can see its own work.** There is no window and no
  display; it renders offscreen through EGL into a pbuffer, reads the pixels back, and the app
  shows the newest image as the view. Seven views (`iso`, `top`, `front`, `right`, `left`,
  `back`, `bottom`), shaded or wireframe, up to 2048px.
- **2D output**: SVG and DXF for flat parts, and hidden-line technical drawings through OCCT's
  HLR - visible and hidden edges as separate sets, so a hole reads as dashed lines instead of
  vanishing. Android cannot preview SVG, so a drawing is always sent with a render beside it.
- **STEP, STL and BREP export**, and import, from the same engine.
- **A CAD part reaches the plate by itself**, by the same handoff the Blender engine uses.
- **STEP and IGES import.** A sixth engine, `libocct_exec.so` (OpenCASCADE 7.6.0, 26 MB
  stripped), converts STEP and IGES files to a mesh on import - the same move the 3MF branch
  already makes, so one model format reaches every path downstream. Both carry analytic
  geometry that none of the three slicers can read.
- **CAD operations available to the engine**: `info`, `boolean` (cut/fuse/common), `fillet`,
  `chamfer`, `shell`, `draft`, `box`, and mesh-to-BREP `export` to STEP or IGES.
- **Constraint-driven sketches.** `sketch <spec> <height> <out>` states relationships rather
  than coordinates - `horizontal`, `vertical`, `equal`, `parallel`, `perpendicular`,
  `distance`, `fix` - and FreeCAD's own PlaneGCS solver finds the geometry, which is then
  extruded. The spec's point order is the profile outline.
- `scripts/build-occt-engine-android.sh` builds and stages the engine, linking OCCT and
  PlaneGCS together. It does not rebuild OCCT from scratch: the OrcaSlicer dependency tree
  already cross-compiles 38 toolkits for arm64, so the script builds only the three its recipe
  omits and links the driver against both sets.

### Notes

- Orca's recipe passes `-DBUILD_MODULE_ModelingAlgorithms=OFF`, and CMake still builds
  whatever DataExchange depends on. `TKFillet`, `TKOffset` and `TKFeat` are not in that
  closure, which is why fillets, shells and drafts were missing until the module was turned
  back on.

## [1.7.1] - 2026-10-05

### Fixed

- **Starting a print from the Files tab crashed the app.** `sendScript` wrote to the printer's socket
  on whichever dispatcher its caller was on, and the Files tab calls it from a UI coroutine - so
  `resetLiveOverrides()`, the script sent immediately before `SDCARD_PRINT_FILE`, raised
  `NetworkOnMainThreadException` and took the process down. The stack was
  `KlipperFilesTab:174 -> KlipperViewModel:457 -> printGcodeFile:1055 -> sendScript:1049 ->
  MoonrakerTransport.sendFrame:255`. It only fired when the write actually reached the socket rather
  than the buffer, which is why the same action worked most of the time. The write runs on
  `Dispatchers.IO` now, in the one function both callers share; the repository's other two G-code
  senders were already wrapped, and the identical call two lines below this one already was too.

- **The All-settings catalog was missing 79 Cura settings, acceleration and jerk among them.** Cura nests a
  setting's own sub-settings under it - `acceleration_print` carries `acceleration_wall`,
  `acceleration_layer_0` and the rest - and the catalog treated "has children" as "is only a container":
  it recursed into such a node and then skipped the node itself, so its key never appeared. The catalog
  offered 529 of the 729 settings the bundled definitions declare, which is why the app looked like it had
  no acceleration setting at all. Ranges and defaults are read now too, so Cura rows clamp like Orca's
  already did. `AllSettingsCatalogTest` walks the definitions independently and fails if the catalog
  cannot offer a key they declare - and fails on the old code, naming the lost keys, which is how the
  test was checked rather than assumed.
- **Verified end to end on the device.** `acceleration_print_layer_0`, one of the settings the catalog
  could not previously offer, was added from the All-settings sheet with the value 1234 and the slice
  ran. The engine's argv, from `files/logs/curaengine-last.log`, ends:

  ```
  [866] …/output.gcode
  [867] -s
  [868] acceleration_print_layer_0=1234
  ```

  A setting the app had no way to reach now reaches CuraEngine as the final argument, after the settings
  source, so it wins.
- **The All-settings list says which settings Cura will actually read.** Cura declares an `enabled`
  expression per setting - 516 of the 729 - and the app never read them, so a setting that the engine
  would ignore was listed exactly like one in force. The list is now split into **Active** and **Not
  active**, and an inactive row names what it is waiting for: `retraction_hop_only_when_collides` shows
  "needs retraction_hop_enabled, travel_avoid_other_parts" under the app's own defaults, which is the
  answer to why adding it changes nothing. Orca and Prusa carry no dependency information at all, so
  their lists stay flat rather than being sorted on a guess. `CuraEnabled` reads the expressions -
  a small grammar of setting names, and/or/not, comparisons, parentheses and Cura's four helper calls -
  and reports an expression it cannot read as active rather than inactive. Seven of the 514 use Python
  list membership or a bare `False`; the test pins that number so an eighth cannot appear unnoticed.

  Checked on the device both ways. With the app's defaults, searching for the setting shows
  `Not active (1)` and the line "needs retraction_hop_enabled, travel_avoid_other_parts". Adding those
  two - through the same sheet, the booleans now being switches - moves it to `Active (1)` with the
  reason gone.
- **Booleans in the All-settings sheet are switches, not text fields.** CuraEngine reads only `on`, `yes`,
  `true` and `True` as true; every other spelling - `TRUE`, `ON`, a typo - falls through to false with
  nothing in the engine log and no complaint from the app. A text box invited exactly that for the 115 Cura,
  109 Orca and 51 Prusa settings the catalogues declare boolean. They get a switch now, which opens on the
  catalogue's own default and writes the same `true`/`false` the curated switches write. The catalogues
  were already carrying the type; only the sheet was ignoring it.

- **Klipper readings that came and went took the layout with them.** A heater's target and power were
  drawn only while non-zero, and "Filament velocity now" only while filament was moving - so a printer
  holding its temperature, or one making a travel, added and removed lines and moved everything below
  them. On a steady PID cycle that happened about once a second, which reads as a fault rather than as
  the printer working. All three rows are now always present, showing zero when zero is the truth and a
  dash when the host has not reported them, which is what the rest of the screen already did. A sensor
  still shows only its temperature. `HeaterReadoutRowTest` fails if any of those rows goes conditional
  again.

- **The Printer card described the wrong host, and claimed no printer was attached when one was.** On the
  PC Klipper route it read "The Klipper host running in this app" while the header above it said "Connected
  to 100.64.13.8", and below that it added "No printer is attached, so there is nothing for the host to
  drive yet" - a claim about *this phone's* USB bus, which has nothing to do with a printer attached to
  another computer. Found by the user from one screenshot of the contradiction; the missing-printer line
  was mine, added when the card was taught to explain the Klipper config error that an absent printer
  causes, and it should have been confined to the case where this device is the host. A third instance of
  the same assumption - "of the same version as the host in this app" in the Machine tab - is route-neutral
  now too.

## [1.7.0] - 2026-10-04

### Added

- **A brush and an eraser for Smart Infill's boundary conditions.** Arming a condition with "Pick" and then
  dragging paints it: a sample lands where the finger goes down and another at every move, and each covers
  every triangle inside the brush radius - the same rule filaSim paints with, and the one the support-paint
  brush already used, so the three agree. Erase takes surface back the same way, which is the real answer to
  a brush that reached past where it was aimed: the condition's count is also the only readout, so rubbing
  it out beats arguing with the geometry. The stroke is handed to the engine once, when the finger lifts,
  rather than on every sample - each sync re-sends the condition's whole triangle list.
  A tap is gone: a press with no movement is one dab, which is what a tap always was.
- **The camera moves when the gesture starts beside the model.** The stroke's first sample decides: on the
  part it paints, off it the camera orbits and pans, and the movement made while that answer is in flight is
  replayed as an orbit. This is filaSim's own hover gate, and it is what makes a brush usable without a mode
  to switch out of every time the view needs turning.
- **An app log you can switch on, and failures in the model viewer's corner.** More > Diagnostics keeps
  a log of what the app did and what it reported; the window lists it with failures red and bold and
  warnings amber, and the log is off by default so nothing is recorded until it is asked for.
  The corner is not the log. A failure or a safety warning lands in red in the lower-left of the viewer
  with its message and time whether or not the log is on, because hiding it is what made the app look like
  it ignored input. Failures also go to logcat at error level, banner-marked, so a capture shows them.
- **The Model tools panel now says how to reach the gizmo.** Pressing and holding the model opens Move,
  Rotate and Scale over it, and nothing said so - the panel's three groups read as the only way in, so the
  gesture stayed invisible. A tip under the bar names it.
- **Interface scale: draw the whole app bigger or smaller.** More > Display > Interface scale opens a
  slider from 75% to 150%. Text, controls, spacing and icons grow together, the way Android's own Display
  size does, because the root multiplies the Compose density and the font scale together rather than only
  one of them. The setting is stored on its own, away from engine settings, so a configuration snapshot
  restored on another phone does not carry someone else's text size.
  The scale is applied when the drag ends, not on every frame. Resizing the interface mid-drag re-lays-out
  the whole tree, and that recomposition cancelled the slider's own gesture after the first touch: the
  value only ever jumped to wherever the finger landed and never followed it. The sheet also re-provides
  the density the device reports, so the track cannot grow as the value rises and move the value under the
  finger that raised it.
  Only the DENSITY is multiplied, never the font scale as well. Compose resolves text as
  `sp * fontScale * density`, so scaling both applied the percentage twice to every label and once to the
  controls: a "138%" setting drew the More title 1.62x larger instead of 1.38x, and text outran its rows.
  Measured against the same title, 75 / 150% now read 104 / 209 px - a ratio of 2.010 for a 2x range, where
  the doubled version gave 3.241.

### Fixed

- **Phone Klipper could be started but never stopped from the UI.** The start button was the whole block,
  gated on NOT being connected - and "connected" here means this app's own klippy is answering, not that a
  printer is attached. So the moment the host came up the control vanished, and the only way to stop it was
  to force-stop the app. It is Start when the host is down and Stop when it is up, and stopping really stops
  it: klippy exits and the socket goes with it.
- **With no printer attached, Phone Klipper explained its halt with a configuration error.** klippy reports
  what it always reports with no board to talk to - "Option 'restart_method' is not valid in section 'mcu'",
  "Printer is halted" - which names a config problem where the cause is an absent machine, and sends the
  user looking for a mistake they did not make. The card now says there is no printer attached, and that
  what follows is what klippy says with nothing to drive.
- **A failure raised off the Plate was still reported to a surface nobody could see.** The corner banner
  lives in the model viewer, so a configuration snapshot import that failed from the More tab wrote its
  message to a status line and a corner that were both on another tab. Anywhere but the Plate the app now
  says it out loud. Found while re-testing "importing an unusable snapshot is a silent no-op", which the
  log line alone only half fixed.
- **The layer readout credited Cura with every slice.** It read "Cura 12" after a PrusaSlicer or an
  OrcaSlicer slice, and the number comes from the file's own `;LAYER:` marker - so it was wrong about the
  engine and was not Cura's number even when Cura wrote the file. It names the engine that actually sliced.
- **The reset had three different names.** PrusaSlicer's "Reset Prusa settings" and OrcaSlicer's "Reset to
  engine defaults" were the same action under two names; both are "Reset to engine defaults" now. Cura's
  pair stays "Reset all app overrides", which is a different thing - it restores the imported profile
  rather than the engine's defaults.
- **OctoPrint's header claimed a connection the Status tab denied.** A successful "Test server" set the
  status line to "Connected to OctoPrint 1.9.3" without saving anything, so the Status tab - gated on the
  saved configuration - said "Configure OctoPrint on the Setup page" directly below it. A test now reports
  what it proved: the server answered, and the setup still needs saving.
- **The OctoPrint setup form was wiped by unrelated updates.** The effect that loads the saved values was
  keyed on the whole configuration, so discovery writing any other part of it - the webcam it found, the
  connection it reported - rewrote the URL and key fields from the saved values while they were being
  typed. It is keyed on the fields the form actually loads.
- **The Modelling screen named an agent that did not exist.** With no harness connected it captioned the
  view "The agent has the camera" next to its own "Connect to the harness first". With nothing on the other
  end it says so.
- **Three of the four "reset everything" controls reset without asking.** Cura's two sheets and the
  PrusaSlicer one threw away every override, or every Prusa setting, on a single tap with no warning and no
  undo; only the OrcaSlicer one asked. All four now go through one `ResetConfirmDialog`, so they ask the
  same way and none is the odd one out. Found because the user checked the engine they were on, saw no
  confirmation, and then asked whether the others had one either - which is the question that turned one
  fix into four.
- **A Smart Infill tap selected too much on a curved part, and did it in the wrong places.** The pick walk
  was bounded by the straight-line distance from the tap, while the panel promises the surface *"within this
  radius of your finger"*. The two agree on a flat face and disagree at every fold: the walk rounds a bend
  and then keeps going along the neighbouring run, which is a fraction of a millimetre away through the air
  for its whole length. Bounded by the distance travelled instead, the budget is spent getting round the
  bend. Measured on a real 3DBenchy at a 2.37 mm radius over ten seed triangles, one differed - by 3x -
  and nine were unchanged, so this is a narrow defect rather than a general one; the flat regions were
  already exact. A hairpin test fails on the old metric and passes on the new one.
- **More > Smart Infill left you on the More tab.** The panel it opens lives over the Plate, so setting the
  flag alone looked like a dead control. The row now takes you to the surface the panel is on.
- **Every outcome the app reported was written where nobody could read it.** All status messages - success
  and failure alike - are the title of one collapsible Plate card, and a collapsed `CollapsibleCard`
  renders a chevron and nothing else, the title surviving only as TalkBack's label. Folded is the default,
  so a slice that failed looked exactly like a slice that was never asked for, and an import that threw
  appeared to be ignored: everything the app said, it said to itself.
- **"The host stopped with:" could be followed by nothing.** The label was printed whenever a log tail
  existed, and a blank tail rendered as a reason that had been lost rather than one that was never written.
- **The settings catalogue showed the engine's printf escapes.** OrcaSlicer writes a literal percent
  sign as `%%` in its own option descriptions, and its `--dump-settings` output carries that through, so
  the catalogue read "adjusted to this %% of acceleration" and "For 100%% overhang". The escape is
  collapsed where the catalogue is read.
- **A restored modelling screen could not reach the engine.** `modellingOpen` is `rememberSaveable`, so
  the screen comes back after the process is killed while `openModelling` - the call that started the
  engine - is not re-run. With the engine no longer booted at launch, that screen showed "failed to
  connect to /127.0.0.1 (port 9876) ... ECONNREFUSED" until it was left and re-entered. It is restarted
  in the same recovery effect that already reconnects the harness.
- **The Blender engine booted at every app launch, and pinned the CPU with it.** `EnderSlicerApplication`
  started the engine and its keeper service unconditionally in `onCreate`: a 1.3 GB library load, ~480 MB
  of asset extraction and a ten-minute partial wake lock for every user who opened the app, including
  everyone who never touches Blender, and the lock outlived the screen going off. Nothing starts it at
  launch now - handing a model over, opening the modelling view and a preview command each ask for it.
- **The Blender engine could never load where the APK's native libraries are extracted.** `libblender_exec.so` needs `hdTiny.so`,
  `sdrGlslfx.so` and `usdShaders.so` - the names those three libraries also carry as their SONAME. The
  build ships copies under exactly those names, and Android extracts only `lib*.so` from an APK's
  `lib/<abi>/`, so they reached the APK and never reached the app's native library directory. dlopen
  answered `library "hdTiny.so" not found` and the app reported it as "not packaged for this ABI", so the
  engine was dead on the one ABI it is built for. The bridge now loads each dependency through its
  lib-prefixed file name first, which registers its SONAME, and the linker resolves the engine's NEEDED
  entries from the libraries already loaded.
- **Model tools scaled relatively, so 145% became the new 100%.** The Scale field multiplied the model
  by the percentage and then reset the field to 100, which made every scale read as a fresh baseline:
  applying 145% twice produced 210%, and the header could never say what size the model actually was.
  The field now names a size - 145% sets the model to 145% of the model as imported, and saying it twice
  leaves it there. The header reports the model's real scale from its transform, so it stays truthful
  while the field holds an unapplied edit. The pinch gizmo on the plate keeps its step behaviour, which
  is what a pinch means.

## [1.6.6] - 2026-10-03

The nozzle-path preview draws the whole file again, and bead geometry follows Cura's own layer
view rather than our own conventions.

### Fixed

- **The preview dropped a third of a long slice.** Past its move budget the parser skipped moves,
  and a skipped move is a hole in the middle of a wall: a 79,000-move slice rendered as beads with
  gaps between them, and up close as short plates with air where the plastic should be. The budget
  is a memory guard, not a sampling policy - it now folds a move into the run it continues, and
  only when the kind of move and the direction agree, so a corner is never cut and a travel is
  never drawn as a straight line across the part.
- **The bead height came from the wrong move.** It was derived from Z rises, and the first rise it
  met was the priming line at Z0.3, so a 0.2 mm layer was drawn 33% narrow - and the odd/even
  layer tint, which counts layers by dividing Z by that height, banded irregularly. The height now
  comes from the `;Layer height:` header the slicer writes, with the rise-based path kept for
  adaptive layers.
- **The bead was drawn above the path instead of below it.** Cura's layer view puts the bead's top
  face on the layer line and hangs the body one layer down, which is where the printer puts it;
  ours floated a layer high and stacked into the layer above.
- **Travels were drawn as beads.** A travel lays down nothing, so it is a line: zero thickness,
  as Cura's reader sets it.
- **PrusaSlicer and OrcaSlicer slices could not be exported to a Klipper host.**
  `SET_VELOCITY_LIMIT` and the `EXCLUDE_OBJECT_*` definitions were refused as malformed commands.
  The object definitions are also what KAMP reads to mesh only the ground the print stands on, so
  refusing them refused adaptive meshing on those two routes as well.

### Added

- **KAMP knows where the objects are on a Cura slice.** CuraEngine declares no objects at all,
  so the macro answered "No objects detected!" and measured the whole bed however well the rest
  of it worked. The app writes the definition itself, from the print's own bounds in the file,
  immediately in front of the mesh call. It does that *after* the sanitizer has corrected those
  bounds from the real moves, and refuses to write one at all while they still hold the unset
  sentinel - which is what declared a 20 mm cube as an object two million millimetres across.
- **The adaptive mesh switch writes into your own start script.** Machine settings -> Adaptive
  meshing (Klipper) puts `BED_MESH_CALIBRATE` into the custom Klipper start G-code - after homing,
  in front of the first extruding move - and takes it out again when switched off. Nothing is
  rewritten behind the slicer's back, and the call is the user's own text, which is the part the
  export check trusts.
- `AGENTS.md`, holding the rules a change has already broken once: every move comes through, and
  bead geometry follows Cura.

### Changed

- The shipped Klipper host config raises `max_accel` to 10000, `max_z_velocity` to 10 and
  `max_z_accel` to 200.

## [1.6.5] - 2026-10-02

Adaptive meshing inside the printer's own menus, and a bed mesh you can look at from any side.

KAMP ships with the app's Klipper host: a slice can measure only the ground its objects stand
on, purging can follow the print instead of a fixed corner, and a new screen in the printer's
menus turns that on, tunes it and says what the connected host is running. The mesh screen
grew a surface you can turn right over - black underneath, so the underside cannot be mistaken
for the bed - and a probe count that is yours to set rather than fixed at five by five.

### Added

- **KAMP ships with the app's Klipper host.** The adaptive meshing and purging macros are in
  the payload, included from the configuration, and installed beside it for a configuration
  that already exists - files only when missing, so an edited `KAMP_Settings.cfg` survives an
  app update. KAMP replaces `BED_MESH_CALIBRATE`, so a host that has it meshes the ground the
  objects stand on, and falls back to the ordinary mesh when a file declares no objects.
- **Adaptive meshing for the Klipper route, off by default.** A switch on the new KAMP screen
  has the app check each sliced file: if the start script already measures a mesh, or the
  printer was never homed before the first extruding move, the file is untouched; otherwise
  `BED_MESH_CALIBRATE` is inserted, marked, immediately before that move, so every object
  definition the slicer wrote is already known to the printer. A `max_extrude_cross_section: 5`
  in the shipped `[extruder]` is what lets KAMP's purge run.
- **A KAMP screen in the printer's menus.** It carries the adaptive meshing switch, reports
  whether the connected host has KAMP, shows the values it runs with - mesh margin, fuzz,
  purge amount, margin and height, flow rate, verbose - and sets them with
  `SET_GCODE_VARIABLE`.
- **The bed mesh drawn as a surface.** The Mesh screen turns the probed grid into a surface
  you rotate with a drag: turn it right over and the underside is drawn black, and the caption
  says so, because a mesh seen from behind has its heights mirrored. The projection is
  orthographic, painted far to near, coloured by height through the viewer's own ramp, and held
  in place by a fit that does not change with the angle - a fit that did made every turn look
  like a zoom. Sixteen tests cover the camera conventions, the wrapping angles and the fit.
- **Mesh probe points are adjustable.** The Mesh screen reads `[bed_mesh] probe_count` from the
  configuration and writes a new one (3 to 13 per axis) to whichever host the app is driving,
  through the same configuration write as the pressure-advance and rotation-distance saves.
  KAMP measures its adaptive mesh with that count, which is the knob its own README points at.

### Fixed

- **A Klipper slice could not be exported once the adaptive mesh switch was on.** The
  mesh call was written into the engine's output, and that output is checked against a
  short list of textual commands that did not include `BED_MESH_CALIBRATE` - Klipper's
  own. The engine reported success and the app withheld every file. The command is now
  allowed for a Klipper host, and the call is added to the *start script* - the user's
  own text - in front of the first extruding move, rather than to the sliced file
  afterwards, which is also the only version of it the safety check trusts.
- **The bed surface followed the camera, not the finger.** Dragging vertically moved the
  point of view instead of the bed: pulling up climbed above the bed and looked down on it,
  which reads backwards to a finger that is holding the thing. It now uses the viewers' own
  turntable - the signs, the 0.35 degrees per pixel and the opening yaw of -28 that the
  plate, layer and nozzle-path views have always used - so the bed moves with the drag and a
  gesture learned in one view is right in all of them. Two tests hold the direction: pulling
  up must lower the tilt towards the underside, pulling down must raise it.

- The KAMP screen asked the printer state for `_KAMP_Settings` - the name
  `SET_GCODE_VARIABLE` takes - while the object klippy publishes is
  `gcode_macro _KAMP_Settings`. The lookup answered nothing on every printer, healthy or not,
  and the screen told the user their machine had no KAMP while it was running it. A test now
  holds the name the screen reads to the list the app subscribes to.
- The surface view's first camera had the up axis and the depth axis swapped: the bed was drawn
  upside down and painted back to front at once, which read as the underside of the printer.
- The same view classified the underside per quad instead of by the camera, so the steep faces
  of a real - and heavily exaggerated - bed were blanked black.

## [1.6.4] - 2026-09-30

Two rounds of looking for bugs on purpose, everything they found, and a card that copies the
printer's own settings from one host to the other.

Most of this release exists because of the first sentence. Five audits of the whole app found
twenty-three defects; five further rounds then audited the fixes themselves and found twenty
more. Three of those were regressions introduced by the first round's repairs - a restart that
stopped the host and started nothing at all, a sealed API key that was sent as its sealed blob,
a layer tracker that survived a Z reset. Every finding, its fix and its commit is recorded in
[docs/bug-audits/2026-09-30-five-round-audit.md](docs/bug-audits/2026-09-30-five-round-audit.md), including the ones no test can reach and why. Reading
that list is the fastest way to know what this app does and does not do.

### Added

- **The printer's own settings can be copied between the two hosts.** klippy's saved
  calibrations - the BLTouch offset, the PID terms, the bed meshes - belong to the printer, not
  to whichever computer or phone happens to be driving it, and the two files drift apart as you
  calibrate. The Machine screen now shows what differs, both files' values side by side, and
  copies one way or the other. What describes the *host* rather than the printer - the serial
  port, the gcodes directory, the `[mcu]` sections, the includes - is never touched, in either
  direction.
- **A written procedure for calibrating the input shaper**, in
  [docs/INPUT_SHAPING.md](docs/INPUT_SHAPING.md): where to put the phone and why, what the mass
  correction is worth, why two runs beat one, and how to try it by eye with no phone at all.
- **Each configuration says when it was last written.** With two files in play, "which of these
  is the right value" is usually "which one did I set yesterday", and a setting carries no date.
  The sync card now shows both files' times in words and marks the newer of the two. The date is
  the file's, not the setting's: a `SAVE_CONFIG` rewrites the whole file, and the card says so.

### Changed

- **The two hosts are called Phone Klipper and PC Klipper** wherever they are read about. "This
  device" and "the other host" were accurate and easy to mix up, which is the one thing a name
  must not be.
- **A configuration written to the PC asks it to restart, and refuses while it is printing.**
  Restarting ends a print there, and the app may not even be driving that host, so it asks the
  host itself over Moonraker's own HTTP query rather than assuming.
- **Whole files are no longer held in memory.** The upload, the conical transform and the
  conformal transform read and write a line or a chunk at a time; a long print used to be held
  three times over as boxed strings, which a large model cannot afford.
- **The Moonraker API key is sealed with the Android Keystore** rather than stored as plaintext.
  A sealed key this device can no longer open is treated as no key rather than sent as a blob,
  and the app asks for it again.

### Fixed

- **A restart could stop the host and start nothing.** The claim that keeps two starts from
  racing was held for the whole life of a run, so the restart that follows a configuration write
  found it set and returned. A restart left the printer with no host at all - worse than the
  silence it replaced.
- **A launch could outrun the service being destroyed**, opening a pty and starting a host that
  nothing could stop, with the phone pinned awake. The flags are checked again immediately before
  the process starts, and the wake lock goes back when a start does not happen.
- **A relaunch after klippy exits could be swallowed**, and the host simply did not come back.
- **Configuration changes went to the wrong host, or nowhere.** Reads, backups and writes follow
  the host doing the printing, including the Machine screen's operations; a host that cannot be
  read is refused rather than guessed at, and the previous configuration is kept beside the new
  one on the host itself.
- **An imported profile could set a setting the app manages itself.** A value containing a line
  break was written into the ini as a line of its own, and a later duplicate wins. Imported
  values now refuse control characters, a Boolean is no longer a number, an unknown boolean token
  no longer means *off*, a bed shape with one corner no longer produces a zero-sized machine, and
  a file that is not a PrusaSlicer configuration is refused instead of installed.
- **A profile whose setting catalogue could not be read dropped every override in silence** and
  reported success. It is refused with a reason, and the generated ini is written whole or not at
  all rather than truncated under a crash.
- **Three transformers and one upload leaked or lied**: the conical stage file and its output
  file are both cleaned up on every path, a failed HTTP request disconnects its socket, and two
  tests that could not fail were replaced with ones that can - including a 512 MB sparse-file
  upload, larger than the heap a test is given.
- **A stopped or cancelled print left the layer height and the Z frame disagreeing**, because the
  tracker the height is measured from was the one piece of parser state a `G28` or `G92 Z` did not
  reset.
- **The Shaping screen said not to put the phone on the bed**, while the note under its own
  moving-mass field explained that a phone on the bed very nearly restores a stock bed's
  weight - and the measurements are taken from the bed. It now says gantry for X, bed for Y,
  and why the bed is the one whose weight matters.

### Notes

The audits are worth reading for what they say about the fixes rather than the bugs: five of the
repairs and four of the tests written for them were incomplete in the same way - correct where
they looked, wrong or empty one step to the side. The conical stage file but not the output file.
One writer's temporary files but not the other's. The write path's "no config yet" but not the
sync's. None of that was visible in a green test suite.

What has no test is stated rather than hidden: the service that owns the klippy process, the
Compose guards, the GL surfaces, the accelerometer and the Keystore each need a process, a
screen, a GL thread, a sensor or an Android Keystore, and every such case is named in its commit.
## [1.6.1] - 2026-09-28

A day of printing on the computer-hosted side, and everything it turned up.

### Fixed

- **A print started a minute after connecting was never sent.** A printer host says nothing while
  nothing is happening; the read timeout that exists so the reader wakes up was treated as the end
  of the connection, so the read loop ended on the first quiet thirty seconds. The host still had
  the socket open - its log shows an accepted upload and no print command ever arriving, and no
  close, because the app never closed it either. An idle read now sends a keepalive and carries on,
  frames are written under a lock (two threads were already writing them), and a print command that
  finds the connection gone reconnects and retries once.
- **Uploads were reported as refused when the host had accepted them.** Moonraker's HTTP file API
  answers with the item it created; the app looked for it inside a "result" object, which is the
  shape of its JSON-RPC calls. A 201 Created, a file on disk and a line in the host's log became
  "the host refused the upload" on screen, and the print that should have followed was never sent.
  Both shapes are read now, and the test that stood in for the host answers with the real one.
- **Configuration changes were written into the app's own copy of printer.cfg**, whichever host was
  printing. A rotation distance measured while driving a computer was saved, confirmed on screen,
  and left on the phone; a pressure advance lasted until the next restart, which any configuration
  write brings. Reads, backups and writes now go to the host doing the printing, through one seam,
  and a write asks that host to restart so that the change becomes the running one.
- **The G-code flavour had no control at all.** It was whatever a profile import left behind and
  otherwise Marlin, so a printer driven by Klipper was sent Marlin's start script - whose G29 L0
  and G29 A are UBL commands. On a Klipper host G29 is the printer's own macro, which homes and
  meshes, so a print homed twice and probed the bed twice before its first layer. The flavour is now
  chosen next to the two script pairs it decides between, and the print screen warns when a file's
  start block contains commands that will misbehave on a Klipper host - reading the start block
  rather than the file's FLAVOR line, which names the G-code dialect and says Marlin even for a file
  sliced for Klipper.
- **Pressure advance could not be saved.** SET_PRESSURE_ADVANCE is live only: klippy's own command
  never marks the configuration as needing a save, so SAVE_CONFIG has nothing to write for it and
  the next restart quietly reverts it. The Extrude screen has two buttons now - Set for tuning
  during a print, Save writing printer.cfg and restarting the host - because they are two different
  things, and only one of them can happen mid-print.
- **No bed mesh was loaded when the host started.** Klipper removed the behaviour that loaded the
  profile named default, so a print whose start script neither loads nor measures a mesh - which a
  custom start script need not - runs its first layer on the probe offset alone. On a bed whose
  measured mesh spans about a quarter of a millimetre, that is a quarter of a millimetre of first
  layer. The shipped configuration now carries the documented [delayed_gcode bed_mesh_init].

### Changed

- The shipped configuration's acceleration limit is **3000 mm/s2**, the value in Klipper's own
  configuration for this printer. 5000 is the Ender 3 V2 *Neo*'s figure, and had been carried
  along since the configuration was first written.

### Notes

This remains a pre-release. The Klipper host this app runs itself is the tested path; driving a
host on a computer is newer, and the six fixes above are what a day of printing on one turned up.
Nothing in this release changes what a Marlin printer is sent, and that is held to by tests rather
than by care.

## [1.6.0] - 2026-09-28

The printer can now be a Klipper host on another computer, and the app is the interface to it.

### Added

- **Drive a Klipper host on a computer.** The Print tab has a third route beside "This device"
  and "OctoPrint": give it a host and a port and the same Klipper screens drive that machine -
  jogging, temperatures, macros, mesh, shaping, the console, the emergency stop - over
  Moonraker. Verified against a real host: connect, subscribe, home, and print.
- **Files go to whichever host is printing.** A file is uploaded to the host with Moonraker's
  upload endpoint, listed from that host, deleted from it, and its thumbnail and slicer details
  come from the host's own metadata. The command that prints it is klippy's, either way.
- **The print camera**, when the host publishes one. It appears on the dashboard with a frame a
  second while the screen is open, and the requests stop when it is not.
- **Klipper's starter macros, on request.** The Macros screen offers the ones a Klipper printer
  is usually given - M600, M486, LOAD_FILAMENT, UNLOAD_FILAMENT - and adds only what is missing
  from printer.cfg, leaving everything already in the file exactly as it is.
- **Each G-code route has its own custom start and end script.** A script written for the
  Klipper host is no longer sent to a Marlin printer when the flavour changes, nor the other
  way round; all four are on the machine settings screen, each named for its machine.
- **A guided extruder calibration**: mark the filament, push a known length, measure what moved,
  and the corrected rotation distance is computed, applied live, and written to printer.cfg.
  This is the one figure in the chain that no measurement had ever confirmed.
- Live velocity limits on the dashboard - the way to settle a print that is ringing without
  stopping it - and a slider for each fan the configuration names itself.
- Babystepping on the dashboard, a stop on every tab while a print runs, and a notification when
  a print ends, carrying klippy's own reason when it stopped.

### Changed

- **The Marlin side is frozen.** The G-code path is two routes rather than one with a switch in
  it: FrozenGcodeRoute is TrioSlicer 1.4.0's code verbatim, KlipperGcodeRoute owns everything
  Klipper, and neither can call the other. Marlin output is pinned by tests against the 1.4.0
  text and a golden file of every layer event, so a Klipper change cannot alter it silently.
- Motion is refused while a print runs rather than merely discouraged: homing, jogging, stepper
  release and hand extrusion would each leave the printer out of step with the file.

### Fixed

The same five-agent audit as the last release, and the same rule that every finding was checked
against klippy's source before it was believed. Grouped, not ordered:

- **Restoring the shipped configuration kept the calibrations it promised to keep.** klippy
  loads the file and the SAVE_CONFIG block together and the file wins, so a pristine body in
  front of a saved block silently reverted the probe offset, the PID terms and the shaper
  frequencies - and the next SAVE_CONFIG wrote them away for good.
- **Starting a print while one was paused destroyed it and broke Pause until the host was
  restarted**: klippy only refuses while its work timer runs, and a pause stops it, leaving
  pause_resume still paused while the file carried on printing.
- **A print that dies with the host is now recorded and announced** instead of leaving half a
  part and nothing else; a refused print says so where the button was pressed; a failed upload
  no longer leaves a truncated file that prints as far as it goes and is reported as finished.
- M17, which klippy has never had, replaced by SET_STEPPER_ENABLE per stepper; "Cool everything
  down" now turns off every heater rather than the two its command named; `restart_method` is
  kept, because without it FIRMWARE_RESTART falls through to toggling DTR, which this
  connection cannot deliver.
- Numbers that were wrong on screen: memory reported as bytes when the file counts kilobytes,
  the board's duty cycle shown as a fraction of one rather than of its five-second window, and
  endstops reading "true" where klippy says TRIGGERED.
- A saved measurement can no longer be silently overridden by a stale duplicate: the shaping
  writer left one option per key, and klippy reads the last one.

### Notes

This is a pre-release and still in testing. The Klipper host this app runs itself is the
tested path; driving a host on a computer is new, and was verified against one machine - this
author's. Nothing in this release changes what a Marlin printer is sent, and that is held to by
tests rather than by care.
## [1.5.3] - 2026-09-27

### Added

- **The printer's resonances can be measured with the phone.** The Shaping screen plays
  Klipper's own sweep through the printer and records it with the phone's accelerometer, then
  reports the frequencies the machine answered at, strongest first, each with how far it
  stands above the surrounding response. Choosing one puts it in the frequency field, and a
  test print with corners says whether it helped.
- The phone's sensor runs at its own maximum rate - 416 Hz on this device, four times
  oversampled across the shaper band - and the rate used is measured from the samples'
  timestamps rather than assumed, because a sensor delivering slower than it promised would
  put every frequency in the wrong place.

### Fixed

An audit of the new front end - five agents, one per area, every finding checked against the
code by hand before it was fixed - turned up the following. They are grouped, not ordered:
the first three cost a print or a printer, and the rest cost trust in a screen.

- **A file name with a space made every print button do nothing.** klippy re-parses a
  non-traditional command with shlex, so `FILENAME=Phone Stand.gcode` is two words to it and
  the answer is "Malformed command" - after the file had been copied in and listed. Names are
  quoted wherever one is interpolated into a command.
- **A saved shaper value could be lost by looking at another tab.** Only one tab is composed
  at a time, and a composable that leaves composition is forgotten rather than saved, so the
  Shaping fields were re-derived from the configuration the moment the screen was left - and
  the save that followed wrote the configured value, not the frequency that had just been
  measured. The sub-tabs keep their state now, as the destination above them always claimed to.
- **A damping ratio of 1.0 could stop the printer.** Every shaper klippy has divides by
  `sqrt(1 - zeta^2)`: sent, it shuts the printer down; saved, it leaves a configuration the
  host will not start from. It is refused at the screen and again where the file is written.
- **The measurement stopped short of the band** on the first measurement after homing - the
  recording allowed three seconds beyond the sweep and the module spends three seconds driving
  to the test point first, so every frequency above ~116 Hz was silently absent. The recording
  allows fifteen now.
- **Low resonances were being dropped.** The drive's own acceleration climbs with the
  frequency, so a peak graded against the middle of a tilted curve needed four or five times
  its neighbourhood instead of two. The ramp is divided out of every curve, not only the one
  measured from the toolhead.
- A host restart dropped the wake lock and never took it again; a relaunch after a crash left
  the bridge pumping the previous pty while the new host was pointed at another one, leaking a
  descriptor per relaunch; and a start arriving during a restart could put two klippy processes
  on one socket. The lock is taken by every launch, the bridge is stopped and the old
  descriptor closed before a new pty is opened, and launches are serialised.
- An imported configuration that names a `baud:` rate is accepted: klippy does not read it on a
  pty, and then refused to start over the option it never used.
- Saving shaper values appended a second copy of every key, because the option lookup
  understood `name:` and the configuration ships `name = `.
- The Files screen can read the app's own G-code: OrcaSlicer keeps the printing time, the
  filament used, the layer height and the thumbnail in a trailing block below the G-code, which
  was never read, so only the slicer's name was shown.
- The mesh reads its saved profiles as the dictionary klippy sends rather than as an array, so
  profiles on the printer appear and can be loaded or removed; a bed that has never been probed
  is no longer a loaded mesh of one row by no points; probing waits for the machine to be homed;
  and removing a profile asks first.
- A `[temperature_fan]` is given the command it has a handler for rather than the heater one,
  PID calibration addresses the heater by the short name, the micro-controller timing is asked
  for again once the printer is up, and a board the app can drive can be granted USB permission
  instead of being found, recognised and left on the bus.
- Jogging asks whether the axis is homed rather than the whole machine, the feedrate and extrude
  speed fields reach the printer instead of being dropped, and the cancel dialog no longer
  promises that the heaters are left on - this printer's own `CANCEL_PRINT` turns them off.

### Notes

- The recording and the printer share no clock. The analysis finds the moment the machine
  started moving inside its own recording, and takes the frequency axis from the sweep's
  schedule, which is linear in time: `df/dt = hz_per_sec` exactly, because a half period
  lasts `0.25 / f` and the frequency advances by `2 · (0.25 / f) · hz_per_sec`.
## [1.5.2] - 2026-09-27

### Added

- **An input shaping screen.** Two numbers per axis - the shaper type and the frequency it is
  tuned to - decide the pattern the steppers are driven with, so that a machine's ringing
  cancels itself instead of printing as ripples beside every corner. Both are editable, both
  can be applied live, and both can be saved into `[input_shaper]` in the printer's
  configuration, which is where a restart reads them from. Nothing needs to be measured to be
  adjusted: an accelerometer makes finding the frequencies easier and is not required to use
  them. Each type's lowest useful frequency is stated, and warned about when a value goes
  below it.
- **The resonance sweep can be played without an accelerometer.** A file of the app's own now
  travels in the staged payload — `PLAY_RESONANCES` — which mirrors Klipper's
  `ResonanceTestExecutor` line for line and plays the same 5→135 Hz sweep, so a sensor
  outside the printer (a phone on its base) can measure what the machine does. It is a file
  beside Klipper rather than a patch to it, and `scripts/verify-resonance-playback.py` checks
  its schedule against Klipper's own generator at every staging. The printer's configuration
  gains one line for it — an include of the app's own `app.cfg` — added above klippy's saved
  block.
- The screen can also ask the printer what shaping it is currently using - klippy publishes
  no status for the input shaper, so that is the only way to see it, and the answer arrives
  in the console.
## [1.5.1] - 2026-09-27

### Added

- **A Z probe screen**, which is the one calibration a printer with a probe cannot do
  without a hand on it: the probe finds the bed, then the nozzle is brought down onto a
  piece of paper and that position becomes the offset the first layer is decided by. The
  screen heats the nozzle and bed first (both grow when hot), homes, runs the calibration
  and takes the four nudges that find the paper - through `TESTZ`, so klippy is measuring
  the offset rather than being moved behind its back - then accepts it and writes it to the
  configuration.
- The two Z offsets are shown apart, because they are easy to confuse and only one of them
  persists: the **probe's offset**, from the printer's configuration, and the **live
  offset** applied on top of a print that is already running.

### Changed

- The Z offset buttons moved from **Move** to the **Z probe** screen, so both offsets and
  the calibration that sets one of them are in the same place.
## [1.5.0] - 2026-09-27

**The printer front end is new, and it is in testing.** The built-in Klipper host has
driven the printer from this app for a while; what it did not have was a way to see it
or to set anything. It has ten screens now, and they are the part to be careful with:
the host itself has printed, but a long unattended print has not yet finished on it,
and the two calibrations below are still outstanding.

- **The printer's own configuration can be imported.** The app ships with the
  configuration of the machine it was developed on, which is the right starting point and
  the wrong one for any other printer. Machine -> Klipper setup takes a `printer.cfg` -
  and any files it includes - and puts in only the parts that are the phone rather than
  the printer: the serial port it can actually open, the file list it actually writes.
  Pins, steps, directions, probe offsets, macros and saved values are carried through
  untouched, and each change is listed when the import finishes. Once a configuration is
  imported it belongs to its owner: the app will not rewrite it again, not even to apply a
  fix to its own, and the screen says which of the two is running.
- **Export the configuration**, so a calibration can be taken back to a host install or
  sent on when something is wrong.

### Added
- **Ten screens for the printer, inside the app.** Dashboard, Temperatures, Move,
  Extrude, Macros, Files, Console, Mesh, History, Machine - the tabs someone who has
  used Mainsail already knows where to find, arranged for a phone. The app is its own
  front end: nothing is installed beside it, and nothing is configured.
- **The screens follow the printer, not a list in the app.** What is subscribed to is
  decided at every connection from the printer's own objects, so a chamber sensor, a
  second micro-controller or a fan you named appears on its own.
- **A console that shows both sides.** Everything the app sends is written down beside
  what klippy answers, including its errors - which is how the bug below became
  visible in one screenshot rather than in an afternoon of guessing.
- **A live temperature chart**: five minutes, one reading a second, per heater and
  sensor, with presets and PID calibration.
- **Files read their own headers** - the slicer, the estimated time, the filament, the
  layer height and the embedded thumbnail - and can be printed or deleted from the list.
- **Move**: homing, a jog pad with step sizes and a feedrate, Z-offset nudges, and the
  printer's own leveling macros.
- **Mesh**: the probed bed drawn as a picture, with profiles to load, save and remove.
- **Print history**: what this app has printed, written down as each print ends.
- **The host is supervised.** klippy exits by itself after saving a calibration and
  when it crashes; either way it is started again rather than leaving the printer with
  no host until somebody notices.

### Fixed

- **Every jog, Z nudge and extrusion was being sent as `G1 Y10,000 F3000`** on a phone
  whose language writes decimals with a comma. String formatting follows the locale, so
  the printer answered *Unable to parse move* and the buttons did nothing at all.
  Numbers that go to the printer are now formatted for the wire, and the tests run
  under a comma locale.
- **A calibration saved with `SAVE_CONFIG` was lost on the next start of the host.**
  The configuration file was rewritten from the app's assets every time, and the
  printer's port changes every time; a PID calibration, a Z offset or a mesh profile
  had nowhere to live. The port is a name that does not move now, so the file is the
  printer's own - and the app can say when it no longer matches what it ships and put
  the default back without losing the saved values.

### Known, and next

- The USB link occasionally stops being answered (the host restarts itself within
  twenty seconds when it notices). A long unattended print is the test that settles it.
- `z_offset` is still 0 and the hotend is still on the printer's original PID values.
- The extruder's direction was corrected in the configuration, and the steps per
  millimetre are inferred rather than measured; a measured 100 mm extrusion settles it.

## [1.4.0] - 2026-09-26

### Added

- **Transform the model with the on-model gizmo.** Long-press the model and three modes float up above it: **Rotate**, **Move** and **Scale**. Rotate draws coloured rings around the model - red X, green Y, blue Z, Cura's colours - and dragging a ring turns the model about that axis, snapped to 15 degrees, with the angle in a hovering readout. Move draws an arrow per axis: drag one and the model slides along exactly that axis, the vertical one lifting it off the bed, with the distance read back in millimetres. The handle under your finger is drawn fatter and lighter, only a handle starts a transform - a drag anywhere else still orbits the camera - and the menu steps aside while you drag, leaving just the value you are dialling in.
- **Scale has a slider that snaps in 5% steps and a percentage field beside it**, for a size you already know, both previewing the model as you go.
- Long-pressing the model while the gizmo is up, or dragging anywhere on the plate, is still the camera's.

### Added (1.4.0, earlier in this release)

- **Drag the model across the plate with a finger.** *Model tools → Transform* has a **Move with finger** button: while it is on, one finger drags the model over the bed and two fingers still orbit, pan and zoom. The drag previews itself in the viewer and the placement is applied once, when the finger lifts, so a large model moves without re-transforming its mesh on every frame. The movement is projected onto the plate, so a drag cannot lift the model off the bed or sink it into it, and a move that would leave the build volume is refused by the same check the typed position goes through.

### Fixed

- **The modelling preview follows the engine's own model.** The preview rendered only when the camera moved, the view was resized or a gesture settled, and nothing told it that the agent had published a new model: the picture kept showing the previous one until something unrelated forced a render — in practice, collapsing the chat, which resizes the box. It now watches the STL the engine rewrites for each finished iteration and renders again when that revision changes, which is also the moment the app imports the model.

## [1.3.10] - 2026-09-25

### Added

- **The Blender engine's MCP token can be copied from the app** (Blender menu → *Copy MCP token*). The token lives in app-private storage and the engine refuses every command without it, so on a phone without root there was no way to hand it to a modelling agent: the file cannot be read from outside, which is what the token is for. Copying it marks the clip sensitive, so Android keeps it out of the clipboard preview.

## [1.3.9] - 2026-09-25

### Added

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
