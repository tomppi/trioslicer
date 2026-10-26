# SolidCraft: the live CAD engine

TrioSlicer's main plate can show a **CAD document** instead of a mesh. The document is a
SolidCraft 0.22.1 design held by a SolidCraft engine process; the agent editing it is the author,
and the plate is a live view of the engine's document. The human keeps every plate function he
had - move, rotate, scale, lay flat, split, snap fit, paint, Slice, Export, the layer and path
views - because those act on a **derived mesh**, not on the document.

The document's author is **the app's own agent by default**: it calls an OpenAI-compatible
chat-completions endpoint directly from the phone and runs the engine's tools in process, so the
user needs no computer, no harness and no second screen. An external agent through the engine's
MCP bridge is the advanced option, kept for desktop tooling and for the existing skills.

This file records what is packaged, how the pieces talk, why the view is incremental, and what
is deliberately not in it.

## What is packaged

| part | what it is |
|---|---|
| app/src/main/jniLibs/arm64-v8a/libsolidcraft_exec.so | SolidCraft 0.22.1's solidcraft-cli, cross-compiled for arm64-v8a with --no-default-features, stripped. It is a PIE executable staged under a .so name so Android unpacks it into the app's native library directory; libocct_exec.so is the same pattern. 63 MB. |
| scripts/build-solidcraft-engine-android.sh | the cargo cross-build (NDK r28c, linker aarch64-linux-android24-clang), the strip, the staging, and --package for the release tarball. |
| scripts/fetch-solidcraft-engine-android.sh | the SHA-256-pinned download of that tarball as a release asset (tag solidcraft-engine-v0.22.1), or a local tree through SOLIDCRAFT_ENGINE_DIR. |

Rebuilding from source:

    scripts/build-solidcraft-engine-android.sh --package
    # prints the tarball's sha256 and the release command
    gh release create solidcraft-engine-v0.22.1 .build/solidcraft-engine/solidcraft-engine-arm64-v0.22.1.tar.gz

The digest is pinned in the fetch script; **re-pin it deliberately**, the way the OCCT and CAD
payload digests are pinned. "gradlew :app:verifySolidCraftEngineExecutable" checks that a staged
binary is an ELF engine of a plausible size, and is not wired into assemble because the release
asset is published separately from the source commit (CI has no Rust cross toolchain).

## The pieces

    in-app agent (this process)  --->  SolidCraftAgentHost --->  SolidCraftSession  --->  solidcraft-cli mcp
        (OpenAI-compatible endpoint)        |  (runs the tool, reports the delta)   |  (stdio MCP)
                                            v                                       |
    external agent (MCP client) -->  SolidCraftBridge --+                           |
        MCP over TCP 127.0.0.1:9878     (forwards, reports changes)                 |
        (token)                             |                                         |
                                            v                                         |
                                     MainViewModel  <---- DocumentDelta -------------+
                                            |
                                            v
                                     derived mesh ---> ModelSurfaceView (the existing renderer)

- **solidcraft/SolidCraftEngine.kt** owns the child process. It starts it as
  libsolidcraft_exec.so mcp --headless --allow-path <files/solidcraft/workspace>, points
  TMPDIR, HOME and SOLIDCRAFT_RECOVERY_DIR into the app's private directories, drains its
  stderr into logcat, and stops it when the app finishes. The engine's own file sandbox is the
  one allowed directory: designs, opened files, body tessellations and exports are all inside it.
- **solidcraft/McpClient.kt** is the stdio MCP client: one reader thread, a pending map keyed
  by JSON-RPC id, per-call timeouts, and named failures (McpTimeoutException,
  McpRemoteException, McpTransportException). A timed-out call reports that the design may
  still have changed, the same honesty the engine's own protocol asks for.
- **solidcraft/SolidCraftSession.kt** is the typed tool surface the app uses itself (export a
  body, save the design, open a file, inspect bodies) and the one the in-app agent's tool host
  calls; it is the same stdio session either way.
- **solidcraft/SolidCraftBridge.kt** is SolidCraft's agent door: the same token boundary the
  Blender (9876) and Python CAD (9877) engines use, on 9878, refusing the engine's screenshot
  tool because this app's viewport is the only renderer. It binds 127.0.0.1 like the other two
  engines; the network side is the app's forwarder (below), not this socket. Every tools/call
  that may have changed the design is turned into a DocumentDelta for the live view.
- **agent/AgentBridgeProxy.kt** is the network side of all three agent engines. It listens on
  the device's own network address at 9876, 9877 and 9878 - the engines' loopback ports, kept
  identical - and forwards each connection's bytes both ways to 127.0.0.1 at that port. A
  specific network address and 127.0.0.1 can both hold the same port, which is why the numbers
  do not change. It is transport only: each engine still checks its token on every message, the
  forwarder checks nothing, and it never logs message content.
- **agent/AgentBridges.kt** owns the CAD agent sheet's **Reachable on the network** switch: the
  stored choice, the three targets, the watcher that follows the device's address (so a Wi-Fi
  change rebinds instead of leaving a stale line), and the per-engine status rows
  ("app bridge up" / "engine not running"). Turning the switch off closes every listener
  before it returns.
- **agent/BridgeNetwork.kt** holds the stored preference and the IPv4 address rule behind the
  connect lines, and it is the one place the app enumerates the device's interfaces
  (java.net.NetworkInterface only - no WifiManager, no connectivity service, no interface names).
- **solidcraft/CadDocument.kt** holds the document record, the per-body tessellation composer,
  the workspace path rules and the Slice-export handoff.
- **cadagent/** is the in-app agent: the endpoint settings with the sealed key
  (`AgentSettings.kt`), the bounded tool-calling loop (`CadAgent.kt`), the OpenAI-compatible
  client (`OpenAiChatClient.kt`), the engine's catalogue as OpenAI tools
  (`AgentToolCatalog.kt`), the optional web tools (`AgentWebTools.kt`), the observable tool
  trace (`AgentToolTrace.kt`: the redacted start/end row model), and the system prompt
  (`AgentRunbook.kt`: live rules + the skill document shipped as an asset).
- **solidcraft/SolidCraftAgentHost.kt** executes the in-app agent's tool calls on the live engine
  session - in process - and reports the same DocumentDelta the bridge reports, so the plate
  repaints the same way.
- **solidcraft/SolidCraftAuthorship.kt** is the one-author rule: an in-app turn in flight or
  attached bridge clients, never both.
  the workspace path rules and the Slice-export handoff.

## The in-app agent (the default)

The desk is not part of the loop. The app's own agent calls an **OpenAI-compatible
chat-completions endpoint directly from the phone** and runs the SolidCraft tools **in process**
against the engine session the app already owns - no bridge, no socket, no token, no computer.

- **The tool catalogue is the engine's.** Every turn reads the engine's own `tools/list` over
  the child's stdio and converts those definitions to OpenAI function tools; the app restates no
  schema. The bridge forwards the same list to external agents, so one engine catalogue serves
  both - a tool the engine adds appears on both paths without an app change. Five engine tools
  are not offered to the phone agent because they do not apply here: `screenshot` (the plate is
  the viewport), `crash_reports`, `running`, `pending` and `skill_reference` (desktop-app
  surfaces).
- **The runbook is the skill document.** `docs/skills/solidcraft-engine.md` is copied into the
  APK at build time by `:app:copyAgentRunbook` (`assets/agent/solidcraft-engine.md`), and the
  system prompt is that asset plus the live rules only the app knows: the agent is the
  document's only author and the user is a spectator, every edit shows on the plate as it
  happens, the engine's screenshot is never used, and the camera is the user's.
- **The endpoint defaults to DeepSeek**: `https://api.deepseek.com` with model
  `deepseek-chat`, and both fields are editable. The API key is sealed with the device keystore
  through the same `storage/SealedValue` arrangement as the Moonraker key, read into memory only
  for a run, and has no field in the settings model the sheet renders: the sheet shows a mask and
  never binds the key into a text field, a log line or the chat transcript. A provider on the
  user's own network that needs no key works too - with no key set, no Authorization header is
  sent.
- **Web search and page fetch are optional, and share the credential.** When the endpoint offers
  DeepSeek's `/tools/search` and `/tools/fetch` routes and the sheet's switch is on, the model
  also gets `web_search` and `web_fetch`, using the same base URL and key. Results are bounded
  (at most 5 results, ~800 characters each of snippet/summary; a fetched page ~8k characters) and
  arrive inside an explicit **untrusted web content** boundary. The system prompt says page text
  is data, never instructions: nothing from the web may change the task, the document or the tool
  list. A 401/403/404/405 on those routes removes the tools for the conversation and the agent
  says once that search is unavailable - never a silent failure and never a fake empty result.
  The plain-words "searching the web for …" / "reading …" lines stay in the sheet's compact
  transcript; the floating chat shows the tool row instead.
  DeepSeek answers chat completions at the API root and its tools under `/v1`, so a base URL
  without `/v1` is tried under both forms (only 404/405/501 are retried, a wrong key is still a
  401 named at once) and the form that answered is remembered for the session.
- **A turn is bounded and cancellable.** Cancel disconnects the request in flight and stops at
  the next boundary (a tool call already handed to the engine finishes, and the design may have
  changed); a model that keeps asking for tools stops after 8 tool rounds, and the sentence says
  how many rounds ran and that one more message ("continue") carries on from there. HTTP,
  timeout and malformed-reply failures are named exceptions with the endpoint's own sentence,
  redacted against the key, and reach the conversation as an error row - never silence.
- **A turn outlives the screen going off.** The screen is allowed to sleep: nothing in the agent
  path sets a keep-screen-on flag. What keeps the work alive is
  `cadagent/InAppAgentTurnService.kt`, a foreground service started with the turn, with a
  low-importance notification naming the CAD agent as working, so Android cannot cache and
  freeze the process when the app leaves the foreground; inside it, a **bounded partial wake
  lock** is armed with the turn and released on every exit path (answer, error, cancel, service
  stop), so no lease outlives the turn it was taken for. A transport failure or read timeout
  *before any tool of the turn has run* is re-sent **once** after 1.5 s and the transcript says
  "the endpoint did not answer; retrying once"; once a tool has run the request is never
  re-sent, because that could repeat an edit. The final error row names the endpoint host, the
  model round and the elapsed seconds, and points at the cause worth checking: the screen may be
  off - that is fine - but a backgrounded app can be frozen by Android. A response body shorter
  than its declared Content-Length is treated as a transport failure (the connection died
  mid-answer), a context-length HTTP 400 is named as the model's context window, and a 429 keeps
  its wait-and-ask-again advice.

### Pointing at a face, and letting the agent look

The plate can be **asked about a face** and can **show the agent the picture**, and both are the
app's own - the engine's `screenshot` is still refused.

- **Select mode.** The CAD agent sheet's **Select a face on the plate** closes the sheet and arms
  the plate. A tap then goes through `ModelSurfaceView`'s existing surface-pick hook and the same
  `MeshPicker` the brush and the joint use (`faceSelectActive` makes it one pick per gesture).
  The picker answers in plate coordinates, so the plate object's placement is undone
  (`ModelPlacement.documentPlacement`) and the tap is matched against the engine's own
  `model.faces {body}` report: planar when it lies on the face's plane, cylindrical when it lies
  on the cylinder, otherwise the nearest area centre. The resolved face is addressed all three
  ways the engine accepts - name, index and a point on it - and `select.set {items:[{type:"face",
  body, index, point}]}` makes the engine's selection agree; the engine refused any face item
  missing one of the three, which is why all three are sent.
- **The tint is the renderer's, not the chip's.** `SelectionTintBuffer` is a byte per scene
  vertex, the paint buffer's shape but a buffer of its own: the mesh shader mixes the selected
  face's warm amber (outside the support palette and the layer preview's ramp) over the shaded
  colour. Selecting never writes the paint path and a paint stroke never tints - arming one mode
  disarms the other through `PlateModes`, and the viewer's two pick callbacks are exclusive. The
  HUD chip names body, face, surface, area and which way it faces from the current camera, with
  **Clear** one tap away. Orbiting keeps the tint (a face is not a screen position); the words
  are recomputed for the camera at the moment the HUD or a prompt is built.
- **The next message carries `SELECTED FACE`.** body, face name and index, surface, area,
  centroid, outward normal (from the picked triangle, so a hole reads as facing into it), the
  point on the face, and the view-relative description. The runbook
  (`docs/skills/solidcraft-engine.md`) tells the agent to act on that face - `solid.offset_face`,
  `solid.shell`, `plastic.*` - rather than infer one from the words, and to quote its name and
  area.
- **A repaint re-resolves the face by NAME.** After an agent edit the whole document is
  re-tessellated and every triangle is renumbered, so the selection is not carried as indices: the
  face's engine name is looked up again in the new body's `model.faces`, its triangles are
  re-tinted and the engine's selection is moved to its new index. A name the edit removed or
  replaced drops the selection with a sentence that names it (`Face selection cleared: F1:-y on
  Body1 …`); a new document, an import, a split, a snap fit or a Rebuild plate drops it the same
  way. A camera-only change never does.
- **The plate render.** `plate_screenshot` is the app's own tool beside the engine's in
  `AgentToolCatalog`/CadAgent (no engine call): `ModelSurfaceView.capturePlate` asks for the next
  frame, the renderer reads its own frame buffer back at the user's camera, scales the long side
  to 768 px and encodes a JPEG. It is the plate view alone, never the app around it and never the
  engine's `screenshot`. The runtime attaches it to the next user-role message of the turn as the
  standard OpenAI content part (`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}`).
  Its tool result is one line ("rendered the plate: 1 body, 12,000 triangles, current camera; the
  image follows"), a caption states the selected face is tinted when it is, and the transcript
  names the picture by size and dimensions - `ChatTranscript` replaces any data URL rather than
  copying its bytes.
- **"Lend the agent my view", default on.** The CAD agent sheet's switch attaches a fresh plate
  render to any message that carries a face selection, so "make this face 2 mm thicker" arrives
  with the tinted face in the picture rather than only in words. Off, the block alone goes.
- **Vision is a capability, not an assumption.** A 400 whose body names the image (image/vision/
  multimodal) means this model cannot see pictures: the app strips the images, re-sends the same
  request once as text, records the sentence *this model cannot see images; sent the geometry
  description instead* in the transcript, and remembers it for the session - no further render is
  attached, and the transcript never claims one was seen. A 400 that is not about the image keeps
  its own named failure.

### Watching it: the chat surface

The AI chat (the floating `ui/AiChatOverlay.kt` the harness chat always used) is now the
in-app agent's window too, and its door is **Ask AI** in the sidebar rail - in the same list as
Plate / Settings / Print / More, in the fold's permanent column and in the phone's collapsible
sidebar alike. It is an action, not a destination: it opens the overlay - over whichever tab is
showing, so it works from Settings or Print as well - and leaves the selected tab alone, with no
badge or highlight when idle, and it opens at the size the chat was last left at. The Blender
menu's old entry is gone - the chat has one primary door. A source switch at the top chooses
**CAD agent (in-app)** (the default) or **Harness**, and that switch is the same state as the
sheet's mode chips: typing in either box always reaches the backend the chat is showing.

- **Two backends, two conversations.** `ui/CadConversations.kt` holds the in-app agent's
  messages (tool rows included) and the CAD harness session's messages as separate lists, and
  every write is one-sided: the in-app loop appends only to the in-app list, the harness poll
  repopulates only the harness list from `cad.messages()`, and a tool row can only exist on the
  in-app side. Switching the source never shows the other backend's transcript - the bug this
  replaced was one shared `cadMessages` list, where a harness case from the user's PC appeared
  under "CAD agent (in-app)". Each source has its own named empty state: **No in-app
  conversation yet - ask for a change** / **No harness session - connect the harness from the
  Harness tab**. The header names the backend and its state - `In-app - deepseek-chat at
  api.deepseek.com` or `Harness - session connected` / `Harness - not connected` - and the
  Author line stays under it in both sources. The in-app list is not persisted, so it is cleared
  when the CAD document is closed or a different document is opened: a transcript from another
  document is worse than an empty one.

- **Tool rows, live.** Every tool call is emitted as a redacted `AgentToolTrace`
  (`cadagent/AgentToolTrace.kt`) twice: at the start (running) and at the end with its duration
  and one-line outcome. The chat draws the transcript with those rows in place:
  `solid.box {length=40, width=30, height=10} · 12 ms · 1 body created (Body1)`, a
  `web_search "M3 socket head" · 13 ms · 1 result`, and, when a call fails or a route is
  missing, a red row carrying the engine's or the endpoint's own sentence
  (`HTTP 404 - this endpoint does not offer web search`) instead of silence. The argument
  summary is built and masked before it leaves the agent, so the API key and the bearer header
  never appear in a row.
- **Stop** in the chat footer is the same cancel the sheet's Cancel uses: it sets the agent's
  cancelled flag and disconnects the request in flight - not the harness session cancel, not an
  engine stop. The chat stays readable and scrollable while the turn streams.
- **Copy transcript** puts the messages and the tool rows on the clipboard as plain text
  (`You: …`, `tool: solid.box … 12 ms · 1 body created (Body1)`, `Agent: …`) so a bad turn can
  be handed to a human or another agent. Nothing secret is in it.
- **The agent can ask a question - and the app makes sure it is seen.** `ask_user` is the
  app's own tool beside `plate_screenshot` and the web tools: a required question, optional
  short options, and `allow_text` (default true). Running it appends a **question card** to the
  in-app conversation (question, one chip per option, the composer for a free answer, *The agent
  is waiting on you.*) and **ends the turn**: there is no blocking wait, nothing spins and the
  engine is not held; the answer arrives as the next user message and starts a new turn, which
  the tool description and the runbook both say. The runbook tells the agent when to ask -
  ambiguous requirements, a destructive or irreversible edit, a dimension or material the user
  has not given - and never to ask for something `inspect_design`/`measure` can read. While a
  question is unanswered and the chat is closed, the rail's **Ask AI** action shows the one badge
  it can have (a small pulsing dot); if the app is in the background a notification *CAD agent is
  asking a question* opens the chat, and a denied POST_NOTIFICATIONS leaves the rail pulse as the
  fallback. The pending question clears on answer, Dismiss, any new prompt, and on closing or
  converting the document - a dismissed question cannot leave a stuck wait. The harness has its
  own ask-user on the harness side; this is the in-app agent's version of the same idea.
- **A rotation keeps the conversation.** The manifest leaves `android:configChanges` unset, so
  rotating the phone recreates the activity. The CAD conversation, the modelling chat's messages,
  their busy/status flags and the chat's selected source live in `MainViewModel` (Compose state on
  the ViewModel, which the activity's ViewModelStore keeps across the recreate), and the in-app
  turn runs in the ViewModel's scope, not a composition scope - so the transcript, a running
  turn's **Working…**/Stop and the source survive a rotation, and the tool rows produced before it
  are still there when the answer lands after it. The harness chats re-adopt their stored session
  ids and reload their transcripts from `messages()`; the in-app list is untouched by that
  re-adoption. This is configuration-change survival only: nothing is persisted to disk, so a
  process death starts an empty conversation. Unsaved sheet endpoint fields and the draft are
  rememberSaveable; the API-key field is not, because a typed credential is not put in saved
  instance state.
- **Three sizes.** The bubble, the floating panel and **full screen** (`ui/ChatSize.kt`): the
  header's expand button (diagonal arrows) takes the panel to full screen, its collapse button or
  the Back gesture returns to the panel (a second Back closes the chat), and the bubble stays
  reachable from either. Full screen is one surface over the running plate - source switch,
  author line, the transcript filling the height (a readable column on a wide window), composer
  and Stop pinned at the bottom. A resize never stops the engine or the turn and never resets the
  scroll; the chosen size is remembered for the session, so a chat left full screen reopens full
  screen and one left as a panel reopens as a panel.
- The header names the document's author in every state, so an attached external agent reads as
  **Author: external agent on 9878 · in-app agent paused** in the chat too, and the composer
  refuses to send while another author has the document.

### One author at a time

`SolidCraftAuthorship` is the single owner of who may edit: **an in-app turn in flight** or
**attached external bridge clients** (live connections, not a past client), never both. The
bridge counts sockets as they attach and detach; the in-app turn claims the ownership before its
first request and releases it in a `finally`. The sheet polls the state and names the author in
every state:

| state | author line | composer |
|---|---|---|
| nobody | `Author: nobody` | in-app composer enabled |
| in-app turn in flight | `Author: in-app agent` | Cancel shown, Send disabled |
| external client attached | `Author: external agent on 9878` · `external agent connected - in-app agent paused` | disabled, with one **Take over** button |

**Take over** (behind a confirmation that the outbound agent must reconnect) closes the live
external connections and detaches them before returning, so the next poll already reads nobody.
While the in-app agent has a turn in flight, the bridge answers a new connection with JSON-RPC
error `-32010` naming the in-app agent and closes it: it never queues that client and never
disconnects a client that is already attached to make room. The mutual exclusion is enforced and
asserted inside the owner object, not in the UI, so a screen cannot produce two authors.

## The live view

The document's geometry is the engine's. The plate draws it as **one plate object per part**: the
tessellation is one mesh per body (CadBodyMeshes), and each body becomes its own object with the
engine's body name and the id cad:<body> (CadPartPublish). The picture is kept live by patching
only what an edit named:

1. the agent calls execute/batch over the bridge; the engine answers with
   created_bodies / changed_bodies / removed_bodies (verified shapes: a box reports
   created_bodies: ["Body1"], a fillet changed_bodies: ["Body1"]);
2. the bridge turns that into a DocumentDelta (undo, redo, a parameter rebuild, an opened file
   say no bodies and become a full re-read - still of the same scene, body by body);
3. the app exports **only the named bodies** with file.export {bodies: [name]} - one batch call,
   one STL file per body - parses them and patches the composer;
4. each edited body's mesh replaces **that part's** plate object's sourceMesh in place: same object
   id, same name, same camera, and every other part's object is left exactly as it is (a body the
   edit did not name keeps the same mesh instance). The plate objects are updated, never reloaded;
5. each part's own human edits are re-applied: the placement transform is carried over relative to
   that part's old mesh (a part that grows keeps its turn and its place; it never recentres), painted
   triangles are kept while the triangle count matches and are otherwise **named as cleared** in
   the status;
6. a plate that has been **split** (or a snap pair applied) is no longer the document's object:
   the app marks the document stale, says which shape the plate is showing, refuses to Slice it,
   and offers **Rebuild plate from the document** in the agent sheet.

No debounce: each edit repaints as soon as it is reported. A second edit that arrives while one
is being applied is merged into the next repaint rather than dropped. Timings are measured in
MainViewModel ("cad live: call->export ... export->mesh ... mesh->state ... total ...") and the
first frame after the state change is logged as "cad frame". The walk in .build/walk-114/
carries the measured numbers.

The camera and gestures are the existing ModelSurfaceView's: orbit, pinch, two-finger pan and
double-tap reset are untouched, and a repaint never calls resetCamera because the object keeps
its identity and name. The engine never touches the camera and its screenshot/render tools are
never used.

## The plate functions on a CAD document

| plate function | on a live CAD document | how |
|---|---|---|
| orbit / zoom / pan / double-tap reset | yes | the same ModelSurfaceView gestures |
| move by finger, position, rotate, scale, gizmo | yes, per part | each part's own PlateObject.placement, carried onto its new tessellation on every document change |
| lay flat, drop to plate, reset/centre | yes, per part | same placement path |
| apply imported Cura transform | yes, per part | same placement path |
| split at a plane | yes, per part | split a part's object; the halves are marked as derived objects, and a later document change marks the plate stale instead of pasting over them |
| snap fit (all styles, cuts, gizmo) | yes, per part | the existing snap pipeline on the derived halves; same stale rule on a document change |
| paint supports / clear | yes, per part | triangle paint on that part's mesh; kept while its triangle count matches |
| multi-object: remove, arrange, settings | yes | the document is a group of plate objects like any others |
| Slice | **no, until converted** | a live document says *Slice needs an STL - convert the CAD document first*; the Slice button is greyed and its tap names that reason. **Convert to STL** is the human's press that clears it |
| Convert to STL / Convert again | yes | exports one STL per part into the workspace's converted/ folder and replaces the document's plate objects with those meshes, at the arrangement the user gave them; afterwards Slice runs as on any mesh |
| Export G-code | yes, after converting | the slice of the converted meshes, as for an imported mesh |
| Export STEP / STL / 3MF | yes | the agent sheet (and the SolidCraft menu) writes the document, unchanged; exports never convert |
| Model / Layers / Path view switch | yes | the slice of the converted meshes, as today |
| sketch / feature / parameter editing | no, by design | the agent is the only author; the app has no UI that edits the document |

### One plate object per part

A document can hold several bodies, and the plate draws each as its own object: the engine's body
name becomes the plate name, the id is cad:<body>, and the placement is the identity on the
document's coordinates - where the agent puts a part is where it prints. Everything the plate
does to a mesh, it does to a part: move, rotate, scale, lay flat, arrange, split, snap fit, paint,
remove. A single-body design is one object and looks exactly as it did before.

The human's edits survive an agent's edit of the same part: a repaint keeps the part's identity
(so the camera does not reset), carries its placement onto the new tessellation relative to the
old one, and keeps its paint while the triangle count matches (and says so when it has to clear
it). Parts the edit did not name keep their very same plate object. The whole arrangement, and
the per-part mapping, are saved in the workspace descriptor (and read back on a relaunch; a 1.9.6
descriptor's single whole-document object still restores and is replaced by the parts on the
first republish).

### Converting to STL is its own press

A slicer takes STL triangles; a live document is not one. The two are separate states on purpose,
and the user asked for the conversion to be a deliberate press rather than a side effect:

1. **A live document** - Slice is greyed, and tapping it says *Slice needs an STL - convert the
   CAD document first*. Beside it, on the plate's actions and in the **SolidCraft** menu, is
   **Convert to STL**.
2. **Press Convert** - each body is exported with file.save_mesh into
   files/solidcraft/workspace/converted/<design>-<body>.stl, and the document's plate objects are
   replaced by one mesh object per part (id stl:<body>) at the placement the human had already
   given that part. The document stays open in the engine.
3. **Converted** - Slice runs on the converted models (one per part); the button reads **Convert
   again**. An agent edit now marks the plate stale with a named reason instead of pasting under
   the human, and Convert again takes the new shape (replacing its own objects in place, same ids
   and placement). Closing the document keeps the converted models: they are the plate's own STLs.

Nothing else converts. Slice does not export the document to make itself work, Export writes the
document unchanged, and a repaint never converts. Converting twice is always the human's choice.

### The SolidCraft menu and the files screen

**SolidCraft** sits in the top bar beside Blender and CAD: New design, Open
STEP/IGES/3MF/OBJ/glTF, **SolidCraft files**, Save design, Export STEP/3MF/STL, Convert to
STL/Convert again, and the engine's state line (*Engine: running / starting / not running*) with
connect, copy-token and stop.

**SolidCraft files** lists the design files in the workspace (files/solidcraft/workspace/designs)
with their name, part count, modified time and size. The part count comes from a tiny sidecar
(designs/<slug>.meta) the app writes whenever it saves or publishes a design; a design it has
never written lists by its file name and says the count is unknown rather than inventing one.
**Load to plate** opens a design as the live document and asks first when a document is open or
the plate holds models. **Delete** confirms by name, and refuses the design the plate is showing:
*this design is on the plate; close or convert it first.* It removes the design, its sidecar and
the engine's display caches - never the converted STLs, which are plate models now. The empty
state says *No designs yet - New design, or open a STEP file.* Nothing on this screen touches the
plate except Load to plate.

## The agent bridge (external agents)

This is the advanced path, and it is unchanged as a transport: an agent on a computer reaches
the same engine session the in-app agent drives. All three of the app's agent engines speak
newline-delimited JSON-RPC 2.0 on loopback, each with its own bearer token: Blender MCP on 9876,
the Python CAD engine on 9877, and SolidCraft on 9878. The token is sent as a top-level "token" field on every message (or inside params).
The CAD agent sheet's row **Copy** is the hand-over: it puts one ready-to-paste line on the
clipboard - `tcp://<this device's address>:<port> token=<that engine's token>` - after the
app's biometric prompt, so connecting an agent is one copy and one paste. The line and the
clipboard handling are in `agent/AgentBridgeHandoff.kt` (AgentBridgeCopy, AgentBridgeTokens).

The CAD agent sheet owns how those ports are reached from outside the phone:

| switch | listeners | how another device connects |
|---|---|---|
| **Reachable on the network** off (default) | 127.0.0.1 only, in each engine | nothing off the phone reaches them; an adb forward or a tailnet serve rule publishes a port by hand |
| **Reachable on the network** on | the app's forwarder listens on this device's network address at 9876, 9877 and 9878, forwarding to 127.0.0.1 at the same ports | the sheet shows one row per engine; its **Copy** puts `tcp://<this device's address>:<port> token=<token>` on the clipboard in one line, and any device that can route to that address connects with that line |

Each line has its own state under it. **app bridge up** means the listener answers and the
engine's loopback port does too; **engine not running** means the listener is up but nothing
answers behind it (start the engine - that is a dead target, not a dead network); **off - this
device only** is the switch-off state. While the listeners are being moved to a new address
the sheet says **address changed - rebinding**. A row whose engine has never started has no
token file yet, so it says **start the engine first - its token is created when it starts** and
its Copy is disabled: nothing puts a line without a token on the clipboard.

The row Copy is gated like the token-only copies always were: the token is read after the
fingerprint (or device-credential) prompt, the clip is marked sensitive so Android does not
preview it, and the toast names the engine, never the token. The token is never rendered in
the sheet - `AgentBridgeLine` carries only the name, URL, state and sentence - and the
forwarder logs no content. The sheet's standalone **Copy MCP token** is kept only as the
fallback for when no published row can offer the one-line form (the switch is off, or there is
no network address to publish on); while a row is copying address + token together it is hidden
rather than being a second, weaker path to the same credential.

The address is not configured anywhere and is not a device name: the app enumerates its own
IPv4 interfaces and picks, in order, an address inside the tailnet range 100.64.0.0/10, else a
private LAN address (10/8, 172.16/12, 192.168/16) on a real interface (the mobile data
interface is only used when no Wi-Fi or Ethernet address exists), else nothing - and then the
sheet shows a named sentence saying which ranges it looked for instead of a dead line. The
rule is in `BridgeAccess.select`, and `BridgeNetworkTest` pins it without a network.

The switch is stored in the app's own preferences file; [AgentBridges.ensureStarted] puts the
listeners back at app start, a watcher re-reads the device address every few seconds, and a
flip of the switch binds or closes them immediately. No listener survives the switch being
turned off, and no engine process restarts for either direction.

**The forwarder is not a gate.** Each engine's token file and per-message check are exactly
what they were on loopback; the forwarder adds no authentication of its own, keeps no state
between connections and never logs the bytes it copies. What else gates the network reach is
what the network itself provides - a tailnet's ACL, or the LAN's own boundary - and the sheet
says so next to the lines.

The engine is the upstream solidcraft-cli; its tool list, parameter docs and command catalog
are the upstream ones (docs/mcp.md in the SolidCraft repository). Two rules are ours:

- **screenshot is refused** by the bridge: the plate is the viewport, and the engine's camera
  is not the user's.
- Files are confined to files/solidcraft/workspace by --allow-path.

## Known limitations

- **A real provider's vision support is untested until the user enters a key.** The
  `SELECTED FACE` block, the plate render, the image content part and the text-only fallback were
  walked against a scripted OpenAI-compatible endpoint on this host (the walk in
  `.build/walk-123/`); whether a given hosted model accepts images is answered by the endpoint and
  named in the transcript, never assumed.
- **A real model provider is untested in this build until the user enters a key.** The in-app
  agent's loop, tool calls and the web tools were walked against a scripted OpenAI-compatible
  endpoint on this host (the walk in `.build/walk-117/`); no call has been made to a real
  hosted provider from the app, and DeepSeek's `/tools/search` route has not been exercised
  with a real key. The sheet's error line names whatever the endpoint actually says.
- **The in-app agent sends the key over the network the endpoint names.** With an https base URL
  that is TLS; with plain http to another machine the key and every prompt travel in the clear,
  and the sheet says so in red. Loopback (a model running on the phone itself) is not observable
  from anywhere else.
- **The published listeners are plain TCP.** Each engine's token authorizes every message;
  nothing encrypts the stream and nothing is signed. On loopback that is the same boundary the
  other engines have always had; with the network switch on, anyone who can reach an address
  and has that engine's token can drive that engine, which is exactly what the sheet says. The
  forwarder logs no message content, so a token does not become a log line. Use a tailnet or a
  LAN you trust.
- One live CAD document per plate. Opening another from the app needs the current one closed;
  the agent can open files inside its own session.
- A document change after a split or a snap joint marks the plate stale (with a named reason and a
  Rebuild action) rather than re-slicing the new shape into the old layout.
- Display and print tessellations are the same STL export: SolidCraft 0.22.1's file.export has no
  tessellation-quality parameter. "Display" and "print" name *when* the tessellation is taken, not
  two different qualities.
- The engine is arm64-v8a only; on other ABIs the status says the binary is not packaged.
