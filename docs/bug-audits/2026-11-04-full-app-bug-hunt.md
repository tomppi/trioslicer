# Bug hunt ledger — 2026-11-04

Confirmed defects found by adversarial code review of TrioSlicer. Every entry is traced to a
reachable path and was independently confirmed (two or three parallel passes where marked).
Severity: **high** — security boundary broken, data loss, app death, or a feature provably
broken; **medium** — silent wrong behavior, resource leaks, reachable races; **low** —
conformance, robustness, content. Latent and unconfirmed observations are listed separately
and are not counted as confirmed bugs.

Status: COMPLETE. ScanRuler embedded engine audited in full (4 passes, Area 1); the
full-app survey ran 16 disjoint area passes (Areas 2-17), all reported and recorded. Totals
(VM-02 = SET-01 deduplicated): 21 high, 42 medium, 61 low. High/medium entries were
independently re-traced against the source by the lead before recording. Latent/unconfirmed
observations are listed per area and are not counted.

---

## Area 1 — ScanRuler embedded engine (releases 1.15.0–1.16.1)

Scope: app/src/main/java/com/tomppi/enderslicer/scanruler/engine/** and its wiring in ui/
(session hosting, in-app agent binding, chrome adapter). Four independent passes: session
lifecycle, WebSocket link, MCP/HTTP/exports, chrome/UI integration. No entry below is pinned
by a test under app/src/test; the baseline unit suite is green. The 1.14 standalone screens
(ScanRulerActivity, ScanRulerTransfer, ScanRulerBridgeScript) were not in this scope.

### High

**SR-01 — The pairing token is published to any loopback client: page impersonation, mesh
exfiltration, fabricated measurement results**
- Where: ScanRulerAssetServer.kt:117 serves GET /__scanruler_bootstrap to any caller;
  ScanRulerEmbeddedSession.kt:414-433 embeds the pairing secret in that HTML twice (the
  redirect fragment "#agent=<port>:<token>" and localStorage scanruler.agenttoken).
- Trigger: any co-installed app GETs http://127.0.0.1:9881/__scanruler_bootstrap, extracts
  port and token, opens ws://127.0.0.1:9880/ and sends the page hello with the stolen token.
  First hello wins, and the real page must finish loading a multi-megabyte bundle first.
- Failure: the attacker becomes "the page": it receives the user's plate mesh over scan.open
  (data exfiltration), its fabricated measurements are returned verbatim to the UI lane and
  the MCP facade, and it can hold the pairing slot forever (SR-08). This defeats exactly the
  boundary MCP_TOKEN_SECURITY.md section 10 claims the pairing token enforces, against the
  very adversary that document counts (any app on the device).
- Fix: never serve the pairing secret over HTTP. Deliver it with evaluateJavascript after
  page load (the app owns the WebView), or gate the bootstrap on a single-use nonce handed
  over the same private channel.

**SR-02 — Unauthenticated out-of-memory on the MCP socket**
- Where: ScanRulerMcpServer.kt:128 — reader.readLine() with no length cap and no read
  timeout, before the token check at :155.
- Trigger: connect to 127.0.0.1:9879 and stream bytes without a newline.
- Failure: unbounded StringBuilder growth; the app process dies of memory pressure, losing
  all in-memory state (plate, scan work). Reachable by any co-installed app and through the
  "Reachable on the network" proxy (9879 is republished).
- Fix: bound the line length (the asset server's 16 KiB readLine at ScanRulerAssetServer.kt:185
  is the in-repo pattern) and set a socket read timeout.

**SR-03 — The one-connection slot is claimed before authentication; one silent socket blocks
every agent forever, and a wedged writer stalls the page link**
- Where: ScanRulerMcpServer.kt:88 claims active at accept time (before any byte is read);
  serve() at :113-140 sets no deadline; notifyToolsChanged (:143-144) invokes send() under
  writeLock and is called from ScanRulerEmbeddedSession.kt:385 on the WebSocket readLoop
  thread (ScanRulerPageLink.kt:333).
- Trigger A (certain, no token): open one TCP connection to 9879 and idle. Every later
  client — token or not — is answered -32010 "another external agent is already connected",
  a lie, until the process dies. Trigger B: a client that fills its socket send buffer and
  stops reading blocks send() while holding writeLock; notifyToolsChanged then blocks the
  WebSocket readLoop thread (runCatching cannot catch a blocked thread), wedging the in-app
  lane and MCP together until restart.
- Fix: authenticate before claiming the slot (or time out unexpired slots); set read and
  write timeouts; emit notifications off the page-link thread.

### Medium

**SR-04 — start() cleanup closes fields assigned after the failing call: server leak per failed
start**
- Where: ScanRulerEmbeddedSession.kt:103-136 starts asset server (:109), page link (:118) and
  MCP server (:133) as locals, but assigns the fields only at :134-136; the catch (:157-162)
  closes those fields, which are still null. The created WebView is also not destroyed there.
- Trigger: any bind failure on 9880 or 9879 (fixed binds with no fallback — ScanRulerPageLink
  .kt:86, ScanRulerMcpServer.kt:77), e.g. a co-installed app squatting the loopback port.
- Failure: the already-started servers keep their sockets and accept threads for the process
  lifetime; each Retry (retryScanRulerView creates a fresh session) repeats the leak and then
  cannot bind the squatted port either — cascading failure with a growing leak.
- Fix: close the locals in the catch (or assign each field as it starts and close all in the
  catch), and destroy the WebView there too.

**SR-05 — Frame-layer memory amplification before any authentication**
- Where: ScanRulerPageLink.kt:51 MAX_PAYLOAD = 512 MiB; :252-254 allocate ByteArray(length)
  from a 12-byte frame header; :278-293 fragment reassembly into a bare ByteArrayOutputStream
  with no cap at all. readLoop runs before the token check (:319).
- Trigger: any local app connects to 9880 (no token needed) and claims a 512 MiB payload, or
  streams continuation frames forever.
- Failure: heap exhaustion; Android's low-memory killer takes the app. Compounds with the
  unbounded accept loop.
- Fix: cap frames and total message size to something a phone can hold, and reject before
  allocating.

**SR-06 — The hello timeout is gated on the global page state, not on the connection**
- Where: ScanRulerPageLink.kt:425-428 — the timeout thread refuses only when page === null.
- Trigger: the workspace is up and the real page is paired; any local app opens connections
  to 9880 and sends nothing (no SO_TIMEOUT is ever set).
- Failure: each connection keeps a thread and a socket fd forever (the 4008 refusal the docs
  promise is not enforced in the paired state). Secondary 15-second-boundary race: the
  timeout can refuse a connection that just paired (welcome, then close 4008).
- Fix: refuse when this connection has not paired (page !== pageConnection), and set a socket
  read timeout.

**SR-07 — The single-page rule is an unsynchronized check-then-act**
- Where: ScanRulerPageLink.kt:308-337 — two connections sending hello in the same window both
  see page === null and both execute page = this.
- Trigger: the page reload race (old connection EOF not yet processed), or a hostile hello
  with the stolen token (SR-01) racing the real page.
- Failure: the losing "paired" connection has all its answers silently dropped (:338), so
  run() waits its full timeout (up to 10 minutes) although the page executed the command;
  pageHello/commands are clobbered.
- Fix: serialize pairing under one lock.

**SR-08 — A paired connection has no liveness check; whoever holds the slot holds it forever**
- Where: ScanRulerPageLink.kt:238 (readLoop blocks in input.read() with no SO_TIMEOUT); the
  server never sends pings (:271-277 only answers them); the hello timeout covers only
  pre-hello connections.
- Trigger: with SR-01's stolen token, a squatter pairs before or instead of the WebView page.
- Failure: the real page is refused 4009 on every retry forever (the embedded link is dead),
  and every run() dispatch goes to the squatter, whose invented results are returned as the
  page's answers — silently substituted scan and measurement data.
- Fix: ping/pong liveness with a deadline, and an idle timeout on the paired connection.

**SR-09 — The in-app agent can never run a page command whose name contains an underscore**
- Where: ScanRulerCommandCatalog.kt:24-27 — toolNameFor replaces dots with underscores;
  commandNameFor maps every underscore back to a dot. The inverse is lossy. ScanRulerAgentHost
  .kt:28-40 then refuses because the mangled name is not in session.commandNames().
- Trigger: the shipped bundle registers commands with underscore segments (e.g.
  body.compare_in_deviation, scan.tube_trace, network.suggest_creases — verified in
  assets/scanruler). The in-app agent is offered body_compare_in_deviation and every call is
  refused with "The ScanRuler page does not offer ... right now". The external MCP lane
  resolves by catalogue lookup and works.
- Failure: a whole class of page commands is permanently unusable from the in-app agent,
  with a misleading error, contradicting ScanRulerAgentHost.kt's own equivalence contract and
  SCANRULER_EMBEDDED.md. ScanRulerCommandCatalogTest.dotsBecomeUnderscoresForTools pins only
  pure-dotted names and misses this.
- Fix: resolve tool names through the catalogue map (toolName to Command), never by string
  surgery.

**SR-10 — Retry / session auto-replace leaves the in-app agent bound to the closed session**
- Where: EnderSlicerApp.kt:1070-1094 caches inAppCadAgent with toolHost bound to the session
  instance (ScanRulerAgentHost(this)); the only clear is setPlateView (MainViewModel.kt:3548).
  retryScanRulerView (MainViewModel.kt:3579-3587) and the FAILED auto-replace (:3606-3610)
  create a new session without clearing it. After close(), commandNames() returns an empty
  list (ScanRulerEmbeddedSession.kt:278).
- Trigger: one in-app turn (caches the agent) -> any named failure -> press Retry -> ask the
  agent again.
- Failure: every engine tool is refused with "does not offer X right now" while the turn's
  fresh catalogue (rebuilt from the new session) lists it; recovery only by switching
  Plate/ScanRuler, which is undiscoverable.
- Fix: clear inAppCadAgent wherever a session is closed or replaced.

### Low

| id | finding | where |
| --- | --- | --- |
| SR-11 | RFC 6455 gaps: unmasked client frames accepted, ping payload unbounded and echoed as an invalid oversized pong, RSV bits ignored, unknown opcode ends the loop with no close frame | ScanRulerPageLink.kt:242-294 |
| SR-12 | Fragmentation violations: interleaved data frames silently concatenate (and org.json's trailing-content tolerance silently drops the second message), a start-less continuation is delivered as a complete text message | ScanRulerPageLink.kt:278-293 |
| SR-13 | A run() timeout releases runLock while the page is still executing the command; the next dispatch overlaps it and the late reply is dropped, breaking the documented one-at-a-time rule | ScanRulerPageLink.kt:141-147 |
| SR-14 | 9880 and 9879 are fixed binds with no fallback, unlike the asset server's documented ephemeral fallback: a squatter bricks the engine start | ScanRulerPageLink.kt:84-87, ScanRulerMcpServer.kt:77 |
| SR-15 | Asset server spawns an unbounded thread per connection; soTimeout is per-read only, so a trickled request (one byte per 15 s) holds a thread forever | ScanRulerAssetServer.kt:78-80, :94 |
| SR-16 | showEmbeddedWorkspace sets embeddedWorkspaceInitialized even when workspace.set failed; the default notes workspace is then never selected again, silently | ScanRulerEmbeddedSession.kt:197-200 |
| SR-17 | start()'s catch (error: Throwable) swallows CancellationException: a cancelled start is reported as FAILED instead of propagating cancellation | ScanRulerEmbeddedSession.kt:148 |
| SR-18 | The agent chip (no maxLines) covers the notes panel's bottom rows for long model names, breaking the 1.16.1 clearance (brick mode 72px assumes a one-line chip; 600-750px width overlaps the panel corner) | ui/ScanRulerWorkspace.kt:207-223, ScanRulerChromeAdapter.kt:336-348 |
| SR-19 | The endpoint hint "Set the endpoint in the ScanRuler agent settings." names a surface that does not exist (the editor is inside the CAD agent sheet) | EnderSlicerApp.kt:3681 |
| SR-20 | session.setVisible(true) after awaitReady is unconditional and can reverse a deliberate hide when the user switched away during preparation | MainViewModel.kt:3621 |
| SR-21 | JSON-RPC error semantics: malformed base64 yields -32603 instead of -32602, a non-object params value is silently ignored, and an unauthorized notification (no id) is answered with id null | ScanRulerMcpServer.kt:161-172, :189-191, :247 |
| SR-22 | scanruler_wait does not do what its description promises (waits only for pairing, never for a running command) and the in-app lane ignores its advertised timeoutMs (fixed 20 s) | ScanRulerMcpServer.kt:206-210, ScanRulerAgentHost.kt:26, ScanRulerEmbeddedSession.kt:289-298 |
| SR-23 | File exports report the requested name, not the one MediaStore assigned on collision, and the extension-derived MIME map is unreachable because the MCP path pre-defaults application/octet-stream | ScanRulerFileExports.kt:30, :50; ScanRulerMcpServer.kt:265 |
| SR-24 | HTTP request-line quirks: absolute-form targets 404, malformed percent-encoding closes the connection with no response instead of 400, and a literal plus decodes to space in paths | ScanRulerAssetServer.kt:99-115 |
| SR-25 | Token comparison is not constant time (consistent with MCP_TOKEN_SECURITY.md section 7, which documents plain comparison; recorded, not a contract violation) | ScanRulerMcpServer.kt:302 |
| SR-26 | Trivia: REFUSED_ORIGIN is defined but never emitted; close(reason) caps at 123 UTF-16 chars, not bytes; Pending.fail vs receive has a teardown micro-race | ScanRulerPageLink.kt:53, :383, :188-193 |

### Latent / unconfirmed (not counted as confirmed bugs)

- The chrome adapter hides .modal but not its .modalback scrim; a dialog that opens leaves an
  invisible scrim that swallows taps (its blind backdrop click can disable the page bridge).
  All direct triggers are in hidden chrome; only a failed localStorage write reaches it.
  (ScanRulerChromeAdapter.kt:153-158.)
- close() may call WebView.destroy() while the composed AndroidView still hosts the view.
  (ScanRulerEmbeddedSession.kt:300-322.)
- A reused ScanRulerMcpServer instance could have an old serve() thread wipe the new
  connection's notifier (current wiring creates one instance per session).
  (ScanRulerMcpServer.kt:135-136.)
- FileExports ignores the IS_PENDING=0 update result (a failed update leaves the row
  invisible); run() leaks pending[id] if latch.await is interrupted (production never
  interrupts); two legal page names can collide after tool mapping (a.b_c vs a_b.c) — none
  in the shipped catalogue; rewriteBinaryValues is dead code.

### Verified safe in this area (coverage note)

Path traversal in the asset server is genuinely confined (percent-decode before the check;
dot-dot and empty segments rejected); no response-header injection; no request smuggling
(one request per connection); the MCP token gate fails closed including a null token; the
frames codec mirrors the vendored page exactly; the WebSocket handshake and SHA-1 accept are
correct; runLock covers every production caller; the chrome adapter's CSS matches the vendored
DOM (notes group kept, editor stacking correct, brick-mode math matches the 1.16.1 contract);
AndroidView hosting cannot re-create the WebView and leaks no Activity; the READY state
machine is correct in both callback orderings; export naming is otherwise sound.


## Area 2 — App shell and wiring (ui/EnderSlicerApp.kt, ui/IntegratedEnderSlicerApp.kt)

Scope: the shell pass of the full-app survey. Findings traced by the area auditor; the three
medium entries independently spot-verified against the source by the lead. Known findings
SR-10 and SR-19 (already in Area 1) are not repeated. No entry is pinned by a test.

### Medium

**APP-01 — The modelling standing brief is silently dropped on any composition recreation
(rotation or process death)**
- Where: EnderSlicerApp.kt:408 "modellingBootstrapped by rememberSaveable" versus :412
  "modellingIntroPending by remember". Owed at :1539-1542, delivered at :1698-1701.
- Trigger: first visit to Blender > Model from scratch, then rotate (or let Android kill the
  process) before the asynchronous harness connect finishes - the connect authenticates and
  adopts sessions over the network, so the window is seconds long.
- Failure: the new composition restores bootstrapped=true but pending=false, and nothing ever
  re-owes the brief. The modelling agent permanently never receives the standing brief: no
  camera.json contract (the code comment at :776-781 says an unordered render makes agent and
  user "drift apart inside one turn"), no render contract, no export hot-load contract, and
  the first-visit default-cube step never runs. Silent: no status, no retry.
- Fix: make the pending flag rememberSaveable (or persist a brief-sent marker and derive).

**APP-02 — The sidebar picker screen has no BackHandler: system back leaves the app from a
nested screen**
- Where: EnderSlicerApp.kt:2475-2487 draws SidebarPickerScreen with an explicit back button,
  but the back block at :1704-1745 (sixteen handlers) has none for sidebarPickerOpen.
- Trigger: Plate > the rail add button (:1983) or Settings > "Add settings to the sidebar"
  (:3263), then system back with no overlay open.
- Failure: the activity default runs - the app exits instead of returning to the tab. The
  file's own contract at :1704-1706 says "back closes any open layer instead of exiting".
- Fix: BackHandler(enabled = sidebarPickerOpen) beside the others.

**APP-03 — Tap-owning tool flags survive a tab change while their top-level BackHandlers stay
enabled: the first back press on the other tab is swallowed and the tool resurrects on return**
- Where: EnderSlicerApp.kt:1732-1745 (BackHandler for supportPaintUiOpen, faceSelectMode,
  cutActive, snapActive - all composed at top level, enabled on every tab) versus the
  tab-change effect :1757-1767 which clears only modelToolsOpen, modelDragMove and the gizmo.
  The comment at :1754-1756 names this exact failure class.
- Trigger: Plate > Model tools > Support paint (or Split, Snap fit, face select), then switch
  to Settings/Print/More. supportPaintUiOpen additionally survives a viewer-mode switch (the
  overlay's DisposableEffect at :7849-7851 clears only the paint mode).
- Failure: on the other tab a stale handler consumes the first back (doing nothing visible),
  the second back exits the app; returning to the Plate resurrects the tool UI. The
  ViewModel-held flags (cut/snap/faceSelect) survive even process death.
- Fix: clear the four flags in the existing LaunchedEffect(selectedTab), or scope the
  handlers to the Plate branch.

### Low

| id | finding | where |
| --- | --- | --- |
| APP-04 | Files-screen vs OCCT flag conflict: the content branch (cadOpen and not cadFilesOpen and not blenderFilesOpen) and the BackHandler guard (:1729, missing "not blenderFilesOpen") disagree, so "Model with OCCT" from a files screen starts the engine invisibly (566 MB extraction plus a polling viewport) and back then closes screens in an order the user cannot see; same gap over solidCraftFilesOpen | EnderSlicerApp.kt:1729, :1998, :2554 |
| APP-05 | Model tools can be re-opened over a plate-tap-owning tool (support paint, annotation, split, snap): two panels stack in the same bottom slot with both modes armed, so one finger-drag is claimed by two tools. The reverse direction is guarded; this direction is not | EnderSlicerApp.kt:1963 vs :2775, :3070, :3084, :953 |
| APP-06 | Token-copy success toast says "MCP token copied" without naming the engine, contradicting MCP_TOKEN_SECURITY.md section 6 ("the successful toast names the engine"); the connect-line copy does name the row | EnderSlicerApp.kt:4190 vs :4217 |

### Verified safe in this area (coverage note)

Biometric gating of every credential copy path (read only after authentication, refusal before
the prompt, nothing copied on cancel, clip marked sensitive); transcript copy strips data
URLs as AGENTS.md requires; the transform-card actions reuse the view-model calls with named
disabled reasons; face-select exclusivity; the CadSliceGate wiring (greyed-but-tappable Slice,
exactly the pinned convert call sites); in-app turn lifecycle and wake-lock exit paths;
harness rotation recovery and reconnect guards; the menu one-job structure; the engine-prep
dependency invariant (CAD always brings the Python); the Smart Infill handoff transaction
(newest-run guard, NonCancellable commit, URI cleanup); ModelSurfaceView hosting and
orientation restore; back-priority ordering otherwise as intended.


## Area 3 — Engine core and G-code (engine/**, engine/gcode/**)

Findings traced by the area auditor; every high/medium entry spot-verified against the source
by the lead. The repo AGENTS.md G-code rules were the contract.

### High

**ENG-01 — Klipper PAUSE/FILAMENT_CHANGE layer events and the probe-pause safety step can
never be published: the sanitizer's fail-closed text policy rejects the app's own PAUSE**
- Where: engine/gcode/KlipperGcodeRoute.kt:22 pauseCommand() returns "PAUSE" (emitted at :37
  for PAUSE and :41 for FILAMENT_CHANGE); engine/GcodeCommandPolicy.kt:144-181
  requirePublishedTextSafe with the Klipper textual allowlist at :488-517 (mesh, velocity,
  exclude-object, pressure-advance, retraction) containing no PAUSE/RESUME;
  engine/GcodeSanitizer.kt:118-133 parses each line (GcodeCommand.parse returns null for
  bare "PAUSE" - the opcode regex wants letter+digit) and then fails closed.
- Trigger: (a) Klipper flavor + Cura engine + a PAUSE or FILAMENT_CHANGE layer event
  (CuraEngineRunner.applyLayerEvents:542-554, CuraEnginePostProcessor.process:148-159);
  (b) non-planar or conical slicing with pause-after-probe on a Klipper printer
  (CuraEnginePostProcessor.kt:68-76 injects the pause, :113 sanitizes it).
- Failure: every slice or event application in these configurations dies with a misleading
  "Unsupported textual or malformed command ... PAUSE". The pause-at-layer, filament-change
  and probe-pause features provably never reach a Klipper printer. Fails closed, but the
  feature is unbuildable and the error blames the command rather than the event.
- Tests: GcodeLayerEventProcessorTest.aPauseForAKlipperProfileIsKlippersOwnPause exercises
  only materialize while claiming the whole path; GeneralAuditSafetyRegressionTest pins the
  allowed Klipper set, which excludes PAUSE. The two claims contradict at the production path.
- Fix: recognize the route's own pause/resume commands in requirePublishedTextSafe.

### Medium

**ENG-02 — Released artifacts can still be leased: the documented "lease acquisition fails for
released artifacts" contract is not implemented**
- Where: engine/SliceArtifactPublisher.kt:164-171 isCompleteGcode checks the file, the
  completion marker and the envelope but never reads the .released marker that release()
  writes at :93-104; acquireLease (:185-188) gates only on isCompleteGcode.
  docs/non-planar.md:80 states the missing contract verbatim.
- Trigger: an export or upload holds a lease; a non-planar/conical option change invalidates
  the artifact (release() writes .released because the lease is active); a second export or
  upload then passes both checks and ships the invalidated G-code. End-to-end reachability of
  the second export may be softened by UI gating (cross-check flagged for the UI areas).
- Fix: refuse a directory carrying .released in isCompleteGcode or acquireLease.

### Low

| id | finding | where |
| --- | --- | --- |
| ENG-03 | The fold invariant is XY-only: a folded run may deviate in Z beyond FOLD_DRIFT_MM (conical/non-planar output, spiral/vase mode; a pure-Z move skips the direction test and folds into a travel run) - preview geometry silently off the source path, file itself correct. The invariant test measures drift in x/y only | GcodeNozzlePath.kt:193-209, PrusaNozzlePath.kt:144-157 |
| ENG-04 | Klipper retraction layer events validate speed 0.1..1000 mm/s but the policy's SET_RETRACTION bound is 500: values 501..1000 pass validation, then fail the whole event application with a misleading text-policy error | GcodeLayerEventProcessor.kt:196 vs GcodeCommandPolicy.kt:513-523 |
| ENG-05 (UNCONFIRMED) | The Prusa machine-limit strip regex requires trailing whitespace, so a bare "M205" line is not dropped as the comment claims; no emitter of the bare form was traced | GcodeSanitizer.kt:358 |

### Verified safe in this area (coverage note)

Quoted-label handling and the full M486 parameter set per AGENTS.md; the fold/no-drop cores of
all three readers (identical criteria in count and emit passes, flush at layer end and EOF,
pinned by the fold/decimation/layer-coverage tests); Orca Bambu/non-Bambu dialect markers
accepted everywhere; layer-event injection gated to Cura with a correct machine-end restore
boundary; sanitizer envelope checks on every spatial move, cold-extrusion check, modal
handling, atomic validated replace, saturating time estimates; the fail-closed command policy
(arcs, framed lines, G27/G28/G29 placement, unmodeled M-codes) pinned by the safety regression
tests; PrinterEnvelope checks; SliceArtifactPublisher atomic publish/retention/lease-close;
child-process reaping; EnginePreparation obeys the borrowed-payload rule; config writers
escape values and write atomically; SequentialPrintCheck; argv-only engine invocation.

## Area 4 — Klipper printer screens (ui/klipper/**)

Findings traced by the area auditor; high/medium spot-verified by the lead. The contract was
KLIPPER_UI.md: "Motion is refused while printing rather than merely discouraged."

### High

**KUI-01 — The Mesh tab offers homing and full-bed probing while a print runs**
- Where: ui/klipper/KlipperMeshTab.kt:115 ("Home first", enabled = state.isReady) and :118-120
  ("Probe the bed", enabled = state.isReady and state.isHomed). KlipperMoveTab.kt:54 shows the
  correct pattern (motionAllowed = ready and not isPrinting); this tab omits it, and
  KlipperPrinterRepository.send/command carry no print-state guard.
- Trigger: start a print, open the Mesh tab (isReady and isHomed are both true while
  printing), press "Probe the bed" and confirm.
- Failure: BED_MESH_CALIBRATE descends the probe/nozzle to every grid point at bed height over
  the part; "Home first" sends G28 mid-print. The print is destroyed and the machine can
  crash into the part, and the file keeps streaming from a disturbed position. The doc's
  refusal contract is provably absent on this screen.

**KUI-02 — The Z probe tab offers G28 and PROBE_CALIBRATE while a print runs**
- Where: ui/klipper/KlipperZProbeTab.kt:193 ("Home", enabled = state.isReady) and :194-198
  ("Start calibrating", enabled = state.isReady and state.isHomed). Same contract as KUI-01.
- Trigger: printing, open Z probe, "Start calibrating" -> PROBE_CALIBRATE
  (KlipperPrinterRepository.kt:1193).
- Failure: the probe works over the part and klippy parks in manual-probe state (TESTZ/ACCEPT)
  while the virtual-sd file keeps printing; the two move streams interleave - print destroyed
  and an offset calibrated against a moving, occupied bed.

### Medium

**KUI-03 — The KAMP settings fields send raw, unvalidated text into SET_GCODE_VARIABLE;
comma decimals silently corrupt the variable**
- Where: ui/klipper/KlipperKampTab.kt:205-217 - free text interpolated verbatim
  ("SET_GCODE_VARIABLE MACRO=... VARIABLE=... VALUE=<text>") where every other numeric field
  on these screens filters or parses. klippy stores ast.literal_eval(VALUE), so "0,5" becomes
  the tuple (0, 5), which KAMP's float casts silently degrade to 0.0.
- Trigger: type a comma decimal (which the app caters to elsewhere for Finnish locale input)
  in any of the six KAMP fields and press the send button.
- Failure: the margin/purge values the user set silently vanish; the next adaptive mesh or
  purge runs with wrong values at print start. Quoted strings and infinities are likewise
  accepted verbatim. Silent wrong behavior of a print-start feature.

**KUI-04 — The dashboard Limits card describes the last-sliced file, not the print on screen
(and a file that may not exist)**
- Where: ui/klipper/KlipperDashboardTab.kt:618-625 builds the card from localGcodePath (the
  slicer's last G-code) while the Job card above shows state.printFileName.
- Trigger: slice model A, print older file B from the Files tab (or a new slice completes
  mid-print).
- Failure: the dashboard - "the numbers a print is diagnosed with" - shows A's M204/limit
  lines under "This file, and this printer" while B prints; with nothing sliced the card
  still renders and claims "none set in the file" for a file that does not exist.

### Low

| id | finding | where |
| --- | --- | --- |
| KUI-05 | The ignored-Marlin note misdescribes M900: it is pressure advance, not a limit, so being ignored means the printer keeps its configured pressure advance - not "moves at the printer's own limits instead" | KlipperDashboardTab.kt:704-710, printer/KlipperFileLimits.kt:66 |
| KUI-06 | The per-tab "Stop" is an emergency stop (halts the MCU, needs a firmware restart) while the dashboard's identically-labelled "Stop" cancels the print; only the dialog title distinguishes them, and the app's own Danger card says the emergency stop is not for ending a print | KlipperScreen.kt:94,132-135 vs KlipperDashboardTab.kt:359 |
| KUI-07 | PID tuning runs over a live print despite the card's own precondition ("must be at room temperature and not printing"): no print or temperature gate on the path, and the resulting values are offered for SAVE_CONFIG | KlipperTemperaturesTab.kt:202,330-334 |

### Verified safe in this area (coverage note)

Dashboard speed rows and units (mm/min vs mm/s per KLIPPER_UI.md); the limits card's M204
handling and named M201/M203/M205 ignores; job card gating and "why it stopped"; the
temperature log window and heater rows; console behavior; Z-probe TESTZ/ACCEPT semantics
verified against the vendored manual_probe.py; shaping floors, damping cap and mass
correction; motion refusal on the Move and Extrude tabs where implemented; files-tab print
refusal while printing/paused; mesh picture geometry; macro/history/machine wording; the
deliberate exceptions (macros stay available; motion allowed while paused, as the doc
sanctions).

## Area 5 — Printer core (printer/**)

Findings traced by the area auditor; high/medium spot-verified by the lead.

### High

**PRN-01 — A configuration save on the PC route restarts a host that is printing, killing the
print; the sync path carries exactly the guard this path lacks**
- Where: printer/KlipperPrinterRepository.kt:1576 - writeHostConfig's remote branch runs
  "if (written) remote.restart()" with no print-state check, versus :1713-1718 where
  writeRemoteConfig refuses when printState() is printing or paused ("syncing would restart it
  and end the print").
- Trigger: PC Klipper route with a print running (started from the phone or from the PC);
  the user saves pressure advance (the repo's own comment says it is tuned during a print),
  rotation distance, shapers, mesh probe count or starter macros.
- Failure: Moonraker restarts klippy mid-print; hours of print are lost, silently, from a
  settings save. HostConfigSeamTest pins the restart but no print guard on this path; the
  refusal exists only on the sync path. Device route is unaffected (no restart there).

### Medium

**PRN-02 — Print start resets the machine's live state before knowing the print will start, so
a refused or raced start silently rewrites a running print's babystepping and speed/flow**
- Where: printer/KlipperPrinterRepository.kt:1346 and :1426 send
  KlipperScripts.resetLiveOverrides() ("SET_GCODE_OFFSET Z=0 / M220 S100 / M221 S100")
  BEFORE SDCARD_PRINT_FILE, unconditionally.
- Trigger: double-tap "Print this file" (the Dashboard's Job card gate is weaker than the
  Files tab's), or any start that is refused because a print began elsewhere.
- Failure: the second tap's reset lands after the first print started and its command is then
  refused with "SD busy": the running print loses its babystep Z offset and its speed/flow
  factors mid-print - exactly the state the code comment says must only be cleared before a
  print. No repository-level guard; the two tabs gate differently.

### Low

| id | finding | where |
| --- | --- | --- |
| PRN-03 | The file filter is case-insensitive but klippy's is not: a *.GCODE file is listed and offered for printing, then SDCARD_PRINT_FILE fails "Unable to open file" (klippy's virtual_sdcard extension list is lowercase-only). Phone-route reachability UNCONFIRMED (the app always names lowercase) | KlipperPrint.kt:21, KlipperGcodeFiles.kt:52 vs vendored virtual_sdcard.py |
| PRN-04 | The designed "connection dropped mid-start" recovery is unreachable in its own intended case: sendScript throws "not connected" before the reconnect branch it was written for | KlipperPrinterRepository.kt:1346-1358 |
| PRN-05 | MoonrakerTransport's reply deque (ArrayDeque) is touched by caller threads, the reader thread and close() with no synchronization - a genuine data race in the printer-driving core | MoonrakerTransport.kt:45,92,115,206 |
| PRN-06 | Print-history bookkeeping is shared, unsynchronized state (plain vars across three threads; read-modify-write file append): duplicate or lost records, or a torn history file silently discarded whole | KlipperPrinterRepository.kt:186-188,526-556; KlipperGcodeFiles.kt:257-275 |
| PRN-07 (trigger UNCONFIRMED) | PrinterBridge.start() leaks a claimed USB connection on its own failure path: open/setParameters run before the fields are assigned, and the catch closes only those fields - later starts cannot claim the interface until process restart | PrinterBridge.kt:98-125 |

### Verified safe in this area (coverage note)

KlipperProtocol ETX framing and update shapes (pinned against real klippy); KlipperClient
request/reply discipline and caps; the pty and CH34x USB handling; KlipperConfigFile's saved
block semantics (byte-identical to klippy's autosave header; body-beats-autosave inverted
correctly so calibrations survive restore/import) and the device/remote field rules from
KLIPPER_UI.md; the stale-refresh bug stays dead (nothing rewrites a seeded config wholesale);
route separation verified against verify-route-separation.sh; copyForPrinting atomicity and
path confinement; script quoting and locale-safe numbers; connection-generation guards;
printer state/object merging; speeds units; console/temperature-log locking; sealed API-key
handling; Moonraker dialect mapping; WebSocket caps; upload streaming.

## Area 6 — OctoPrint client and harness (octoprint/**, harness/**, ui/HardenedOctoPrintSheet.kt)

Findings traced by the area auditor; all five spot-verified by the lead. OCT-01's final
server-side hop is marked UNCONFIRMED (see entry); the rest is in-repo.

### High

**OCT-01 — An optimistic move update creates duplicate LazyColumn keys and crashes the app**
- Where: octoprint/OctoPrintRepository.kt:977-989 filesWithMoved rewrites the moved entry's
  path with no collision check - while filesWithCopied directly below (:993-999) DOES guard
  ("if (files.any { it.path == newPath }) return files"). The Files list uses the path as its
  LazyColumn key (HardenedOctoPrintSheet.kt:760), and Compose throws unconditionally on a
  duplicate key.
- Trigger: the Files page lists a/cube.gcode and b/cube.gcode; move a/cube.gcode into folder
  b (OctoPrint's shutil.move overwrites). The optimistic update then holds two entries with
  the same key until the follow-up refresh lands. The fully-confirmed variant needs no
  overwrite: flattenFiles never dedupes server-supplied paths, so a server that lists one
  path twice crashes the page identically (hostile server input is in scope per the existing
  unsafe-server-path tests).
- Failure: IllegalArgumentException on the main thread - the whole app dies.
- Fix: give filesWithMoved the collision guard its sibling has (or key the list by identity).

### Medium

**OCT-02 — Any HTTP 401 silently destroys the stored OctoPrint API key, bypassing the
strict-evidence rule the same code establishes for 403**
- Where: octoprint/OctoPrintRepository.kt:1036 - "if (http?.statusCode == 401 ||
  forbiddenIsInvalidKey)" calls invalidateAuthorization -> store.clearApiKey(). The 403 branch
  requires apiKeyRejected (a same-origin JSON error naming the key) with a comment explaining
  that a proxy or captive portal could otherwise word a response into deleting a valid key;
  the 401 branch gets none of that verification. OctoPrintClientTest asserts
  isApiKeyRejection(401) == false under the comment "Every one of these used to erase the
  stored key".
- Trigger: any polled endpoint answers 401 - a reverse proxy with its own auth in front of
  OctoPrint (explicitly supported by docs/octoprint-integration.md), or a DHCP-reassigned
  address - with zero user action after the config is saved.
- Failure: the encrypted key is erased and the user must re-approve the app for a key OctoPrint
  never rejected. The test suite claims this class of bug is fixed; only the 403 route is.

**OCT-03 — Deleting the parent folder bypasses the "file being printed cannot be deleted"
guard, and the confirmation misdescribes the action**
- Where: octoprint/OctoPrintRepository.kt:952-959 activeFileGuard compares exact paths only -
  a folder never equals the file inside it - while the dialog promises "OctoPrint can delete
  the folder only when it is empty" (HardenedOctoPrintSheet.kt:223-224). The project's own
  live smoke test records that folder deletion cleans up recursively.
- Trigger: printing prints/cube.gcode, delete the folder prints.
- Failure: OctoPrint recursively deletes the folder including the file being printed: the
  protection the UI implies is not enforced, and the user confirmed a destructive recursive
  delete under a dialog that describes a harmless one.

**OCT-04 — The harness store deletes the launch token and session-cookie ciphertext on any
decrypt failure, including transient Keystore errors**
- Where: harness/HarnessConfigStore.kt:266-269 - runCatching { decrypt(encoded) }.getOrElse
  removes both keys and returns empty. octoprint/OctoPrintSecretStore.kt:76-86 documents
  exactly this failure class ("a Keystore that was momentarily unavailable, a backup restored
  without the device-bound key") and keeps the ciphertext for it.
- Trigger: assistant connect while AndroidKeystore is momentarily unavailable.
- Failure: the only stored copy of the pasted launch credential and the 30-day cookie is
  erased (AI_ASSISTANT.md: "The launch token is pasted, never published"); recovery is the
  launcher log by hand or a harness restart that mints a new token. The two secret stores
  disagree on the same failure.

### Low

| id | finding | where |
| --- | --- | --- |
| OCT-05 | A bare hostname (which normalizeBaseUrl upgrades to HTTPS precisely to keep the key out of cleartext) shows the error-styled "HTTP is unencrypted" warning - wrong content that may push users to type http:// explicitly | HardenedOctoPrintSheet.kt:1102 vs OctoPrintClient.kt:896-899 |

### Verified safe in this area (coverage note)

CSRF double-submit handling in both request paths; credential redaction and same-origin
pinning (webcam, appkeys, redirects with SSRF blocklist); upload streaming, snapshot identity
and print-start separation with fresh preflight and command mutex; command gating while
printing; file-manager path normalization and hostile-nest caps; both secret stores'
Keystore/origin-binding design and backup exclusions; the harness protocol end to end
(slash endpoints, double-wrapped payloads, receipts, throughSeq projection semantics,
last-text-block-wins, per-turn prompt pairing, cleartext consent, response caps).

## Area 7 — Settings and profiles UI (SET)

Findings traced by the area auditor; SET-01, SET-02 and SET-04 spot-verified by the lead.

### High

**SET-01 — Extra-settings changes never invalidate published G-code: stale G-code stays
exportable and uploadable**
- Where: MainViewModel.kt:2893 setExtraSetting and :2942 removeExtraSetting are the only
  settings mutators that never call withoutPublishedSlice - verified: updateSettings (:2595),
  updatePrusaSettings (:3060), updateOrcaSettings (:3097), updatePrusaPreset (:3157) and the
  machine mutator (:3257) all invalidate with "Settings changed; slice again to export
  G-code". Reached only through the settings screens (AllSettingsSheet, the sidebar's
  catalogue editors).
- Trigger: slice, then add/edit/remove any of the hundreds of catalogue values (the sheet
  says "used by every slice"), then Export or upload.
- Failure: the exported G-code was sliced under the old extra settings, silently. Violates
  docs/profile-filament-management.md Safety. There is also no isBusy guard, so edits land
  mid-slice.

**SET-02 — Sidebar numeric editors write locale-formatted numbers; fractional values can never
be committed on comma-decimal phones**
- Where: SessionSidebar.kt:215 writes numberText(next, decimals); SessionValues.kt:520-521
  numberText formats with the device locale (comma on fi/de/fr); ExtraSettingSpec.kt:330
  parses with toDoubleOrNull (dot only) and answers "the value must be a number", a reason
  the wiring discards. Verified.
- Trigger: on a comma-decimal locale, drag any fractional sidebar value (e.g. 0.05) and
  release.
- Failure: the commit writes "0,05", is rejected, the slider snaps back, the setting never
  changes. Integer rows work. Violates the hard dot rule in printer/DecimalText.kt ("text the
  app puts in a field it will read again is written with a dot"; its comment says this trap
  has shipped twice) and contradicts DecimalTextTest.

### Medium

**SET-03 — The Orca sheet's Flow ratio, Retraction length and Retraction speed accept any
double: negatives and absurd values reach the engine unclamped**
- Where: OrcaSettingsSheet.kt:402-419 (and clampAllToEngine at :513-534 omits all three),
  while the same settings clamp in the Prusa and Cura sheets and the sheet's own comment says
  "an edit outside them is one the engine would refuse". OrcaConfigWriter writes them verbatim.
- Trigger: type -1 in Retraction length (or 500 in Flow ratio).
- Failure: slicing proceeds with negative retraction (filament pushed while travelling) or
  ~5x flow, or the engine refuses with a generic error; no range message anywhere.

**SET-04 — The Klipper/Marlin custom G-code editors seed the OTHER route's default script;
enabling the pair puts wrong-dialect G-code on the print**
- Where: MachineSettingsSheet.kt:209 and :225 pass defaultScript = state.startGcode to BOTH
  start editors (:241/:257 both end editors); ScriptEditor seeds the field on toggle from
  defaultScript. state.startGcode is the Frozen (Marlin) text on a fresh install, containing
  UBL G29 L0 / G29 A and M84 X Y E. Verified. The KAMP switch 50 lines below shows the
  intended per-route seed.
- Trigger: fresh install, toggle "Use custom Start G-code (Klipper)" on (field pre-fills with
  the Marlin UBL script), set flavor to Klipper, slice.
- Failure: klippy receives G29 L0 / G29 A - the double-probe incident the file's own comment
  describes - and M84 X Y E releases every stepper including Z. Mirror case sends
  BED_MESH_CALIBRATE to a Marlin printer: the exact incident documented in BuiltInGcode.kt:9-13.

**SET-05 — Prusa "Reset to engine defaults" is incomplete and its confirmation copy is false**
- Where: PrusaSettingsSheet.kt:44-84 resets 35 of 42 fields; the six extrusion-width fields
  and extraKeys survive; the preset selection and the imported snapshot are never touched,
  though the dialog promises "Every PrusaSlicer setting goes back to the engine's own default
  ... an imported snapshot is discarded" and EngineSnapshotCard's contract says the same.
- Trigger: import a Prusa .ini that sets extrusion widths, change a field, Reset, confirm.
- Failure: the slice still uses imported widths and extras; the header still names the old
  presets; the "discarded" snapshot can still be restored - after a confirmed no-undo reset.

### Low

| id | finding | where |
| --- | --- | --- |
| SET-06 | CalculatedField formats with the locale then trims on "." - comma locales render "3," instead of "3" (the exact pattern docs/ui-style-guide.md forbids) | CategorizedSettingsSheet.kt:1137 |
| SET-07 | Orca rows show Cura provenance badges ("Imported Cura value") and vice versa - the known deferred provenance item, now traced to its cause | SettingsFields.kt:130-134 |
| SET-08 | The adhesion summary reads "Skirt 0x" for adhesion None (brim 0, skirt 0) | PlateSessionSummary.kt:61-62 |
| SET-09 | docs/ui-style-guide.md's onboarding contract (two steps, done flag on step 2) is contradicted by the shipped three-step flow; the code is right and the doc stale | Onboarding.kt:66, MainActivity.kt |

### Verified safe in this area (coverage note)

The two-pane settings split; every Cura numeric field clamped in its own transform; per-engine
preset scoping with the switch-back refusal; profile management lifecycle (serialization,
cancellation honesty, delete semantics, dirty detection); the sidebar row sanitizing and
built-in id collision rules; blocked/managed key refusals; picker key safety; UiScale bounds;
onboarding writes only machine keys through the invalidating path; the AML two-key write;
port/host persistence; engine-store fallback.

## Area 8 — Tool UI (TOOL)

Findings traced by the area auditor; TOOL-01 spot-verified by the lead (including the
kotlin-stdlib parsing semantics).

### High

**TOOL-01 — The layer-events sheet admits NaN/Infinity input and the Add button crashes the
app**
- Where: ui/LayerEventsSheet.kt:187,191 gate on value.toNumber() != null, and toNumber is
  replace-comma-then-toDoubleOrNull - which accepts "NaN", "Infinity" and "1e400". The Add
  handler builds LayerEvent unguarded (MainViewModel.kt:7897-7915), and LayerEvent.kt:36
  does require(value.isFinite()) - an uncaught IllegalArgumentException straight out of the
  click handler. Verified.
- Trigger: Layers tab > Add event > Nozzle temperature > type NaN (or Infinity, or 1e400) >
  "Add to this layer".
- Failure: app death from ordinary UI input. Finite-but-wild values are safely refused later
  by the validator with a named error; only the non-finite spellings crash, and only because
  they die in the constructor first.

### Low

| id | finding | where |
| --- | --- | --- |
| TOOL-02 | The transform card's Split and Remove are dead buttons with no reason caption while busy - violating the AGENTS.md "a disabled action says why in a sentence" rule that Drop follows and TransformGizmoOverlayTest pins for Drop only | TransformGizmoOverlay.kt:222-245 |
| TOOL-03 | "Apply scale" silently discards out-of-range input (outside 1-1000%) from an enabled button - no message, no field error, bounds never shown | ModelToolsSheet.kt:584-587 |
| TOOL-04 | Disabled actions without reason captions: Snap fit joint while busy, Arrange now (single model), Add to this layer (invalid/blank input) | ModelToolsSheet.kt:364,761-775; LayerEventsSheet.kt:124 |
| TOOL-05 | The rotate summary keeps the previous object's tally after a selection change or Undo (unkeyed rememberSaveable beside keyed position/scale fields) | ModelToolsSheet.kt:406-408 |
| TOOL-06 | Layers and nozzle-path viewers lose the camera orbit on SurfaceView recreation; only the plate restores it, though the ui-style-guide bullet names all three modes and rotation | LayerPreviewView, NozzlePathView.kt:270-286 vs ui-style-guide.md:118-121 |
| TOOL-07 (UNCONFIRMED window) | Diagnostics.record is a non-atomic read-modify-write on a StateFlow: two threads recording can drop a log line | Diagnostics.kt:138 |

### Verified safe in this area (coverage note)

The layer-event rebuild path (named refusals, no half-committed state); event payload G-code
safety (message sanitizing, custom-G-code allowlist, per-value ranges); layer counts are
source counts per AGENTS.md; the preview views' zero-move and empty-preview paths are
unreachable and budgets follow PreviewMemoryPolicy; SnapPreview stale guarding; multi-object
spacing sanitizing; mesh-limit input; export naming; chat size/open-request state; theme
engine-accent rule; diagnostics buffer bounds; engine-setup borrowed-payload rules with
moving progress numbers and named failures; engine-files confirmations.

## Area 9 — Viewer GL core (VGL)

Findings traced by the area auditor; VGL-01 and VGL-02 spot-verified by the lead.

### High

**VGL-01 — Unhandled allocation refusal in the layer-view ribbon path: uncaught
OutOfMemoryError on a scrub where the contract requires a named refusal**
- Where: viewer/LayerPreviewGeometry.kt - rebuild() (:206-257) catches OutOfMemoryError,
  halves the plan via BandAllocator and ends in LayerWindow.refusal naming both numbers
  (exactly the AGENTS.md contract), but rebuildRibbons() (:355-367), called from select()
  (:97,:111) and growTo() (:163), allocates the selected layer's ribbon triple with no such
  catch. Verified: the OOM catches exist only around the rebuild/grow allocation paths.
- Trigger: build a current-layer window around a small layer, then scrub inside the window
  onto a layer with far more stored runs on a device where the driver refuses the allocation
  (the exact case BandAllocator exists for).
- Failure: the OutOfMemoryError escapes the GLSurfaceView event runnable - process death -
  instead of the documented footer refusal with both numbers. The asymmetry is the bug.

### Medium

**VGL-02 — The plate grid and XYZ triad ignore printer.originAtCenter: drawn in the wrong
quadrant on every centered-bed printer**
- Where: ModelSurfaceView.kt:3611-3630 buildGrid always spans (0,0)-(width,depth) and
  triadOrigin (:1654) is the front-left corner; verified that ModelSurfaceView never
  references originAtCenter. SceneCameraFit and ModelPlacement both handle centered beds
  (bed at plus/minus w/2, models centred at the origin).
- Trigger: import a profile whose bed crosses zero (Prusa bed_shape -115..115, Orca negative
  coords) and open the Plate.
- Failure: the grid covers a quadrant half off the bed and none under the model, and the
  triad sits at the bed centre under the part. Silent wrong rendering.

**VGL-03 — Cura travel runs are drawn as one chord per run, cutting corners up to about 49
degrees - "a straight line the printer never made"**
- Where: RibbonPathBuilders.kt:105-113 groups Cura travel moves with TURN_SPLIT_DOT = 0.65
  (about 49 degrees) and emits one travel per run (:200-219), while the parser folds at
  FOLD_COLLINEAR_DOT = 0.995 (about 5.7 degrees) and the Prusa builder draws one travel per
  move. AGENTS.md forbids folding across a corner.
- Trigger: any Cura file whose stored travel moves turn up to 49 degrees at a corner.
- Failure: the Path tab's travel overlay cuts corners across the part (two 20 mm legs at 45
  degrees draw a chord about 4 mm off); extrusion beads are unaffected (per-move windows).

### Low

| id | finding | where |
| --- | --- | --- |
| VGL-04 | releaseGpuBuffers omits the texture-region tint VBO: a scene-sized buffer stays pinned across backgrounding (the comment says exactly why the others are released) | ModelSurfaceView.kt:3065-3080 vs :3092-3098 |
| VGL-05 | The axis triad draws with depth testing ON, against the AGENTS.md overlay rule (annotation and gizmo comply); the code follows AxisTriad's own doc instead - the two documents conflict and one must change | ModelSurfaceView.kt:3548-3609 |
| VGL-06 (UNCONFIRMED windows) | The paint-probe executor outlives its shutdown (post-shutdown execute can throw RejectedExecutionException on teardown) and a stale brushProbePending flag can silently swallow the next tap | ModelSurfaceView.kt:1368-1371, :343, :715, :933 |

### Verified safe in this area (coverage note)

GL context recreation resets every object id and upload marker; offscreen FBO lifecycle;
RibbonChunkStaging bounded chunks with the window-i-at-vertex-i*18 contract; the buffer-sizing
rule (countKind, never extrusionMoveCount, pinned by NozzlePathMemoryTest); RibbonBand window
semantics; the documented "remembered smaller window" bug is NOT present (re-planned per
selection, pinned); LayerWindow plan/refusal semantics and buffer bookkeeping;
PaintColorBuffer slices/dirty ranges/overlay precedence; SelectionTintBuffer separation;
touch arbitration and screen-distance gizmo hit testing (GizmoOverlayOrderTest); gizmo drag
math; camera fit math; grid builder bounds; wireframe buffer; plate capture; density/speed
ramps; PreviewMemoryPolicy/BandAllocator device-derived capacities with no sampling.

## Area 10 — Engine lifecycle, modelling, ScanRuler 1.14, storage (MISC)

Findings traced by the area auditor; MISC-01 and MISC-04 spot-verified by the lead.

### High

**MISC-01 — The 1.14 ScanRuler export bridge is wedged permanently by one refused second
export: the in-flight export is destroyed and every later export is refused**
- Where: scanruler/ScanRulerActivity.kt:463-468 routes a refused transfer.begin through the
  shared complete(), which at :566-572 deletes the CURRENT destination (the in-flight
  export's SAF document) and broadcasts the failure; ScanRulerBridgeScript.kt:105-106 clears
  the page's single pending buffer on that broadcast; ScanRulerTransfer.kt:55-57 refuses
  later begins forever in WAITING/WRITING. Verified.
- Trigger: double-tap an export link (each tap's async blob read reaches beginDownload twice),
  or start a second export while a multi-megabyte file streams.
- Failure: the first export's document is deleted mid-write, its chunk loop stops, the page
  is told the export failed, and every later export answers "Another export is already being
  written." Recovery requires leaving the screen and losing the in-page scan session. A plain
  double-tap provably breaks the feature for the session.

### Medium

**MISC-02 — The export watchers keep the stale revision and drop the fresh one; the plate
shows an older generation as the latest**
- Where: nativebridge/BlenderEngine.kt:446-457 and CadEngine.kt:716-722 claim the new
  revision BEFORE the queue check, so a second revision of the same path is claimed and then
  dropped because that path is already queued; the setter's "replay the newest" then replays
  the older file. The null setter at UI detach claims-and-drops a queued export too.
- Trigger: the documented iteration loop re-exports the same STL while no UI listener is
  attached (both engines deliberately outlive the UI).
- Failure: the plate imports the older mesh as current, permanently (the fresh one is claimed
  and never re-offered). Contradicts the setter's own "newest one" contract.

**MISC-03 — EnginePreviewClient re-sends a command the engine may already have executed;
"All to plate" then imports every object twice**
- Where: modelling/EnginePreviewClient.kt:105-115 retries after ANY Throwable including
  timeout, where its twin CadPreviewClient deliberately does not ("a caller must not apply
  the same motion twice").
- Trigger: a timed-out "All to plate" export while the engine is still executing.
- Failure: the engine exports twice, each publish has a new mtime, the watcher imports a
  second copy of every object: 2N parts on the plate for one press, violating "import exactly
  once".

**MISC-04 — The ScanRuler 9879 bearer token rides into device-to-device transfers**
- Where: app/src/main/res/xml/data_extraction_rules.xml - the cloud-backup section excludes
  scanruler/ (line 89, with the comment calling it "a second bearer secret for the same app
  uid") but the device-transfer section has no such exclude. Verified (single hit at :89).
- Trigger: an Android 12+ device-to-device transfer.
- Failure: files/scanruler/scanruler_mcp_token.txt lands on the replacement device and in the
  transfer archive - what the same file's own rationale forbids for the other three tokens.
  Anyone reading the archive can drive the ScanRuler MCP surface as the app uid.

**MISC-05 — BlenderEngineService's START_STICKY restart advertises a running engine that no
longer exists and pins the process plus a wake lock for it**
- Where: nativebridge/BlenderEngineService.kt:46-62,135-145; EnderSlicerApplication.kt:19-25
  says the engine is not started at launch any more.
- Trigger: the app process dies while the keeper runs (crash or lmkd kill).
- Failure: the recreated service posts "Blender engine running / Generation socket active"
  and arms a 10-minute wake lock while nothing serves port 9876; an external agent following
  the runbook retries a dead socket. False state plus a resource pin until a UI path happens
  to start the engine.

### Low

| id | finding | where |
| --- | --- | --- |
| MISC-06 | REGION annotation chains are stored open (closed=false), so the exported length_mm omits the closing edge and the document declares a region unclosed; undo can also re-type a chain | annotation/AnnotationState.kt:330-335 |

### Verified safe in this area (coverage note)

KlipperPayload single-extractor/single-stamp with completeness checks and the borrower path
(CAD and mesh2step both extract what they borrow, soname-link ordering handled);
KlipperEngineService launch/restart/wake-lock/pty lifecycle; the preview clients' framing,
caps and path escaping; SealedValue and the credential stores (cloud-backup token exclusions
hold everywhere except MISC-04); mesh2step engine/runner/protocol; placement maths,
PlateArranger, PlanarPatchSelector; supportpaint invariants; ScanRulerTransfer byte
accounting and the 1.14 WebView lockdown; script/build pinning (digest-checked fetch/stage/
verify, engine checks in the APK gates) and the Blender addon's fail-closed token.

## Area 11 — Persistence, presets, importers (PROF)

Findings traced by the area auditor; PROF-01 and PROF-02 spot-verified by the lead.

### High

**PROF-01 — One corrupt Cura import bundle permanently blocks the restore of ALL persisted
state, and the saved plate is then overwritten**
- Where: data/AppStateStore.kt:173-186 recoverImportBundle promotes the staging file
  ".next" to the primary with no validation (verified: next.renameTo(importBundle)), while
  materializeBundle (:188-231) then require-throws on header/length/fingerprint/version
  mismatch. MainViewModel.restorePersistedState wraps the whole restore in one runCatching
  and reads the saved import BEFORE the workspace store, so the throw aborts everything.
- Trigger: process death or low-memory kill while the multi-second bundle copy is mid-write
  (first import after install, or after clear), or any later bundle corruption.
- Failure: on every launch the plate, settings and engine snapshots are never restored, with
  only "Saved Cura configuration could not be restored" and no self-heal. Every other store
  treats corrupt data as absent; the backup rules call this bundle expendable. Consequence:
  once the user rebuilds a plate, the next workspace save overwrites the previous workspace
  for good.

### Medium

**PROF-02 — Prusa .ini import silently falls back to defaults for every multi-extruder
(comma-list) value and reports success**
- Where: model/PrusaConfigImporter.kt:89-90 - double() replaces commas with dots ("1.75,1.75"
  becomes "1.75.1.75", null) and int() rejects "210,215" outright; the callers fall back to
  defaults. Verified. OrcaProfileImporter has exactly the missing first-slot rule.
- Trigger: import an .ini from a multi-tool Prusa profile (the picker offers XL 2T/5T and
  MMU machines) where temperature, filament_diameter, extrusion_multiplier etc. are
  comma-joined.
- Failure: the import reports success while temperatures, diameters and multipliers are
  silently the app's defaults - a PETG 230 C profile slices at 210 C.

**PROF-03 — PrusaPresetCatalog's resolution caches are unsynchronized HashMaps touched from
concurrent Dispatchers.IO coroutines**
- Where: model/PrusaPresetCatalog.kt:106-108,524 with getOrPut from five call sites; each
  printer selection launches a fresh walk and only isBusy gates taps.
- Trigger: two quick printer selections (or a selection during the initial catalogue load).
- Failure: concurrent getOrPut on HashMap over a slow walk: lost or torn cache entries, so
  picker lists are intermittently empty or wrong for the session.

### Low

| id | finding | where |
| --- | --- | --- |
| PROF-04 (UNCONFIRMED) | Import section precedence is first-wins for sections outside the priority list, and same-section duplicates keep the first (opposite of INI last-wins): a config-bundle-shaped file could merge presets into a franken-profile reported as success | PrusaConfigImporter.kt:43,66-71,167-171 |
| PROF-05 | OrcaProfileImporter's contract says a bundle may hold a vendor's whole catalogue while its caps refuse anything over 16 presets - a vendor .orca_bundle is refused by name | OrcaProfileImporter.kt:14-39 |

### Verified safe in this area (coverage note)

UserPresetStore staged writes, rollback, migration and engine scoping; WorkspaceStateStore
atomicity, lenient decode and path confinement; the three settings JSON codecs (additive
reads, nullable widths round-trip, serialize/apply parity machine-checked); the preset
schema/application/sanitizer gates; backup/restore exclusion lists match the real file names
everywhere except the Area-10 device-transfer gap; the full Prusa preset resolution chain
(all 138 bundle conditions pinned by snapshot); the Orca importer's hardening (first-slot,
boolean tokens, control characters, zip caps); the Cura import stack end to end including
formula refusal and XXE hardening; ExtraSettingSpec validation; the small stores.

## Area 12 — Snap fit (SNAP)

Findings traced by the area auditor against docs/snap-fit-notes.md; SNAP-01 spot-verified by
the lead.

### High

**SNAP-01 — The depth shift loses the wall-lip's cut solid: cuts land at the wrong plane,
silently or as a wrong refusal**
- Where: viewer/SnapJoint.kt:186-203 sunk() shifts unionSolid, subtractSolid,
registrationSolid, registrationRecess, frame and socketFrame - verified that cutSolid is the
one solid of the joint NOT in the list. The snap-fit-notes.md contract says a joint is
"carried along the axis as one piece, solids and frames together".
- Trigger: place a WALL_LIP joint with cuts, then move the depth arrow to any non-zero value
  (depth flows through parametersFor and into sunk()).
- Failure: placeEveryJoint subtracts the un-shifted cutSolid from the shifted ring: at small
  depth the ring is grooved at the wrong height - possibly above the roof the roof-standoff
  cap exists to protect - and at larger depth the slit misses the ring entirely and the
  preview refuses with "the cuts would take nothing out of the insert's ring" while the ring
  is right there. The cut readout still claims the pre-shift count. No test combines cuts
  with depth.

### Medium

**SNAP-03 — SnapLayout/SnapSpread measure the wall-lip's pocket in the wrong half's frame, so
the multi-joint guard's verdict follows the packer's layout instead of the geometry**
- Where: SnapLayout.kt:44-58 and SnapSpread.kt:386-395 pair joint.subtractSolid with
  socketFrame; that invariant holds for the cantilever (whose pocket is placed through the
  mate) but the wall lip places its recess through the lip half while socketFrame is the
  mate's - so the packer's tens-of-millimetres separation inflates every wall-lip footprint.
- Trigger: a seam carrying a wall lip plus another joint (explicitly supported), or the
  auto-spread with the lip style.
- Failure: the guard misses real crossings (another joint silently cuts the lip's ring apart
  - the exact failure it exists to prevent) and refuses clear layouts with fabricated
  crossing/wall numbers; the spread spacing notes carry the phantom width.

**SNAP-04 — SnapLibrary's shape cache is an unsynchronized HashMap behind a check-then-act,
reachable from overlapping preview jobs, and the caller races on one scratch file**
- Where: viewer/SnapLibrary.kt:362,379-380,454; previewSnapJoint cancels the old job but
  load() is cancellation-blind (seconds of asset reads and booleans), so the cancelled job
  completes while the next re-enters for the same shape; MainViewModel.libraryMesh writes the
  same .part scratch file concurrently.
- Trigger: a library-style joint with two quick edits (slider drags).
- Failure: unsynchronized concurrent get/put on one HashMap (lost entries, corrupted table),
  plus a possible truncated- STL read from the shared scratch file.

### Low

| id | finding | where |
| --- | --- | --- |
| SNAP-02 | The ladder's drop reason is misattributed: the "material is too thin" branch is dead (the walk only moves to lower rungs), so thin-material builds tell the user "the wall is too narrow for the key" for a key that fits, naming the wrong lever | SnapFit.kt:176-202 |

### Verified safe in this area (coverage note)

SnapApply's assembled-frame staging and halvesMeet invariant (SnapApplyTest); the watertight
gate; halvesMeet refusals with spans; the depth sign convention; the deflection gap with its
named clamp; multi-barb pitch/pawl/click labels; clearance separation; the hook clamps with
both numbers; SnapLip's ring build with named refusals and roof-capped slit floors at depth
zero; SnapLibrary's fit-before-triangle check with both number sets and the max-scale lever;
cantilever/library layout and spread; SnapPad contour rules; SnapSeam room measurement;
SnapGhost; the parametersFor depth trap (pinned); sequential multi-joint booleans with the
three named refusals.

## Area 13 — State core: MainViewModel + MainUiState (VM)

Findings traced by the area auditor; VM-01 spot-verified by the lead. VM-02 is the same
defect as SET-01, independently found from the state core's side and confirmed there.

### High

**VM-01 — Workspace restore silently drops every wall-lip cut and size and the library shape
of recorded snap joints**
- Where: MainViewModel.kt:9327-9340 restoreWorkspace builds SnapJointRecord with only
  anchor/depth/half/tightness/barbs/facing/style/lipEngagementMm/lipToothHeightMm - verified:
  the save side (toSnapshotSnap, :9510-9533) carries the full set under the comment "Every one
  of the lip's own choices, not just the first two" (lipWallMm, lipRecessExtraMm,
  lipCutWidthMm, lipCutDepthMm, lipCuts, lipCutDirections) plus libraryShape, and the store
  round-trips them all.
- Trigger: build a wall-lip joint with cuts and typed sizes (or a library hook with a chosen
  shape), Apply, relaunch the app, open Snap fit.
- Failure: joints come back at default sizes with zero cuts (or a library hook rebuilt from
  the default cantilever), silently; pressing Apply overwrites the record and the user's cut
  layout and sizes are permanently gone. Tests pin the SAVE side and the store round-trip;
  the ViewModel restore mapping is the unpinned gap.

**VM-02 (= SET-01) — All-settings overrides change a slice input without invalidating the
published G-code**
- Confirmed from both scopes: MainViewModel.setExtraSetting/removeExtraSetting (:2893,:2942)
  are the only mutators without withoutPublishedSlice, and the extras are slice inputs for
  all three engines. Stale G-code exports and uploads silently. See SET-01 for the entry.

### Medium

**VM-03 — A settings edit's workspace re-save can be permanently skipped, and the whole
workspace is then refused at the next launch**
- Where: MainViewModel.kt:9602-9617 persistSettings only re-saves the descriptor while its
  generation still matches; beginOperation (:9843-9853) bumps that generation and a skipped
  write is never retried. The descriptor carries the settings fingerprint at its last save.
- Trigger: a settings burst whose chained write is still draining, then any gated action that
  does not itself rewrite the descriptor (Slice, exports, mesh-to-step, texture apply); or
  process death inside the same job.
- Failure: next launch answers "Settings changed since ... the workspace was not restored":
  models, placements, paint, snap pair and CAD record all gone from the session. Ops that
  re-save self-heal; Slice/Export never do.

**VM-04 — "Rebuild plate from the document" (and the connect-agent republish) never persist
the workspace descriptor**
- Where: rebuildCadPlateFromDocument (:1048-1101) publishes and clears the derived ids but
  takes no snapshot; scheduleCadPersist's eight call sites are all elsewhere.
- Trigger: restore a workspace whose plate holds a split/snap layout made from a CAD
  document, press Rebuild, kill and relaunch.
- Failure: the descriptor still names the pre-rebuild derived objects, so the relaunch
  silently restores the stale layout the press was supposed to replace (AGENTS.md names
  Rebuild as the deliberate way to take the new shape).

**VM-05 — BumpMesh texture apply replaces only the in-memory mesh; the object's file and
descriptor are never updated**
- Where: applyBumpMeshTexture (:8529-8627) updates state but leaves sourcePath naming the
  pre-texture staged STL; no workspace save runs on the apply path.
- Trigger: apply a texture, then process death.
- Failure: restore re-parses the old file and the model silently reverts to its untextured
  shape; sendModelToBlender also ships the untextured file. Every comparable geometry change
  stages a new file and updates sourcePath; this one does neither.

**VM-06 — Part Topo import appends where its contract says it replaces, and the result lands
exactly on top of the model it was derived from**
- Where: importPartTopoResult's KDoc says "Transactionally replaces the active model" and
  the preparer places the result at the analysed mesh's exact centre/minZ, but onSuccess
  appends via withModelAdded. (The status line says "as a standalone Part Topo model" - the
  one intent ambiguity in this entry.)
- Trigger: Smart Infill > Part Topo > export.
- Failure: the plate holds both bodies overlapping at the same spot, and the caller then
  clears the modifier package although the geometry it was made for is still on the plate -
  the original silently loses its modifiers.

**VM-07 — Switching workspace drops the running in-app turn's cancel handle; Stop/Cancel
become no-ops**
- Where: setPlateView (:3542-3563) nulls inAppCadAgent without cancelling a running turn;
  the turn stops only via its own cancel(), which the null-out has orphaned.
- Trigger: an in-app agent turn running, tap the Plate/ScanRuler switch, press Stop.
- Failure: the turn cannot be stopped and keeps editing / holding the engine until it ends on
  its own; cadTurnRunning keeps Convert/Rebuild refused with no way out.

### Low

| id | finding | where |
| --- | --- | --- |
| VM-08 | A cancelled ScanRuler enter job can overwrite the fresh run's state (failure text and scanRulerLoading cleared without checking it is still the current job) | MainViewModel.kt:3597-3690 |
| VM-09 | The import pre-save descriptor omits snap and cad, so a crash in the window permanently loses the pair record and CAD state | MainViewModel.kt:2352-2512 vs :9441-9476 |
| VM-10 | Derived-object and pair-source staged files leak when a CAD document is closed or replaced | MainViewModel.kt:994-1039,1512-1587,4216-4224 |

### Verified safe in this area (coverage note)

The invalidation coverage map (every other mutator drops the published slice); snap
derivations and staleness keys; the concurrency guards (operation lock, stale-plate checks,
stale-result drops, write chaining, deferred-restore replay); the artifact lifecycle (leases,
pending exports, interrupted-export recovery); removeModel semantics; the descriptor
contract itself (fingerprint mechanism sound - VM-03 is the writer side); arrangement; the
CAD repaint pipeline with name-based face reapply; rotation survival; single-flight preview.

## Area 14 — CAD agent, SolidCraft, bridges (CAD)

Findings traced by the area auditor; CAD-01 spot-verified by the lead. cad-known-lows excluded.

### High

**CAD-01 — The ScanRuler bridge row can never hand over its token: the documented
external-agent path for the 9879 facade is unusable**
- Where: agent/AgentBridgeHandover.kt:84-94 (verified) - AgentBridgeTokens.file() has cases
  for 9876/9877/9878 and returns null for 9879; AgentBridges.TARGETS nevertheless publishes
  9879 and the sheet gates the row's Copy on the token being available.
- Trigger: mint the ScanRuler token (the embedded workspace start does), turn on Reachable on
  the network.
- Failure: the ScanRuler row permanently reads "start the engine first - its token is created
  when it starts" with Copy disabled - a false sentence - and the one-line handover promised
  by MCP_TOKEN_SECURITY.md can never be copied. No other ScanRuler token copy path exists, so
  on a non-rooted phone an external agent can never receive the token. A test pins available()
  as exactly the three engine ports while the sheet renders four rows.

### Medium

**CAD-02 — Engine crash plus reconnect permanently breaks the external bridge and can block
the in-app agent behind a ghost author**
- Where: solidcraft/SolidCraftEngine.kt:261-269 nulls the session on engine death but never
  closes the bridge; :298-308 keeps the OLD bridge when the new bind fails (the stale one
  still holds 9878) and returns true.
- Trigger: the engine child dies (the class doc anticipates exactly this), user presses
  Connect agent again.
- Failure: every external call on 9878 is answered by the stale bridge forwarding to a dead
  client ("the SolidCraft engine session is closed" instead of the -32002 that exists for
  it), the rows still show "app bridge up", and new connections still claim authorship so
  the in-app agent is refused on behalf of a client that cannot edit anything. Recovers only
  on app restart or a clean Stop.

**CAD-03 — CadPersistScheduler's lost-wakeup drops the trailing save of a burst - the exact
bug its contract claims fixed**
- Where: solidcraft/CadPersistScheduler.kt:55-79 - schedule() sets dirty and returns while a
  job is active; run() reads dirty==false and is about to return in the race where the
  trailing save never fires.
- Trigger: a final edit of a burst racing the draining job on two threads.
- Failure: design, sidecar and descriptor stay at the previous edit until some later edit or
  the close-time persist; a process death loses the burst's last edits. The test's
  single-threaded driver cannot hit the race.

**CAD-04 — Stop/Cancel is silently discarded if pressed before the first model request of a
turn**
- Where: cadagent/CadAgent.kt:226-229 - run() begins by resetting cancelled = false,
  clearing a Stop that arrived during the turn's real setup window (busy flag set, live view
  attach, tools/list round trip, runbook load).
- Trigger: tap Send, then Stop within the setup window.
- Failure: the turn runs to completion; no "Stopped" status, no transcript row - silent
  failure of the one control that bounds a loop with no round limit. After the first model
  request, cancel works as documented.

### Low

| id | finding | where |
| --- | --- | --- |
| CAD-05 | Take over can silently take back nothing: a connection in the attach-then-add window is neither closed nor detached (and the symmetric close race leaves a ghost author) | SolidCraftBridge.kt:139-162 |
| CAD-06 | Copy transcript can carry a data URL through tool-row arguments (set_parameter's unclipped summary), breaking AGENTS.md's "never carry a data URL"; withoutImageData also misses mime types with extra parameters | ChatTranscript.kt, AgentToolTrace.kt:66-71 |
| CAD-07 | Timeout errors name the wrong budget ("did not answer within 180 s" for a 20 s connect timeout) while the same row prints the real elapsed time | OpenAiChatClient.kt:106-114, AgentWebTools.kt:375-382 |

### Verified safe in this area (coverage note)

The CadAgent loop contract (retry-once rules, vision fallback, image caps, heartbeat,
ask_user); OpenAiChatClient redaction, caps and named failures; web tools nonce fencing;
sealed key handling with mask-only rendering; the turn service's wake-lock pairing;
question card lifecycle; the one-author rule internals; the bridge token gate (fail-closed)
and id rewriting; McpClient timeout honesty; DocumentDelta merging; CadPartPublish
placement/paint carry rules; the convert gate (no automatic conversion anywhere); face
selection by name; the forwarder's byte transparency and pipe cap; the files screen's rules.

## Area 15 — Smart Infill and texturizer (SMT)

Findings traced by the area auditor; SMT-01 spot-verified by the lead.

### High

**SMT-01 — Native session use-after-free: the Apply export calls are not tracked work and
are not gated on closed, so close() destroys the filaSim session under them**
- Where: smartinfill/SmartInfillController.kt:571-574 (verified) - modifierArchive() and
  optimizedShape() call the engine directly, outside the tracked work Job; close() (:606-615)
  destroys the engine immediately when the tracked work is done.
- Trigger: after Optimize, Apply runs the blocking JNI export (11-21 MB, hundreds of ms) on
  Dispatchers.IO; any model change in that window disposes the controller and closes it on
  the main thread while the export still dereferences the native session.
- Failure: native use-after-free - SIGSEGV app death or a corrupted archive silently handed
  to the store/import. Contradicts the documented "destroyed only once the running call has
  returned" contract, which the handle registry only enforces for calls that begin after
  destroy. Tests pin only the tracked-run entries.

### Medium

**SMT-02 — Part Topo import throws "placement transform is unavailable" when the incoming
body already sits at the placement point**
- Where: ui/PartTopoResultPreparer.kt:40-43 requireNotNull(transformed.slicingTransform);
  ModelPlacement.transformed early-returns the mesh unchanged for a near-identity placement
  (its documented fast path), and a parser-produced mesh has no slicingTransform, so the
  require throws.
- Trigger: a centered-bed printer profile (originAtCenter) at default placement, where the
  engine's recentered result arrives exactly at the target point; symmetric results too.
- Failure: Apply reports "The optimized body could not be imported; the model was kept" for
  a good result. The tests use asymmetric fixtures where the early return cannot fire.

### Low

| id | finding | where |
| --- | --- | --- |
| SMT-03 | The legacy thumbnail lookup in the BumpMesh gallery can never match (full path and a missing dot compared against bare asset names): every cell decodes the full texture instead and the shipped thumbs are dead weight | BumpMeshTextureCatalog.kt:89-95 |

### Verified safe in this area (coverage note)

The frame contract round trip (pinned from both ends); SmartInfillPackageStore's full
validation rules including fingerprint binding and budgets; the JNI handle registry and
close idempotence; the controller state machine otherwise (busy/closed gating, stale-result
invalidation, options refused mid-run); thermal report validation; the WebView host's
budgets and one-shot provider; the panel's input parsing (NaN refused before org.json); the
BumpMesh pipeline (limits, mask remap semantics, export budgets, single-flight slots); the
studio model/Sheet staleness and undo guards.

## Area 16 — Mesh IO and geometry (MESH)

Findings traced by the area auditor (MESH-01 includes a faithful simulation of the float
expressions); the structural claims spot-verified by the lead.

### High

**MESH-01 — BedClipper's clipClosed silently drops the cut-face cap on ordinary float
coordinates: Split's clipper fallback yields open halves that slice to nothing**
- Where: viewer/BedClipper.kt:236-242 records each crossing with the triangle's own walk
  order (corners[edge] to corners[next]) while the kept surface's cut() (:207-208) is always
  below-to-above; chainCrossSection (:733-766) joins segment ends only on an exact
  PositionKey and returns null on one unmatched endpoint, and crossSectionCap then returns
  empty - the whole cap is dropped, silently.
- Trigger: Split on a model whose coordinates are not binary-exact (any placed, rotated or
  organic model) when the clipper fallback runs (no manifold library on the device,
  non-manifold mesh, or boolean failure). Two adjacent crossing triangles traverse their
  shared edge in opposite directions, so the shared endpoint is computed by two different
  float expressions and lands on different keys. The auditor's simulation over the exact
  Float expressions shows an 8-crossing box fails to cap in several percent of trials and
  success falls off with crossing count - a real cross-section caps less than half the time.
- Failure: the half is left open exactly as the code's own doc warns ("an open outline at
  every layer and slices to nothing at all"); snap/boolean on the halves is refused
  NotManifold. The existing tests all use binary-exact geometry where the two expressions
  coincide bitwise, so none can see this. Fix: record crossings with cut()'s orientation, or
  join with the tolerance weldCutSection already uses.

**MESH-02 — The 3MF parser accumulates every vertex and triangle in boxed lists before any
limit applies: a valid 3MF at the app's own default triangle limit OOMs the import**
- Where: viewer/ThreeMfModelParser.kt:293-310 (SAX handler appends unbounded) and :48-58
  (the parse completes before emit() enforces the limit at :87); vertices are never limited.
- Trigger: import a 3MF with about 1.5M triangles - exactly the documented default limit -
  or any file with millions of vertex/triangle elements (a small compressed 3MF expands
  hugely; the only size guard bounds the compressed file).
- Failure: heap exhaustion during import. The auditor's arithmetic puts the transient at
  roughly 650 MB of boxed collections for a mesh the STL path loads in one 108 MB buffer.
  Hostile amplification is unbounded. Contrast: StlParser enforces its caps during the scan.

### Medium

**MESH-03 — The 3MF XML is parsed with an unhardened SAXParserFactory, bypassing the app's
own SecureXml contract: entity expansion is an unbounded DoS**
- Where: ThreeMfModelParser.kt:54,163 - plain SAXParserFactory.newInstance() with no
  disallow-doctype-decl or secure-processing features, while profile/SecureXml.kt:27 carries
  exactly that hardening and is pinned for the profile path.
- Trigger: a crafted 3MF whose model part declares recursive internal entities (billion
  laughs). External-entity disclosure is likely unreachable on Android (Expat does not
  resolve them) - this is DoS, not disclosure.

**MESH-04 — LayerPreviewSurfaceView.maximumLayerZ is never assigned (always 0): the Layers
tab camera ignores print height and orbits about the bed**
- Where: viewer/LayerPreviewSurfaceView.kt:187 declares it, :315/:329/:447 read it; verified
  no assignment exists. Git history shows commit 68b3c913 deleted the assignment
  ("maximumLayerZ = value.layers.maxOfOrNull { it.z }") and never replaced it.
- Trigger: open the Layers tab on any print whose height is comparable to its footprint.
- Failure: fit radius ignores Z (a tall print is fitted as if it were flat - cropped or
  overflowing at default zoom) and the orbit pivot sits on the bed instead of mid-model.
  Silent; no test pins the framing.

**MESH-05 — One direct ByteBuffer per mesh contradicts AGENTS.md's heap rule; the mesh path
allocates single buffers up to 576 MB and its comments assert the opposite premise**
- Where: viewer/StlMesh.kt:56-70, StlParser.kt:86-94 (one allocateDirect of
  triangleCount x 72 bytes), BedClipper.kt:306-315, MeshSolidBuilder.kt:78-87.
- Trigger: import any STL at or above 200k triangles at the shipped limits: default 1.5M ->
  108 MB direct buffer; Extreme 8M -> 576 MB - larger than the single direct buffer AGENTS.md
  records as killing the Path tab at 160 MB.
- Failure: heap accounting exhaustion at sizes the settings UI invites, with no
  BandAllocator-style fallback. The doc comments and the off-heap test encode the premise
  AGENTS.md records as false - a contract violation even though the feature is deliberate.

### Low

| id | finding | where |
| --- | --- | --- |
| MESH-06 (UNCONFIRMED window) | MeshBoolean.ensureLoaded/isAvailable can answer "unavailable" while the first load is in flight, silently sending Split down the clipper fallback | MeshBoolean.kt:34-55 |

### Verified safe in this area (coverage note)

StlParser's binary/ASCII bounds and completeness proofs (triangle-count lies and truncation
refused); StlMeshWriter sidecars; PlateThreeMfWriter's dialect stamps and paint encoding
(matching the hardware-confirmed attributes); the 3MF geometry transforms, component depth
cap and per-triangle limit at emit; MeshBoolean handle release and soft-failure contract;
MeshBvh arithmetic and stack bounds; MeshPicker unprojection and clip semantics; MeshRepair
loops and refusals; MeshSolidBuilder/MeshVolume math; BedClipper's core clip itself
(capacity, winding, snapping, weld) - the defect is confined to the cap recording/chaining
step; SolidSplitter routing; BedPlaneDrag; the nozzle-path views' buffer sizing rules
(countKind, band rebuild, named refusals); the layer preview draw path.

## Area 17 — Non-planar and conical transforms (NPC)

Findings traced by the area auditor; the structural claims spot-verified by the lead. This
was the final pass of the full-app survey.

### High

**NPC-01 — flushSkins leaves the nozzle at the last shell piece in absolute mode: the next
move and the whole machine-end script execute from the wrong point**
- Where: nonplanar/ConformalGcodeTransformer.kt:808-815 (verified) - the return hop
  (travelDance back to the source position) runs ONLY when emissionModal.absolutePosition is
  false, under the comment "In absolute mode the next original move carries its own X/Y/Z, so
  no return hop is needed." Cura output is G90/M82 absolute, so after every skin flush the
  emitted position is the last shell-piece endpoint while the source stream is at the home
  layer's end. Absolute coordinates are valid; the PATH between them is a straight chord.
- Trigger: any conformal print (defaults: 3 conformal shell layers). Shell flushes happen at
  layer markers and at the machine-end sentinel, each followed by moves that now execute from
  the shell end.
- Observable failure: (a) the first post-flush move crosses/drags through the shells just
  printed; (b) the ENTIRE machine-end G-code runs from the shell end - with Cura's default
  relative end script the retract+lift and wipe happen over the fresh shells (ooze on the
  part, wipe across it); (c) a post-flush extrusion prints along the wrong chord. The
  relative branch shows the intended contract ("must land back on the source position").
  No test pins it: every fixture's home layer is the last layer, so all flushes occur at the
  sentinel with no move following.

### Medium

**NPC-02 — The conical G-code back-transform has no cooperative cancellation at all**
- Where: conical/ConicalGcodeTransformer.kt - zero cancellation checks in the file
  (verified: grep count 0) across the four full-file passes (every move subdivided to 0.5 mm
  - millions of staged lines on a realistic file).
- Trigger: user cancels a conical slice while the back-transform runs.
- Failure: the cancel is never observed; the transform runs to completion and publishes. The
  preparation got exactly this fix (CAN-01) and the conformal transformer got it too
  (CURV-01); this file never did. The tests pin preparation only.

**NPC-03 — Whole-plate-cutoff (zone 3) collision warnings are swallowed when they are the
only overlap**
- Where: nonplanar/NozzleCollisionScanner.kt:477-481 increments cutoffViolatingMoves but the
  return gate (:495) checks only violatingMoves - verified. The contract says zone 3 warns
  "regardless of horizontal distance", and the UI maps zone 3 to "the plate clearance" and
  appends the material-exceeds sentence when the counter is positive.
- Trigger: a conformal print where the only overlap is the cutoff zone (e.g. an end-script
  lift/park below the holder clearance height, or a shell-flush dive with a small measured
  clearance).
- Failure: the named plate-clearance warning can never appear for a cutoff-only overlap; the
  slice publishes silently. The test that claims "the cutoff must warn" passes only because a
  zone-1 violation co-occurs in its fixture.

### Low

| id | finding | where |
| --- | --- | --- |
| NPC-04 (stock reachability UNCONFIRMED) | The conformal transformer does not reject in-printable G92 X/Y/Z coordinate resets while the conical has the exact require - the docs' rejected-output list says the slice must fail; custom G-code is the plausible producer | ConformalGcodeTransformer.kt:863-873 vs ConicalGcodeTransformer.kt:140-142 |
| NPC-05 (stock reachability UNCONFIRMED) | Conical E compensation drops retraction on spatial moves with negative E and all extrusion on Z-only moves, and can explode on near-Z-only moves; the conformal transform preserves them - the two transforms disagree | ConicalGcodeTransformer.kt:271-275,288-299 |
| NPC-06 | Firmware-retract detection uses raw prefix matching and misses the leading-zero aliases (G010/G011) the command policy promises to normalize: shell hops then print unretracted across the fresh shells | ConformalGcodeTransformer.kt:378-381 |
| NPC-07 | The effective slope limit floors itself at 5 degrees, breaking its own clearance invariant when the UI-exposed nozzle taper angle is below 10 degrees | NonPlanarSettings.kt:74-79 |

### Verified safe in this area (coverage note)

Machine-end boundary monotonicity in both transforms (the named hunt item); the SUP-01
support-tower clamping fix and E-state accounting across clamped moves; the arc-rejection
policy everywhere (fail-closed through the publisher); the conformal walk-1/walk-2 mirroring
for stock output; conformal E conservation to 1e-6; the surface builder's weld packing and
boundary back-off; conical transform math; MAX_EMITTED_MOVES checked-not-trimmed (AGENTS.md
contract); staged/temp cleanup on every refusal; sidecar validation; overhang strategy
exclusivity; both settings sheets' input rules; the collision sweep reads every move (no
toolpath sampling). Content note: docs/non-planar.md's Pipeline/Options sections still
describe the removed relief-field implementation (options that no longer exist); its
marker/rejected-output/artifact sections match the current code.

## Addendum - findings from the fix round (2026-11-05)

Found while verifying the safe fixes with their new tests.

**PRN-08 (medium, fixed in this round) - a locally answered call can wedge the PC-route
connect for the whole read timeout**
- Where: printer/MoonrakerTransport.kt - write() answers gcode/subscribe_output locally by
  queueing the reply in the ready deque, but the client's reader thread may already be parked
  in the socket read inside pump(), where it cannot see the queue until the read timeout (30 s)
  or the next socket arrival. The client's first call after connect is subscribeOutput, so the
  whole session - and the "Connecting to ..." state - hangs on which side wins one start-order
  race.
- Trigger: any PC-route connect (Moonraker). Observed by the new KlipperPrintStartTest as an
  intermittent 5-second "the fake host never reported X: the state says null"; the same race
  in production shows as an occasional ~30-second "Connecting to ..." before a PC host appears.
- Fix (applied): the local answer sends a WebSocket ping; the host's RFC-required pong is the
  socket activity that brings the reader back to the queue. The test's fake Moonraker was also
  protocol-noncompliant (it ignored pings) and now answers pong like a real server.
  Verified: KlipperPrintStartTest passes 3 consecutive reruns where it previously failed ~50%.

**MESH-01 completed** - the recorded orientation fix was necessary but not sufficient: the cap
and the kept surface computed a shared crossing through complementary lerps (bit-different in
the last bits), and the exact-key weld split the seam at those points. cut() now sorts its
corners below-to-above internally, so every producer of a shared crossing computes it from the
same expression in the same direction; on the placed-geometry fixture the capped halves are
edge-manifold (open edges before: 16 per half, after: 0). The earlier uAt trapezoid-endpoint
snap is kept as well.

**Test-side notes** - two new ModelToolsSheet tests were adjusted: Compose visibility asserts
("is not displayed") proved unreliable for panel-internal captions in the constrained test
surface, so the refusal sentence is pinned by existence and the rotate-tally contract by a
source pin (the same pattern as the repo's other *SourceTest files).


---


---


---


---


---


---
