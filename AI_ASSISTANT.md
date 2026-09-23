# AI assistant: chat, paint-to-guide, and photo-to-3D

TrioSlicer can host an AI assistant that talks to a **DeepSeek harness** running on a desktop machine. The assistant can read your model, act on a region you paint onto it, and - the headline capability - **turn a photograph into a printable STL that lands on the build plate by itself**.

This document explains what the pieces are, why the work is split the way it is, and the protocol details that are not guessable from the outside.

> **Addresses here are placeholders that say what belongs there** - `<phone-tailscale-ip>`, `<gpu-box-lan-ip>` and so on, so you are told what to supply rather than handed an example that looks real and will not work. The full list is in [`docs/skills/README.md`](docs/skills/README.md). The one literal range is `100.64.0.0/10`, Tailscale's CGNAT space, named because the range itself is what matters.

## The shape of it

```text
   phone (TrioSlicer)              desktop (harness host)          GPU box
 ┌────────────────────┐         ┌──────────────────────┐       ┌──────────────────┐
 │  AI chat overlay   │  HTTPS  │   dsh harness        │  ssh  │  image-to-3D     │
 │  "Build from image"├────────►│   agent + skills     ├──────►│  generator       │
 │                    │  tailnet│                      │       │  (8 GB GPU)      │
 │  export poller     │         └──────────────────────┘       └──────────────────┘
 │  (every 500 ms)    │◄──────────── STL written to the app's exports dir ────────┘
 └────────────────────┘                    (via adb, from the desktop)
```

The phone never talks to the GPU box. It uploads a photo, asks for a model, and waits - the finished mesh arrives through the same export folder the Blender engine already uses, so the hot-load path is shared rather than duplicated.

## Why the work happens elsewhere

Generating a detailed mesh is a multi-gigabyte, GPU-bound job - the pipeline peaks around **5.7 GB of VRAM** and takes two to four minutes. That does not fit on a phone, and does not belong on one. The phone is a **client**: it captures the image, holds the conversation, and displays the result.

That split is the whole design. It also means the assistant's capabilities are bounded by what the harness and its skills can reach - not by what ships in the APK. Adding an ability means writing a skill on the desktop, not shipping a new app build.

## The three machines

| role | what it runs | reached by |
|---|---|---|
| Phone | TrioSlicer | - |
| Harness host | the `dsh` harness, its agent, and the skill files | HTTPS over a tailnet |
| GPU box | the generator, reachable only by ssh | from the harness host |

The harness is addressed by its **tailnet name** (`https://harness-host.example.ts.net`), not a raw address: the app reaches it over TLS, and a bare `100.x` address fails certificate validation. A harness on the same LAN can be addressed directly.

## The harness has its own interface

The harness serves a complete web UI, and the app's chat overlay is deliberately only a slice of it - enough to ask for a model and watch it arrive, without reimplementing a whole chat client on a phone.

For everything else - browsing sessions, reading a full transcript, watching a long turn stream live - [`webviewdp`](https://github.com/tomppi/webviewdp) wraps that UI in a minimal Android WebView app. It takes the same `?token=` launch URL described below, keeps the 30-day cookie the exchange sets, keeps navigation inside the WebView, and reloads itself when Android reclaims the renderer process in the background.

Two views of one harness: the overlay for the task at hand, the wrapper for everything else.

---

# The app side

## The chat overlay

A floating, draggable panel on the **Plate** tab - deliberately not a bottom sheet, because the user paints onto the model to show the assistant what to work on, and a sheet would cover the model and dismiss on the first tap outside it. It collapses to a bubble so the plate stays usable.

Two ways to ask:

- **Type** - a normal message.
- **Build from image** - pick a photo; it is uploaded and a prompt asks for a model.

A **Stop** button appears while a turn is running. It matters more than it looks: see *Semantics that bite* below.

## Sessions

One conversation is one harness session. Three rules keep it from fragmenting:

**A session is created rooted at the workspace.** Skills are discovered from the session's working directory, so a session created without one lands in the harness's own directory and cannot see them - the agent then improvises a pipeline instead of following the documented one, and the failure is silent. Every session the app creates carries the workspace.

**A stored session is verified before it is adopted.** `session/prompt` accepts an id the harness has never heard of and then answers nothing, so a stale id gives a chat that looks connected and stays silent forever. The app checks the id against the session list and creates a replacement deliberately if it is gone.

**The conversation is rebuilt when the app is recreated.** A rotation - or a dark-mode change, or the process being killed - destroys every in-memory value while the stored configuration survives. The app reconnects when the chat is opened with no live client, so the conversation comes back instead of showing an empty chat with no way to reconnect it.

## Reading the answer

The session list carries a **projection** of each turn, and it is a *preview*: every prompt and response is clipped to roughly a hundred characters. That is enough to drive a chat and nowhere near enough to read an answer that runs to several thousand.

The full text comes from a different call. Two details decide whether it works:

- **The read needs a log cut.** `session/page` takes `throughSeq`, and the value is `projections.asOfSeq` from the session list. Passing `-1` - the obvious "latest" - quietly returns **zero records** rather than erroring.
- **Only `text` blocks are the reply.** An assistant message also carries `reasoning` blocks: the agent's private working. Concatenating every block puts its thinking in the chat. Within a turn, several messages may carry text; the **last** one is the answer.

The app polls the cheap projection while a turn runs, and reads the full log once the answer is in.

---

# The harness protocol

Not guessable, and each mistake produces an unhelpful error.

## Authentication

One exchange, and the user supplies the only copy of the secret:

```text
GET  <origin>/?token=<launch token>   303 -> Set-Cookie, valid 30 days
```

**The launch token is pasted, never published.** The server mints it at startup and prints one `?token=` URL per authority; the launcher shows those URLs, and `dsh-url.sh` (`dsh-url.ps1` beside the Windows launcher) prints them again on demand by reading the launcher's log. Paste the URL into the app's address field: the token is stripped off the address, encrypted with a device-bound Keystore key, and used to redeem the session cookie.

The cookie is valid for 30 days and outlives harness restarts, so it is what the app keeps and reuses; the token is not needed again until the cookie expires or the app's data is cleared. The cookie is encrypted the same way, and both live in `harness_config` - a file excluded from cloud backup and device transfer, so no copy of the ciphertext exists off the device.

**Why nothing is published.** Up to 1.3.5 the launcher wrote `auth.json` - the per-authority `?token=` URLs - into the harness's served dist, and the app fetched it on a 401. Every file in that dist is public: anything that could reach the port (any device on the tailnet, any local process) could read a working sign-in for a harness that runs code on its host. The launcher no longer writes it and the app no longer reads it; `DSH_PUBLISH_AUTH_JSON=1` (or `-PublishAuthJson`) restores the old file only for a 1.3.5-or-earlier client that cannot paste the URL.

The harness still publishes `defaults.json` (the suggested workspace path) in the same dist. That is a path, not a credential, and a fresh install needs it to root its first session where the skills are.

## The request envelope

Every call, without exception:

```json
POST /api/<endpoint>            endpoint with SLASHES, not dots
Content-Type: application/json
Cookie: <from the token exchange>

{ "type": "client-request",
  "rpcId": "<uuid>",
  "method": "<endpoint>",
  "payload": { "args": { "<field>": { ... } } } }
```

Three things that are easy to get wrong:

- **Dots 404.** `/api/session.list` does not exist; `/api/session/list` does.
- **The payload is double-wrapped**, and the args field is named per endpoint: `_request` for `session/list`, `request` for `create`, `prompt`, `cancel` and `page`.
- **Nothing is returned unless `result.ok`** - failures arrive beside it as a structured error, not as an HTTP status.

## The endpoints used

| endpoint | args field | request | returns |
|---|---|---|---|
| `session/list` | `_request` | `{}` | every session with its projections |
| `session/create` | `request` | `{ cwd? }` | `{ sessionId }` |
| `session/prompt` | `request` | see below | `{ accepted: true }` |
| `session/cancel` | `request` | `{ sessionId }` | `{ accepted: true }` |
| `session/page` | `request` | `{ address, throughSeq, maxMessages? }` | `{ records, hasMore }` |
| `session/uploadFileBinary` | *query string* | raw octet-stream | `{ ok, value: { receiptId } }` |

A prompt is:

```json
{ "requestId": "<uuid, client-minted, REQUIRED>",
  "sessionId": "session-...",
  "mode": "queue",
  "content": [
    { "type": "file", "receiptId": "<from an upload>" },
    { "type": "text", "text": "..." }
  ] }
```

## Semantics that bite

**`requestId` is client-minted and required.** Omitting it fails boundary validation *without naming the missing field*.

**Uploading is not sending.** Bytes staged by `uploadFileBinary` reach the agent only through a `file` content part naming the receipt. Upload without that part and the agent receives a text-only message that merely *mentions* an image - it will go looking for the file on disk, and may or may not find it. Receipts are single-use and scoped to the session they were uploaded against.

**Cancelling stops the agent, never the work.** `session/cancel` ends the turn; the processes the agent started over ssh keep running, orphaned, still holding VRAM. Worse, a **background job that finishes afterwards delivers a completion notice, and a notice into a session with no live turn starts a new one** - so a bare cancel can be undone a minute later. Stopping properly means cancelling *and then* telling the agent to drop its jobs.

**The projection is not the log.** See *Reading the answer*.

---

# Photo to 3D

The runbooks the assistant follows are published, sanitized, in [`docs/skills/`](docs/skills/) - see [`docs/skills/README.md`](docs/skills/README.md) for what was substituted and why.

## The runbook

The order is not a suggestion. Generating before the box is awake looks like a hang; hibernating before the model is confirmed loses it.

1. **Wake the GPU box** and confirm it answers.
2. **Prepare the image** - apply EXIF rotation; a phone photo is frequently sideways and the generator faithfully produces a sideways model.
3. **Generate** with Hunyuan3D-2mini at DMC 512³.
4. **Post-process** - scale to a target height and reorient (`--from-y-up`; the generators disagree about up-axis).
5. **Validate** - expect watertight, with no open, over or degenerate edges.
6. **Deliver to the phone.**
7. **Confirm it is on the plate, then hibernate.**

## The generator

**Hunyuan3D-2mini**, not TripoSR. TripoSR is faster but has a hard detail ceiling, and **raising its resolution does not help**: measured on one photograph, 320³, 512³ and 768³ produced the same shape, because its triplane is capped long before the extraction grid is. Within Hunyuan, **DMC at 512³** is the default - it roughly doubles the triangle count on the same source, which is what thin ribs and small features need.

The generator is used in its **VRAM-offloading** form, which moves the denoiser and conditioner to system memory between stages. That is what makes 512³ fit on an 8 GB card: measured at the extraction stage, **999 MiB free before the offload and 6341 MiB after**.

Triangle count is driven by the **subject**, not the settings. The same pipeline has produced 644,596 triangles on a diffuser and 1,044,576 on an earbud. Counts are not a target and must not be trimmed to match an expectation - the app's mesh limit is a setting the user controls.

**Background removal is the step that decides what the model is even looking at.** There is no language or reasoning anywhere in the chain: saliency segmentation picks whichever object is most salient, so two objects in frame, a busy background, or a too-tight crop all produce a clean, watertight, *wrong* mesh. Checking the mask before spending GPU time costs seconds and catches this; a full run costs minutes.

## The handoff to the phone

The app hot-loads from its own private directory, so the file must be copied in - the shared sdcard is not enough:

```text
push <file>.stl to /sdcard/Download/
copy it into <app>/files/blender/exports/
chown it to the app's uid and chmod 600
```

**The filename must be new every time.** The app dedupes by path, size and mtime and dispatches each revision exactly once, so reusing a name can be silently ignored.

**The ownership step is not optional.** A root copy lands owned by `root`, and the app cannot read it; the import then fails with no error reported anywhere. Verify with `ls -la` - the owner must be the app's uid, not `root`.

## Why a human has to look at the result

Shape generation is a learned mapping from appearance to form. It will produce a confident, clean, watertight mesh of something that is not what was photographed, and **nothing in the pipeline flags it** - not the validator, not the generator. Rendering and looking at the result is a required step, not a nicety.

---

# Powering the GPU box

A machine that exists to be used occasionally should not idle for weeks, so it hibernates between runs and is woken on demand.

## Waking it

A magic packet is a **layer-2 broadcast and does not route**, so the sender must sit on the same physical segment as the box's ethernet port. In this setup a small always-on single-board computer does the sending:

```text
SBC ──ethernet──► bridge (no DHCP of its own) ──► GPU box (runs the DHCP server)
```

The bridge must **bridge rather than route**, which is proved by the SBC taking a lease from the box's own DHCP server rather than an address of its own. If that ever changes, the wake stops working silently.

**The failure worth knowing about:** the packet is sent *from* an address the SBC obtains from the box's DHCP server - the machine that is asleep when you need it. An SBC that rebooted while the box slept has no address on that interface, the bind fails, and **no packet is sent while the sending script still reports success**. The recovery is to re-add the address by hand and re-send; the durable fix is a static address on the SBC, which is a configuration change rather than a code one.

The configuration that makes waking work at all is four things, and all four are needed: BIOS wake-on-PCIe enabled (and ErP power-saving *disabled*, which otherwise cuts standby power to the slots), the NIC's wake-on-LAN setting, a boot-time service that enables the PCI wake flags, and a sleep hook that re-arms them **after every resume** - the flags reset on both boot and resume, and a udev rule alone misses devices whose attributes do not exist yet.

## Hibernating it

**Resume is unreliable - expect a cold boot.** Roughly a third of hibernations fail to restore and the machine boots fresh instead, losing everything in RAM. Neither outcome should be promised to a user as a session that survives.

The likely cause is a hibernation image that does not fit its target cleanly: the resume target is smaller than RAM, and a second swap area at the same priority means which area receives the image is not pinned to the one the kernel is told to resume from. The real fix is a dedicated swap partition larger than RAM - a partitioning job, not a kernel-argument tweak.

**Never hibernate under a running generation.** Attempting it freezes processes, fails, and resumes with the GPU context destroyed: the generation process keeps spinning on one CPU core while the GPU sits at 0%, and it will never finish. Check for running generation processes first, and confirm the machine actually went down before expecting a wake.

---

# Security

- **The harness is reachable only over the tailnet**, and the app addresses it by its tailnet name so the connection is TLS-validated.
- **The launch token is the only credential the app is given, and it is encrypted at rest.** A pasted harness URL keeps its `?token=` value, which is sealed with a non-exportable Android Keystore key together with the session cookie the exchange mints; both are dropped when the configured address changes, because a credential from one harness means nothing to another. The ciphertext and key an older build wrote and nothing read are still deleted on the first save.
- **Plain HTTP is refused unless the user accepts it for that address.** A launch token and a 30-day cookie over the network in the clear is a decision, not a default: the app asks once per non-loopback `http://` address, remembers the answer for that address only, and keeps an `Unencrypted connection` notice on the chat while it is in use.
- **Root is used on the phone for the export handoff only** - copying into the app's private directory and setting ownership.
- **The harness host holds ssh credentials for the GPU box.** Anything that can drive the harness can drive the box; treat harness access as equivalent to shell access on both machines.
- **Skills are executable instructions.** The agent follows the markdown in the workspace, so the skill directory is as sensitive as the credentials beside it.

# Limitations

- The assistant needs a harness and (for photo-to-3D) a GPU box; neither is bundled, and the app is a client to both.
- Replies are read from a polling projection, not a stream, so a long answer appears when the turn ends rather than as it is written.
- The chat window shows full text for the most recent messages; older turns fall back to the truncated preview.
- Image-to-3D quality is bounded by the photograph: one subject, generous framing and a contrasting background.
- The generated mesh is a plausible solid, not a checked one. Inspect every model before printing.
