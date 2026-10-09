# CAD engine: known low-severity findings

Recorded from the five-agent bug hunt (2026-10-09) so that the next hunt does not spend its
budget rediscovering them. Each was verified against the code by the session that ran the hunt;
none is fixed. They are latent or cosmetic - nothing here misleads the user on its own.

**Give this list to any agent hunting the CAD engine.** If a finding is below, it is known.

| # | where | what | why it stays low |
|---|---|---|---|
| L1 | `cad_mcp_slim.py` `_pick_at` (~1023) | the pick echoes the coordinates it was given *after* scaling them by the supersampling factor, so a tap at (100, 100) answers x=200, y=200 | the app now scales the answer back (CadViewport), and the face index - the part that matters - was always computed from the same scaled point |
| L2 | `CadPreviewClient.readReply` (~161) | completeness is "last byte is }" plus "org.json parses", and org.json ignores trailing content, so one reply followed by a fragment would be accepted | no path emits two replies per connection: one command at a time under one lock, one reply per request. A robustness gap, not a demonstrated fault |
| L3 | `CadEngine.start`/`stop` (~310, ~454) | `start()` is guarded only by a volatile flag assigned 60 s later, so two overlapping starts both pass; the loser's failure path calls `stop()` and can destroy the winner's process | the only caller serialises it (`MainViewModel.cadEngineStarting`), so it is not reachable from the UI today |
| L4 | `cad_mcp_slim.py` ~446-490, 992-1021 | the grid and the edge overlay stay selectable in the same context as the part, so `MoveTo` could detect a grid edge (not in the part's pool → `index: null`, `kind: "edge"`) and the app treats that as a real pick | **UNCONFIRMED** - nobody has run it. One socket call with the grid on, tapping empty bed beside the part, settles it: `"none"` kills it, `"edge"` with a null index confirms it |
| L5 | `CadScreen.kt` ~106, `CadViewport.kt` ~361 | `onPickUsed` is declared and never invoked, and nothing else calls `clearPick()`, so re-tapping the same face at the same pixel stores an equal value in the StateFlow and emits nothing | the user sees no overlay for that one re-pick; any different pixel works |
| L6 | `docs/skills/cad-engine.md` ~139, `fetch-cad-payload-android.sh` ~14 | the doc says replies are capped at 1 MiB (that is the Blender client's constant; the CAD client's is 8 MiB), and the fetch script's comment names tag `v1.0.0` while it downloads `v1.1.1` | documentation only; the numbers a caller needs are in the code |

Two more that are *not* bugs but are worth knowing when reading the engine:

- `view` silently substitutes `iso` for an unknown `orientation` and does not echo what it used,
  where `render` raises for an unknown `view`. A caller cannot tell "iso because I asked" from
  "iso because you asked for nonsense".
- `import_file` returns the shape name under `_summary`'s `"name"`, while
  `CadPreviewClient.importFile` reads `"shapes"`, so that documented return value is always null.
  Nothing acts on it today.
