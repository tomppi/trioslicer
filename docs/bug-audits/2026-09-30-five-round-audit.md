# Five rounds of auditing, September 2026

This is the record of the bug hunt that produced the 1.6.4 fixes: one audit of the whole app by
five separate auditors, then five further rounds that audited the fixes themselves. Every finding
is listed with what it was, what fixed it, and - where there is none - why no test can reach it.

The short version: the first pass found 23 defects, the follow-up rounds found 23 more, and three
of those were regressions introduced by the first pass's own repairs. A fix is not done when it
compiles and its test is green; it is done when someone who did not write it has read it looking
for what it broke.

## Round 0: the five-auditor hunt (23 findings, all fixed)

Five auditors read the app in sequence, each briefed on what the previous one had found so they
would not report the same thing twice. Commits `b04b2818`, `569cf7ab`, `19e8fec4`, `185846e3`,
`c44dbb9c`, `da552a7a`, `78344021`, `2e701607`, `737588c1`, `e989510c`, `26ffe4a7`, `a92e5e1c`.

The most serious: a print started a minute after connecting was never sent (the read timeout was
treated as end-of-connection); uploads were reported as refused when the host had accepted them;
configuration changes were written into the app's own copy of printer.cfg whichever host was
printing; the G-code flavour had no control at all, so a Klipper printer was sent Marlin's start
script with its UBL commands.

## Round 1: an audit of the fixes (8 findings, all fixed)

- **The restart left no host at all.** The claim that keeps two starts from racing was held for
  the whole life of a run, because the function holding it blocks reading klippy's output until
  klippy exits. A restart stopped the printer's host and started nothing - worse than the silence
  it replaced. `61764934`
- **A launch could outrun `onDestroy`**: a host nothing could stop, holding the printer, with the
  phone pinned awake. `ea9e99bf`
- **A sealed API key this device cannot open was sent as the key**, and the next save re-sealed
  the blob and destroyed it. `ApiKeySealingTest` asserted that behaviour - a test that locked the
  bug in. `61764934`
- **The conical stage file leaked** whenever walk 1 refused a file: a full copy of the G-code,
  unclosed, beside the output. `f975916a`
- **Two configuration writers shared one cache file**, so one could upload the other's
  configuration with a restart behind it. `f975916a`
- **A failed HTTP request never disconnected its socket.** `ed45172a`
- **Two of the upload tests could not fail**: one compared a function with its own definition,
  the other asserted a constant. Replaced by a hand-written expectation of the multipart bytes
  and a 512 MB sparse-file upload, larger than the heap a test is given. `f975916a`
- **The layer tracker was not reset by `G28` or `G92 Z`**, so the next rise was computed across
  the frame change. What a `G92 Z` means for a layer height is genuinely ambiguous, so this is
  the consistent choice rather than a provably right one, and no test discriminates. `ea9e99bf`

## Round 2: audited by hand (2 findings)

The agent wedged after three rounds without reporting, and its four areas were read directly.

- **The conical transform leaked its output file too**, not only the stage file: one file was
  fixed and its twin missed. `33a9f42f`
- **Start and Stop are disabled on the PC route with nothing on screen saying why.** `33a9f42f`
- Clean, having looked: the websocket's 8 MiB bound is far above anything this app asks for over
  that socket, and a disabled Compose button cannot be reached another way.

## Round 3: the sync feature (5 findings, all fixed)

- **A computer with no `printer.cfg` yet was reported as "did not answer"**, and with no
  differences to show the card offered no buttons at all - a dead end on the first sync a new
  host needs. The write path had been taught that distinction; the sync path still collapsed it.
  `ac7ee364`
- **The sync could overwrite an edit made between its read and its write.** It re-reads the
  target immediately before the replace and refuses if it changed. `c3cac9ec`
- **Copying to the computer restarted it unconditionally**, ending a print there. It asks the
  host for its print state over Moonraker's own HTTP query and refuses while printing. `c3cac9ec`
- **`writeRemoteConfig` leaked both temp files** - the twin of the `writeHostConfig` fix - and
  discarded the result of the backup upload. `ac7ee364`
- **The difference list was built in the writer's order** rather than the preview's: a future
  trap, one token to fix. `ac7ee364`

## Round 4: the print path, audited by hand (clean)

The agent wedged again; the five questions were answered by reading the code.

- An upload that succeeds and a start that fails produces an error naming the truth: the file is
  on the host, print it from Files, which does not upload it again.
- The reconnect cannot start a second print: it happens before the command is sent.
- Deleting a file that is printing is benign - the open handle keeps the inode alive on Linux.
- A cancel leaves nothing stale: the state follows klippy's `print_stats`, not the app's belief.
- A host switch cannot act on the wrong machine: every action resolves the host when it runs.

## Round 5: the import path (8 findings)

The least-defended area in the app, and the sharpest round.

- **A value could carry a key.** The config writer appends values into ini lines and field
  strings were only trimmed, so a value containing a line break added a key of its own - and a
  later duplicate wins. This was the real blocked-key bypass. `4510cfa7`
- **A Boolean was a number**: `"layer_height": true` imported as 1 mm. `4510cfa7`
- **An unknown boolean token became false**: `thin_walls = yes` turned the setting off. `4510cfa7`
- **`bed_shape = 100x100` computed a zero-sized machine**, surfacing at slice time. `4510cfa7`
- **An unreadable setting catalogue dropped every override in silence** and reported success.
  `c3ce36c4`
- **A file that was not a PrusaSlicer configuration was installed over the user's settings.**
  `c3ce36c4`
- **The generated ini was written in place**, so an interrupted write left a truncated
  configuration for the engine to read. It is written whole or not at all. `c3ce36c4`
- **No clamping of imported numbers** - partly fixed, with the reason recorded. The audit asked
  for values to be clamped to the catalogue's minimum and maximum; implementing that made a
  round-trip test - the writer's own output read back by the importer - refuse the app's own
  `ironing_angle` sentinel. Those bounds are the setting screen's slider range, not a validity
  contract the engine shares. What is enforced instead is the one number no engine can act on: a
  layer height of zero. Speeds and temperatures the firmware refuses, or the printer's own limits
  clamp, are left to the machine that owns them. `c3ce36c4`

## What has no test, and why

Seven findings were fixed without one, each because the thing to be tested cannot exist in a JVM
test: the service that owns a real klippy process, the Compose guards on a screen, the GL
surfaces, the accelerometer, and the Android Keystore. Each is named in its commit.

One change is reviewed rather than pinned: the streaming upload sends byte-identical output to
the version it replaced, so what changed - the memory it holds - is invisible on the wire. A
sparse-file test is the closest a JVM test can come, and it is in `UploadStreamingTest`.

## The pattern worth keeping

Five of the fixes and four of the tests written for them were incomplete in the same way: correct
where they looked, wrong or empty one step to the side. The conical stage file but not the output
file. One writer's temp files but not the other's. The write path's "no config yet" but not the
sync's. A test whose fixture made it pass for the wrong reason. None of that was visible in a
green test suite, and all of it was visible to a reader going over the same ground with a
different question.
