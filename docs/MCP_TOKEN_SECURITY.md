# The MCP engine tokens

Two of the app's engines are driven over a loopback socket by an agent that is not the app: the
Blender engine on **9876** and the CAD engine on **9877**. Each refuses every request - `ping`
included - without a shared secret, and each keeps that secret in a plain file in the app's private
storage. This document is that boundary: what the token is, what holding one grants, how to check
one, how it rotates, and what was deliberately not built. It is a **bearer credential**. There is
no second factor, no signature and no expiry; whoever holds the value can do everything it
authorizes.

## 1. What the token is

| engine | file | port |
|---|---|---|
| Blender | `files/blender/scripts/startup/blender_mcp_token.txt` (`BlenderEngine.kt:66`, `:260`) | 9876 (`BlenderEngine.kt:41`) |
| CAD | `files/cad/cad_mcp_token.txt` (`CadEngine.kt:48`, `:132`) | 9877 (`CadEngine.kt:43-44`) |

`files/` is the app's private directory, `/data/user/0/com.tomppi.enderslicercura/files` on the
device. The app writes the file when it is missing and reuses the value otherwise
(`BlenderEngine.ensureToken`, `BlenderEngine.kt:247-258`; `CadEngine.ensureToken`,
`CadEngine.kt:141-152`), always **before** the engine starts (`BlenderEngine.kt:143`, before
`BlenderBridge.start` at `:155`; `CadEngine.prepare`, `:198-207`, before the process at
`:395`). The value is `UUID.randomUUID()` with the dashes removed: 32 lowercase hex characters,
122 random bits (`BlenderEngine.kt:253`, `CadEngine.kt:147`). Nothing chmods it explicitly; the
app's private directory is 0700 and the file 0600 - the app's umask, observed on the dev phone as
`-rw------- u0_a252 u0_a252 32`, and recorded in `docs/skills/cad-engine.md:32-34`.

Each engine reads the file once at startup, before it opens the port
(`start_blender_mcp.py:76-88`, called at `:127`; `cad_mcp_slim.py:2143-2154`, called at
`:2211`), and refuses to serve without a token: it records `no MCP token loaded; refusing to
serve` and never binds (`blender_mcp_slim.py:181-192`, `cad_mcp_slim.py:1163-1174`). The app
fails closed too: if it cannot write the token it does not start the engine
(`BlenderEngine.kt:143-147`, `CadEngine.kt:201-204`).

## 2. What holding it grants

Every request is authorized before it is dispatched, and a missing or wrong token is answered
`unauthorized: send the token from <file>` (`blender_mcp_slim.py:307-321`,
`cad_mcp_slim.py:1315-1329`). With the token, `execute_code` runs Python with no further
restriction - `exec(code, namespace)`, with `os` in the namespace beside the modelling library
(`blender_mcp_slim.py:486-498`; `cad_mcp_slim.py:1431-1464`). The Blender engine runs in the
app process as a loaded library (`CadEngine.kt:24-30`, `BlenderBridge.kt:61-77`), so that Python
is the app's own process and uid; the CAD engine is a child process started as the app, with no
uid switch (`CadEngine.kt:395-406`). **A token is code execution as TrioSlicer.** It is not
"can make models".

Because that code is the app's uid, it reaches:

- the app's whole private storage, the two token files included, and `shared_prefs` beside it;
- the working directories: `files/models/` (`MainViewModel.kt:706`), `files/slice-results/`
  (`SliceArtifactPublisher.kt:155`), `files/gcodes/` (`KlipperHostFiles.kt:60`, `:68`), and
  the Blender import/export handoff (`docs/skills/blender-mcp-engine.md:102`);
- the network: the app holds `INTERNET` (`AndroidManifest.xml:3`) and Android grants it by uid;
- subprocesses: `os` is in the namespace, so `os.system` and `subprocess` are a line away;
- anything else the app may do as itself. **The boundary is the uid, not the interpreter.**

What it does not reach: another app's private data, and the system. Crossing that needs root. The
token does not grant root, and the engine process is not root.

One qualification, because this is where an honest model stops: `execute_code` can spawn helpers,
so the app's own entitlements come with the uid. This document does not claim the Keystore-backed
credentials in `shared_prefs` are out of reach; it claims only that they are not a file an
`open()` reads.

## 3. Blast radius, bounded by the manifest

The app declares exactly these permissions (`AndroidManifest.xml:3-20`):

    INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, WAKE_LOCK,
    USE_BIOMETRIC, HIGH_SAMPLING_RATE_SENSORS

There is **no storage permission**: no `READ_EXTERNAL_STORAGE`, no `WRITE_EXTERNAL_STORAGE`,
no `MANAGE_EXTERNAL_STORAGE`, no `READ_MEDIA_*`. That is the bound on the token. Its holder
gets the app's private data and the network; it does not get the shared-storage tree, other apps'
data, or anything else that needs a permission the app has not asked for. The foreground service
is why the socket survives the screen going off, which is the same reason the app can be driven
with the phone locked.

## 4. Why a plain file is acceptable

Four things are true at once; none of them is "the secret is well protected":

- **The file is in the app's private directory.** The kernel's uid isolation and SELinux keep every
  other app out; the directory is 0700 and the file 0600, owned by the app's uid
  (`-rw-------`). Crossing that needs root.
- **The socket is loopback-only.** Blender binds `localhost` (`BlenderEngine.kt:138-139`,
  `:159`; `native/blender/blender_exec.cpp:48`; `start_blender_mcp.py:111`); CAD defaults to
  it and nothing sets `CAD_MCP_HOST` (`cad_mcp_slim.py:2199`). Off the device the port is
  reachable only through an `adb forward`.
- **The engine only listens while it runs.** It binds in `start()`
  (`blender_mcp_slim.py:195-198`, `cad_mcp_slim.py:1177-1180`) and closes the socket when it
  stops (`blender_mcp_slim.py:293-298`; the CAD process is destroyed, `CadEngine.kt:518-530`).
- **The app can stop it.** *Stop Blender engine* (`EnderSlicerApp.kt:1333-1338` →
  `BlenderEngine.shutdown`, `:193-207`) and *Stop CAD engine* (`EnderSlicerApp.kt:1447-1452`
  → `CadEngine.stop`, `:518-530`). The Blender engine is in-process, so its stop closes the
  socket and parks it; the loaded scene stays in memory and only ending the app process releases it
  (`BlenderEngine.kt:193-206`).

Two limits worth stating in the same breath:

- The loopback socket is reachable by **every app on the device**. That is exactly why the token
  exists: without it, any co-installed app could run Python as this app's uid
  (`cad_mcp_slim.py:1163-1166`). The token is what makes that reach useless.
- The token file is no more private than adb. With wireless debugging on, any host the phone has
  trusted gets a shell (`u:r:shell:s0`), and on a build where rooted debugging is enabled adbd is
  already root; either reads the file (`docs/skills/blender-mcp-engine.md:30-37`, `:52-54`).

## 5. How it is read, and by whom

- **Root, or a root-capable adb.** `adb shell su -c 'cat <path>'` on the dev phone, and a plain
  `cat` where adbd already runs as root (`docs/skills/blender-mcp-engine.md:20-22`, `:44-45`;
  `docs/skills/cad-engine.md:93-99`).
- **The user, in the app.** *Blender → Copy MCP token* (`EnderSlicerApp.kt:1345-1366`) and
  *CAD → Copy MCP token* (`EnderSlicerApp.kt:1420-1435`; CAD since 1.8.8, `CHANGELOG.md:19-20`).
  Both go through `requestMcpTokenCopy` (`EnderSlicerApp.kt:2516-2587`), which requires the
  fingerprint or the device credential and copies nothing if that fails; the clip is marked
  sensitive so Android does not preview it (`:2605-2618`).
- **A debug build only.** `adb run-as` reaches the app's files when the APK is debuggable, and the
  released APK is not (`TECHNICAL.md:110-112`).
- **On a non-rooted phone an agent cannot read it at all.** There is no read path an agent can use;
  a human taps *Copy MCP token* and hands the value over, which is the workflow the item exists for.

Neither token can ride off the device in an Auto Backup or a device-to-device transfer: both
`files/blender/` and `files/cad/` are excluded from both backup lists
(`app/src/main/res/xml/backup_rules.xml:32`, `:49`; `.../data_extraction_rules.xml:30`, `:55`,
`:78`, `:94`). That is a backup-policy question rather than a read path an agent can use, and a
restore leaves neither engine tokenless: both trees are extracted from the APK's assets on the new
device, and the token beside each is written anew.

## 6. How to check a token

`^[0-9a-f]{32}$` is a paste sanity check and nothing more. The engine compares the strings
(`command.get("token") == self.token`, `blender_mcp_slim.py:312`, `cad_mcp_slim.py:1320`).
There is no checksum, no signature and no expiry, so a different-but-well-formed value, a stale
value and the *other* engine's value are indistinguishable by eye. The only authority is the
engine: send a `ping` with the token and read the reply.

```text
→ {"type": "ping", "params": {}, "token": "<token>"}
← {"status": "success", "result": {"pong": true}}        # accepted
← {"status": "error", "message": "unauthorized: send the token from blender_mcp_token.txt"}
```

There is no token-free liveness check: `ping` is authorized like every other command
(`blender_mcp_slim.py:449-452`, `cad_mcp_slim.py:1392-1395`).

The three failures are different and need different fixes:

| what you see | what it means | what to do |
|---|---|---|
| connection refused, nothing listening | the engine is not running - or a hand-run engine found no token file and refused to serve, which it records as `no MCP token loaded; refusing to serve` (`blender_mcp_slim.py:181-192`, `cad_mcp_slim.py:1163-1174`) | start the engine, or write a token beside the hand-run one |
| the port accepts and the reply is `unauthorized` | a wrong value: a typo, a copy from before a rotation, or the other engine's token (9876 vs 9877) | correct the value, not the read |
| the read came back empty or not 32 hex characters | the read failed: wrong path, a permission-denied `cat` whose empty output was taken for a token, a partial paste | fix the read; the engine cannot tell an empty token from a wrong one |

A permission-denied read is the trap to name: a non-root `adb shell` fails the `cat` and every
request then answers `unauthorized`, which looks exactly like a rejected token
(`docs/skills/blender-mcp-engine.md:54-56`).

## 7. Rotation

`ensureToken` creates when the file is missing or empty (`BlenderEngine.kt:247-258`,
`CadEngine.kt:141-152`), so deleting the file and starting the engine again mints a new value.
When that takes effect differs per engine, because the token is read **once per engine process**,
before the serve loop (`start_blender_mcp.py:127`, `cad_mcp_slim.py:2211`), and a parked engine
re-serves with the value it already has:

- **CAD**: *Stop CAD engine* destroys the child process (`CadEngine.kt:518-530`); the next start
  writes and reads the file. Delete the file, stop, start.
- **Blender**: *Stop Blender engine* only parks the in-process engine (`BlenderEngine.kt:193-207`),
  and the serve loop recreates the server with the same token it read at process start
  (`start_blender_mcp.py:140-145`). Delete the file and end the app process - force-stop the app -
  before relaunching.

A copy taken before a real rotation fails `unauthorized` afterwards. Until the engine process is
replaced it keeps working: the value lives in the engine's memory, not only in the file, so
deleting the file revokes nothing by itself.

## 8. Considered and declined

- **A per-session token** would bound what a leaked copy is worth, but the app would have to hand
  the engine a new secret every session and the human would have to copy it again every session -
  and the hand-over is the path that works on a phone without root. It buys a shorter lifetime at
  the cost of the one workflow that works, and it changes nothing about what a holder can do during
  the session.
- **An operation allow-list instead of `execute_code`** has to name every operation the engines
  are for, when their whole value is arbitrary Python - `bpy` for Blender, `build123d`/OCP for
  CAD, and fifteen further named commands beside `execute_code` in CAD
  (`cad_mcp_slim.py:1402-1419`). A list either covers the modelling or leaves an escape hatch, and
  the escape hatch is the vulnerability. It shrinks the engine until it is not worth driving;
  declined as a boundary, not as an idea.
- **Publishing the token through logcat** would let an agent read it without root, and is the one
  option here that moves the trust boundary rather than shrinking the capability. The token would go
  from "the app's private storage, or the person holding the phone after a biometric prompt" to
  "anything that can read the log": the adb shell, every host the phone has trusted for debugging
  (`docs/skills/blender-mcp-engine.md:30-37`), and whatever collects logs afterwards. That is a
  weaker boundary than the file already has, and the non-root path - the biometric copy - exists.
- **Signing tokens so they can be verified offline** proves a value was minted by the app, but there
  is no attack here that forgery enables. An attacker who can write the token file is already the
  app's uid and past the boundary; an attacker who cannot never reaches the comparison. It would add
  a key to manage so the engine could accept a token the file does not contain.

If this is revisited, the per-session token is the one to build first: it is the only option that
reduces what a leaked copy is worth without changing what the engines do, and it needs the re-copy
to stay one tap, which the app already has.
