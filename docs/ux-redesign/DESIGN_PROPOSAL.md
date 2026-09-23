# EnderSlicerCura — UI/UX redesign proposal (v1)

Proposed information architecture, visual language and per-screen UX changes.
Mockups: [mockups/](mockups/) — PNG renders of the nine proposal screens.
Sources: [mockups/generate.mjs](mockups/generate.mjs) + [mockups/style.css](mockups/style.css);
regenerate with \`node mockups/generate.mjs\` + the Edge screenshot script (see header of generate.mjs).

---

## 1. Where the app is today

The current app is one screen plus menus and sheets:

- A single Scaffold: top bar (title, "Plate", "OctoPrint", "Menu"), bottom bar (Slice / Export G-code), and the viewer in the middle.
- Almost everything else is a **94%-height modal bottom sheet**: Print settings (accordion), Profiles & filament, Printer & G-code, Model tools, Mesh limit, Non-planar, Conical, Layer events, OctoPrint (96%), Smart Infill.
- Four menu levels deep: **Menu → Advanced → Experimental tools → submenu** (BumpMesh, Non-planar, Conical, Smart Infill).
- Viewer info is scattered across floating cards: printer summary, orientation gizmo, mode buttons, gesture hints, status — all overlaid at once.

### Problems this creates

| # | Problem | Consequence |
|---|---|---|
| 1 | Every feature is behind the Menu or a menu-in-menu | Users cannot discover 60% of the app; frequent features cost 3–4 taps |
| 2 | No persistent navigation | No way to jump plate ↔ settings ↔ print; no "where am I" |
| 3 | Sheets pretend to be screens | Modal sheets block the model view; no back-stack; accidental dismissal; heavy sheets on a 412 px phone |
| 4 | Settings have no search and no per-field provenance | 60+ fields in accordions; hard to find what changed vs. the imported Cura profile |
| 5 | No onboarding | Machine values are buried in the menu, yet they drive engine formulas and validation |
| 6 | No brand identity | Material dynamic color from the wallpaper: the app changes identity per device, looks generic |
| 7 | Print workflow split | OctoPrint is a sheet; estimate/warnings are vague text; nothing connects "sliced" with "ready to print" |
| 8 | Nozzle-path view is functional but flat | Geometric "beads" render as flat extruded ribbons; camera lacks fit/zoom feedback and orthographic measurement mode |

## 2. Design goals

1. **One finger, one thumb:** the four most-used destinations are one tap away on a bottom navigation bar.
2. **Full screens where rules require them** (settings, printer, print session); **stay modal only for quick pickers** (model tools, layer events, non-planar options).
3. **Provenance is visible:** every value shows who set it — PROFILE (from the Cura profile), IMPORTED (from a project/configuration), APP (user override).
4. **Safety first:** a printer checklist screen, confirm on destructive actions, disabled-with-reason everywhere.
5. **A real 3D viewer:** accurate nozzle-path geometry, stable analytic shading, and a camera that fits, orbits and measures.
6. **Adapts to foldables:** unfolded, the plate view gains a live session/quick-settings pane instead of empty space.

## 3. Visual language — "engineering cockpit"

Dark, warm **amber accent** (consistent with the app's print-heat world), cool neutral surfaces, dense but calm.

| Token | Value | Use |
|---|---|---|
| bg | #0B0E13 | page background |
| surface / surface-2 / surface-3 | #11161D / #171E27 / #1E2733 | cards, inputs, chips |
| border / border-2 | #232C38 / #2E3947 | 1 px card borders (hairline structure, no shadows-only) |
| text / text-2 / text-3 | #E8EEF4 / #99A6B3 / #66727F | primary, secondary, tertiary |
| **accent** | #FFB454 | primary actions, active tab, highlights (amber = heat/nozzle) |
| ok / danger / warn / info / violet | #3ECF7A / #F0655D / #E8B44C / #6FB8FF / #B08CFF | semantic states, legend colors |
| radius | 14 / 10 / 8 px | cards, buttons, chips |
| grid | 4 px + 48 dp touch targets | existing EnderSlicerDimens contract, unchanged |

Typography stays Roboto (system) with tabular numerals for engineering values. Icons are a small stroke set (cube, layers, wave, sliders, printer, power, wrench, …) — consistent weight 1.7–1.9, rounded caps.

The app currently yields to wallpaper dynamic colors; the proposal pins this scheme (dark-first). Light theme would follow the same token structure.

## 4. New information architecture

**Four bottom-nav destinations: Plate · Settings · Print · More.**

| Tab | Contents | Replaces (today) |
|---|---|---|
| **Plate** | Viewer (Model / Layers / Path), model chip, import, Tools menu (transform, support paint, layer events, texturize, mesh limit), Slice / Export | The whole current home screen + Model tools sheet + Mesh limit menu item |
| **Settings** | Full-screen print settings: profile header, search, category chips, per-field value + origin badge | Print settings sheet (94%) |
| **Print** | Full-screen OctoPrint session: connection, job, temps, camera, files | OctoPrint sheet (96%) |
| **More** | Profiles & filament, Printer & G-code, config snapshot, Experimental (BumpMesh, Smart Infill, Non-planar, Conical, mesh limit), About | Menu "Configuration" + "Advanced" submenu chains |

Deep actions (non-planar/conical options, Smart Infill sheet, layer events, model tools) keep bottom sheets — they are momentary configuration, not destinations. The "Plate ▾" menu (clear plate) becomes part of the Tools menu with a destructive-style confirmation.

## 5. Screen-by-screen

### 01 — Plate (model) · [mockups/01-plate.png](mockups/01-plate.png)

- **Top bar:** brand mark + "Plate" + model name/triangle count. One **Import** button (STL / .3mf / .curaprofile in one place, no menu needed); overflow ⋮ for scan/niceties.
- **Viewer HUD** is now two compact cards: model stats top-left (name, size, triangles, placement + a single OK state), and the **Model | Layers | Path** segmented control top-right.
- **Status strip** (one line under the viewer): dot + state ("Ready"), and the machine context on the right ("Ender 3 V2 · 220×220×250 · 0.40 nozzle") — replaces the always-open printer summary card, freeing the top of the viewer.
- **Action bar:** Tools (transform/paint/events/texture/mesh-limit), **Slice** (filled amber — the single primary), **Export**.
- _UX change:_ the bottom "Slice a model first" hint and printer summary move into one quiet strip; the primary action stays pinned and visually dominant.

### 02 — Plate (layers preview) · [mockups/02-plate-layers.png](mockups/02-plate-layers.png)

- After a slice, the layer slider + **legend** (walls/infill/supports/travel) appear between viewer and status strip.
- The **layer chip shows L42 + Z height**; an **Events chip with a count** surfaces layer events in place of a hidden menu item.
- Status strip carries the whole result summary: "Sliced · 118 layers · est. 2 h 34 m · 26.4 g".
- _UX change:_ slice outcome, layer data and event editing live in one place; no more jumping through menus to find per-layer tools.

### 03 — Print settings · [mockups/03-settings.png](mockups/03-settings.png)

- Settings become a **full screen with a back button** (sheet → screen), so you can compare values against the model view without a modal over it.
- **Profile header card:** resolved name ("PLA · 0.20 mm · 15% infill"), source (imported profile + Cura version), override count, warnings chip, one-tap reset.
- **Search field** over all print settings; **category chips** filter the list (All / Quality / Walls & top / Infill / Speed / Material / Cooling / Supports / Travel / Adhesion / Experimental).
- **Every field shows value + unit + origin badge:** PROFILE (grey), IMPORTED (blue), APP (amber). Collapsed categories show a one-line summary instead of an empty header.
- _UX change:_ settings apply immediately (unchanged contract), but now the user can find a field, see whether it came from Cura or from their own override, and reset selectively — the biggest single discoverability win for Cura users.

### 04 — Print (OctoPrint session) · [mockups/04-print.png](mockups/04-print.png)

- **Connection card** (dot, host, API version) replaces multi-line sheet headers.
- **Job card:** file name, state chip, progress bar with gradient, elapsed/remaining, Pause + Cancel (danger-styled, with the existing confirmation guard).
- **Temperature row:** hotend / bed / fan as numeric tiles — at-a-glance print health.
- **Camera card:** live badge, fullscreen expand, snapshot taps straight into the existing fullscreen viewer.
- **Files card:** recent files with print-again affordance.
- _UX change:_ the print session reads as one continuous monitoring page rather than a stacked sheet; everything important is above the fold.

### 05 — More · [mockups/05-more.png](mockups/05-more.png)

- Grouped, icon-led list: **Configuration** (Profiles & filament, Printer & G-code, Configuration snapshot) / **Experimental** (BumpMesh, Smart Infill, Non-planar, Conical, Mesh triangle limit with state text) / **About**.
- Experimental rows carry explicit state ("Off" or badge) — a single glance tells what is enabled.
- _UX change:_ the menu-in-menu disappears; each item is one tap with a description telling you what it does.

### 06 — Printer · [mockups/06-printer.png](mockups/06-printer.png)

- **Safety checklist** (build volume, nozzle, max hotend, start/end G-code, OctoPrint) — 5/5 with per-item state, replaces the "check every dimension before use" paragraph with an actionable list.
- **Machine profile** with the same origin badges as settings.
- **Start & end G-code** card with inline preview and edit.
- _UX change:_ printer setup is now a deliberate, verifiable step — the machine data that drives everything gets a home the user finds before their first slice.

### 07 — Onboarding (first run) · [mockups/07-onboarding.png](mockups/07-onboarding.png)

- Three-step first-run: **Printer → Material/profile → Print & safety**. Step 1 shown: name + volume + nozzle + bed defaults, plus a hint that a Cura project (.3mf) import fills everything at once.
- **Skip for now** keeps defaults; nothing is force-required.
- _UX change:_ first-run sets the machine context instead of dumping the user into an empty plate with a "Import an STL from Menu" hint.

### 08 — Foldable (unfolded) · [mockups/08-foldable.png](mockups/08-foldable.png)

- Landscape split: viewer + layer scrub left, **session pane right** — print session chips (layers/time/weight/warnings), quick settings (layer height, infill, supports, brim) and the action stack.
- _UX change:_ the unfolded surface is used as a cockpit; the same bottom nav keeps orientation.

## 6. Nozzle-path view — accurate geometry, quality shading, better camera

Mockup: [mockups/09-nozzle-path.png](mockups/09-nozzle-path.png). This is a rendering + camera overhaul of the existing path view, not a feature change.

### 6.1 Geometry (accuracy first)

The path renderer already builds per-move beads from real slice data (width from ΔE·A/length/layer-height, height from layer height). Proposal:

- **Cross-section:** a rounded-top extruded profile (flat base, domed top) instead of a flat ribbon; width = clipped extruder width, height clamped to the layer height (keeps 0.12 vs 0.20 mm visibly different, keeps flow variance visible at corners).
- **Junctions:** miter joins on wall corners, butt joins inside infill, **capsule ends** on travel (zero-flow) moves; micro-segments < 0.05 mm collapse to hairline width (removes the dark-spec artifacts from 5.14-alpha flow data).
- **Per-move data in the shader:** start/end point, width, height, flow, speed — so the existing parser data (already capturing per-move flow/layer height) is used for rendering, not only stored.

### 6.2 Shading (stable, no moire)

Today: per-face directional tint + flat 0.90× side tint; the directional range caused corduroy stripes at low zoom — hence the flat tint. Proposal:

- **Analytic face normals** computed per segment (not vertex-interpolated) with a **fixed 3-light rig**: key 55% (warm, 40° elev), fill 25% (cool, opposite azimuth), rim 15% (specular-ish back edge). Top face = smooth radial gradient of the bead hue; side faces = hue-shaded 0.82–0.95×.
- **Ambient occlusion:** per-bead proximity term between adjacent wall loops and between beads — close beads darken each other's flanks gently (kills the "floating ribbons" look at high zoom, and because it is analytic, it cannot alias).
- **Contact shading:** soft ground shadow under the whole path plus per-bead contact at base.
- **Compositing:** 2× MSAA, gamma-correct blending, no alpha banding; the tint stays flat per side face so zoomed-out rendering remains clean — the corduroy artifact is addressed by analytic normals + AO instead of by removing directional light entirely.

### 6.3 Camera (accurate and controllable)

- The path view **reuses the model-view turntable camera** (center-locked orbit around the printed-part centre, pinch zoom anchored at the pinch centroid, two-finger pan on a plane, double-tap reset) — one camera model across Model/Layers/Path.
- **New:** animated **Fit** control (frame the whole path with one tap), a **zoom step indicator** ("1.6×"), and a **Perspective / Ortho toggle** — ortho is the measurement mode (true line widths on-screen, no perspective distortion).
- **Selection + focus:** tap a move → bead highlights, a small inspector shows X-range, width, height, flow, speed (as mocked); double-tap focuses/zooms to it; "Focus" button re-centers.
- **Clipping guarantee:** near plane derived from path bounds so the print never clips while orbiting; camera distances clamped to fit.
- **Performance:** layer path uploads once to a GPU VBO; shading is per-fragment in a shader; layer scrub re-uses the buffer (only selected-bead overlay updates).

## 7. Behavior & safety

- **Toasts → inline status** where it matters (slice result, restore result, Smart Infill state) — the status strip owns transient copies; toasts remain only for background outcomes.
- **Destructive actions keep confirmation**, and Clear plate / Cancel print become explicitly danger-styled in their contexts.
- **Disabled-with-reason stays** (e.g. "Slice a model first to export validated G-code") — the cheap, honest affordance for a single-threaded engine.
- **Data model untouched:** profiles, imports, overrides, machine values, layer events, Smart Infill/BumpMesh filaSim flows, G-code validation — none of the semantics change; this proposal is pure presentation + navigation.
- Back behavior (Android back / gesture) closes a full screen to Plate instead of dismissing a sheet; sheets (model tools, layer events, non-planar, conical, Smart Infill) remain modal because they are quick interactions.

## 8. What we are NOT changing

- CuraEngine, SlicerSettings semantics, profile/import machinery, G-code validation, OctoPrint protocol/auth, BumpMesh, filaSim/Smart Infill, layer-event model.
- The style guide rules that already work (4 px grid, 48 dp touch targets, three button tiers, locale-safe numbers).

## 9. Implementation plan (for review)

| Phase | Scope | Verification |
|---|---|---|
| P1 | Theme tokens + bottom nav shell (Plate/Settings/Print/More) replacing top-bar menu; keep current sheets initially | existing unit tests + manual smoke test |
| P2 | Plate HUD rework: model chip, status strip, action bar (Tools/Slice/Export); menu items relocated | manual + existing tests |
| P3 | Settings: sheet → full screen with search, chips, origin badges, collapsed summaries | unit tests on settings resolution remain green |
| P4 | Print: sheet → full screen (connection/job/temps/camera/files) | existing OctoPrint tests + manual |
| P5 | More hub + Printer screen (safety checklist) + Onboarding (first-run, skipable) | manual on phone/foldable |
| P6 | Foldable split layout (reuse of WindowSizeClass) | EMU at folded/unfolded sizes |
| P7 | Nozzle-path renderer: analytic normals, 3-light rig, AO + contact, ortho/persp camera, Fit, selection inspector | done (see round 2 status below); verify visually on-device when convenient |

Each phase lands independently; the app remains usable after every one. The viewer changes (P7) are the largest single piece and are isolated behind the existing NozzlePathView/LayerPreviewView boundaries.

## 10. Open questions for you

1. **Dark-first OK?** The proposal pins a dark engineering-cockpit theme and drops wallpaper dynamic colour. Light theme as a follow-up toggle, or ship light+dark from the start?
2. **4 tabs** (Plate / Settings / Print / More) — good, or do you want Printer as a 5th tab and "More" absorbing Profiles?
3. **Onboarding on first run** — welcome, or keep skipping it and only add the Printer safety checklist?
4. **Ortho toggle for the path view** — useful for you, or is perspective-only fine?
5. Any screens/menus to add to the set (e.g. Smart Infill sheet in the new language, profile management details, BumpMesh flow)?
---

## 11. Implementation status (tracking)

Updated as phases land. Verification: \:app:compileDebugKotlin\ + \:app:testDebugUnitTest\
(must stay green after every phase).

| Phase | Scope | Status |
|---|---|---|
| P1 | Theme tokens + brand palette (light+dark), EnderSlicerDimens kept | ✅ land |
| P2 | Bottom navigation Plate/Settings/Print/More; top-bar Import + Plate overflow; More hub (profiles, printer, snapshot, BumpMesh, Smart Infill, non-planar/conical, mesh limit, about); AppIcons brand glyphs | ✅ land |
| P3 | Settings sheet → full-screen tab (search/chips/origin badges still follow-up inside the screen) | ✅ tab land; search + badges polish pending |
| P4 | OctoPrint sheet → full-screen Print tab | ✅ land |
| P5 | More hub + Printer screen upgrade (safety checklist) + first-run onboarding | ✅ land |
| P6 | Foldable split (viewer + SessionPanel at 600 dp+) | ✅ land |
| P7 | Nozzle-path renderer: per-face analytic normals + 3-light rig + per-vertex AO + MSAA; ortho/perspective camera; Fit; zoom indicator; tap-to-inspect; shared flow-width resolver (unit-tested) | ✅ land |

Model-viewer orbit restore across tab switches was added as part of P2
(\ModelSurfaceView.restoreOrientation\), as the tab shell disposes and
recreates the GL surface view.

### Round 5 diff summary

- Engine selection replaces "profile combining" as the product decision:
  the user picks Cura or PrusaSlicer (Settings > Slicing engine), each with
  its own theme accent (Cura blue / PrusaSlicer orange), its own profile
  formats, G-code dialect and binary. Never merge profiles across engines.

### Round 4 diff summary

- \`StlMesh.kt\` - VertexData: meshes >= 200k triangles parsed into direct
  native buffers (off the Java heap); array path unchanged for small meshes.
- \`StlParser.kt\` - binary STL parse writes into the direct buffer directly.
- \`StlMeshWriter.kt\`, \`ModelSurfaceView.kt\`, transforms/tests adapted to VertexData.
- \`EnderSlicerApp.kt\` - bottom Slice bar hidden at >= 600 dp (session pane owns
  actions) - no more duplicate Slice buttons on foldable/tablet widths.

### Round 3 diff summary

- \`PrinterScreen.kt\` (new) - safety checklist (persistent store) + MachineSettingsContent.
- \`Onboarding.kt\` (new) - one-shot, skippable first-run machine setup.
- \`MainActivity.kt\` - onboarding gate before the integrated app.
- \`EnderSlicerApp.kt\` - Printer destination with back navigation, foldable split,
  SessionPanel (summary chips, quick settings, actions).
- \`MachineSettingsSheet.kt\` - became MachineSettingsContent (the modal-sheet
  wrapper is gone; the profile content is now reached via the Printer screen).

### Round 1 diff summary

- \EnderSlicerTheme.kt\ — pinned brand color schemes.
- \AppIcons.kt\ (new) — brand glyph set, no icons-extended dependency.
- \EnderSlicerApp.kt\ — tab shell, Import + Plate overflow menus, More hub,
  ActionBar kept, all existing sheets kept.
- \IntegratedEnderSlicerApp.kt\ — Smart Infill row + Print tab content slot;
  OctoPrint sheet removed.
- \ModelSurfaceView.kt\ — \restoreOrientation\ + renderer \setOrientation\.
- \docs/ui-style-guide.md\ — updated to the new navigation/theme conventions.
- \CHANGELOG.md\ — Unreleased entry.
