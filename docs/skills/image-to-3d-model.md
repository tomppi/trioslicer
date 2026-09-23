---
name: image-to-3d-model
description: Turn a photograph into a printable STL using the GPU box's image-to-3D generators (Hunyuan3D-2mini or TripoSR), then deliver it to the enderslicercura app on the phone. Covers waking the box, choosing the generator, post-processing the mesh, and the handoff that makes the model appear in the slicer.
whenToUse: When the app's "Build from image" button asks for a model, when generating an STL from a photo, when a generated model looks like a blob or is oriented wrong, or when a model never appears in the app after an export.
---

# Image → 3D model

A photograph becomes a printable STL, and the STL appears on the phone's build plate.

**Default pipeline: Hunyuan3D-2mini with DMC.** Do not substitute TripoSR unless asked — see "Choosing the generator" for why it cannot reach this level of detail.

**Deliver at full resolution. Do not decimate, and do not cap the triangle count** — the app's mesh limit is a setting the user controls, and detail is the entire reason for choosing this pipeline over a faster one.

## Run in this order

The order is not a suggestion. Generating before the box is awake looks like a hang; hibernating before the model is confirmed loses it.

1. **Wake the box** — follow [gpu-box-power](../gpu-box-power/SKILL.md), and confirm it answers.
2. **Prepare the image** — `prep_photo.py` (EXIF rotation).
3. **Generate** — `gen_hy3d_vram.py` with `--mc-algo dmc`.
4. **Post-process** — `prep_mesh.py --height-mm <h> --from-y-up`.
5. **Validate** — `verify-stl.mjs`; expect watertight.
6. **Deliver to the phone** — unique filename into the app's exports dir.
7. **Confirm it is on the plate**, then **hibernate** — [gpu-box-power](../gpu-box-power/SKILL.md).

Steps 1 and 7 are the other skill, deliberately. The wake configuration is shared with every other reason to start that machine, so it lives in one place; a fix to it must not have to be made twice.

## The chain

```text
image ──► wake the GPU box ──► generate ──► post-process ──► validate
                                                              │
        app shows the model ◄── hot-load poller ◄── exports dir ◄── adb push
                                                              │
                              hibernate the box ◄─────────────┘
```

**Waking and hibernating are a separate skill: [gpu-box-power](../gpu-box-power/SKILL.md).** Read it before touching the box — the wake is reliable, the hibernation resume is not, and the Pi is what sends the packet.

## When the app asks

The app's **Build from image** button uploads the photograph to a harness session and sends a prompt asking for a model. The button closes the loop by itself — no result is fetched back, because the model arrives through the exports directory like any other export. Your only job on that prompt is to run the seven steps above.

### The image is already on this machine

The upload happens **before** the prompt is sent, so by the time you read this the photograph is on disk — under the harness home, not in the workspace:

```text
C:\Users\<you>\.dsh\attachments\v1\files\<2 hex>\<64 hex>\model-source-<epoch>.jpg
```

Newest one is yours:

```powershell
Get-ChildItem "$env:USERPROFILE\.dsh\attachments" -Recurse -Filter 'model-source-*' |
    Sort-Object LastWriteTime | Select-Object -Last 1 -ExpandProperty FullName
```

**Do not go looking for it inside `session.v3.jsonl.zstd`.** That journal is a concatenation of zstd frames — one per record — so decompressing the file returns only the session header and nothing else, and a full decode to find an attachment reference is a dozen wasted steps. The path above is where it is.

If the prompt does not arrive with an image attached, ask for one rather than guessing at a subject.

## Machines and scripts

| | where |
|---|---|
| GPU box `GPU box` | `<gpu-box-lan-ip>`, ssh `<user>`, venv `/home/<user>/img2mesh/venv` |
| Scripts (PC copy) | `C:\Users\<you>\Documents\img2mesh\blender-mcp\` |
| Scripts (box copy) | `/home/<user>/img2mesh/`, Hunyuan clone at `/home/<user>/hy3d` |
| Phone | `<phone-tailscale-ip>:5555` - the tailnet address - via `C:\Android\platform-tools\adb.exe`. Works anywhere the tailnet is up; `<phone-lan-ip>:5555` only on the same WiFi |

Transport is `box.py` — `--put`, `--get`, `--sudo`, credentials in `box-credentials.json`.

## Choosing the generator

**Use Hunyuan3D-2mini.** This is the single most important decision in the pipeline.

TripoSR is faster but has a hard detail ceiling, and **raising its resolution does not help**. Measured on the same photograph: 320³, 512³ and 768³ all produced the same shape (occupancy 0.00790 vs 0.00792). Its triplane is `plane_size: 32`, so detail is capped long before the marching-cubes grid is. If a model looks like a smooth blob, this is why — not a tuning problem.

| | TripoSR | **Hunyuan3D-2mini (default)** |
|---|---|---|
| a diffuser, 200 mm tall | 69,832 tris | **354,648** (octree 380) · **644,596** (DMC 512) |
| detail | soft, merged features | crisp ribs and thin walls |

Within Hunyuan, **DMC at 512³ is the default**. It extracts on a denser grid than the neural octree and nearly doubles the triangle count on the same source, which is what thin ribs and small features need. The resulting count is a feature, not a problem to manage.

## Step by step

### 1. Prepare the image

`prep_photo.py` applies EXIF rotation. Without it a phone photo is frequently sideways, and the generator faithfully produces a sideways model.

### 1b. Background removal happens inside generation

Generation runs `rembg` (U²-Net saliency segmentation) on the input before encoding it. **This is the step that decides what the model is even looking at** - there is no language or reasoning anywhere in the chain. It picks whichever object is most salient, so:

- **Two objects in frame** → whichever dominates wins, the other leaks into the result.
- **A busy background** → `rembg` leaves fragments behind, and everything downstream inherits them. This failure is silent: the mesh comes out clean and watertight, just wrong.
- **A cropped-too-tight subject** → the silhouette is cut and the model invents the rest.

So the best inputs are one subject, generous framing, and a background that contrasts with it. If a result is inexplicably wrong, suspect `rembg` before suspecting the generator.

**Look at the mask before you spend GPU time on it.** Running `rembg` alone on the candidate input costs seconds and shows exactly what the generator will be given; a full generation costs minutes. A run that skipped this check fed the generator a full frame and got a **detached finger fragment** left in the mask - the clean-but-wrong failure above, caught only because the mask was examined first. When the frame is ambiguous, prepare both a full-frame and a cropped version, using `prep_photo.py --box x0,y0,x1,y1` to isolate the subject.

**Check which object it picked, not just whether the mask is clean.** On a hose clamp photographed in a hand, `rembg` on the full frame returned **the hand** - foreground 0.70, bounding box spanning the whole image - and the pipeline would have produced a confident, watertight model of a hand with nothing downstream flagging it. Pruning the largest connected component did not help, because the stray palm was bonded to the clamp. What worked was preparing several crops and choosing between them:

| input | mask |
|---|---|
| full frame | the hand |
| cleaned composite | clamp plus a bonded palm wedge |
| crop A | clamp plus an edge fragment |
| **crop B** | **clamp alone**, and larger in frame than crop A |

So when the subject is held, or sits against a busy background, generate two or three crops rather than one, and pick the mask that is both clean *and* largest in frame. Reach for `prep_photo.py --box x0,y0,x1,y1` to produce them.

### 2. Wake the box

Follow [gpu-box-power](../gpu-box-power/SKILL.md). Confirm it answers before generating — a cold boot after a failed resume costs minutes, and doing that mid-pipeline looks like a hang.

### 3. Generate

```bash
cd /home/<user>/img2mesh
./venv/bin/python gen_hy3d_vram.py <input.png> \
    --output-dir <dir> --octree 512 --mc-algo dmc --steps 50 --guidance 5.0
```

**Use `gen_hy3d_vram.py`, not `gen_hy3d.py`.** It offloads the denoiser and conditioner between stages, which is what makes 512³ fit on the 8 GB card — measured at the extraction stage: **999 MiB free before the offload, 6341 MiB after**. The plain script hits the CUDA OOM described below.

**`--mc-algo dmc`, not `diso`.** The extractor keys are `['dmc', 'mc']`; `diso` is the *package* name, and passing it fails the argument check. DMC is the right choice: the same source photograph gave **644,596 triangles at 512³** against 354,648 for the neural-octree extractor at 380.

The input is positional and **the output is `<dir>/mesh.stl`** — there is no output path to name on the command line.

**Wait for the output file, not for a line of log text.** A generation takes two to four minutes. Use the helper rather than hand-rolling a loop:

```powershell
.venv\Scripts\python.exe wait_for_mesh.py out_screw --log /home/<user>/img2mesh/out_screw.log
```

It polls for `<dir>/mesh.stl`, watches the log for a traceback, and exits 0 / 1 / 2 so the outcome is unambiguous.

**Never key a wait loop on log text.** The log prints paths *relative* to `/home/<user>/img2mesh` (`wrote out_screw/mesh.stl`) even when the command named an absolute one, so a loop matching `/home/<user>/img2mesh/out_screw/mesh.stl` never fires. Observed on 2026-09-12: a run whose mesh was written six minutes in sat in its own wait loop for the full thirty-minute deadline and then reported a timeout for finished work.

The model is `tencent/Hunyuan3D-2mini`, `subfolder='hunyuan3d-dit-v2-mini'`, `variant='fp16'`. First run downloads it.

`sweep_hy3d.py` varies octree/steps/guidance — **run one config per process**, because the pipeline does not release VRAM between runs in a single process.

### 4. Post-process

`prep_mesh.py` scales to a target height and reorients:

```bash
./venv/bin/python prep_mesh.py <generated.stl> <final.stl> --height-mm 200 --from-y-up
```

**`--from-y-up` is required for Hunyuan output - but as written it inverts the model.** TripoSR and Hunyuan disagree about up-axis, and skipping the flag leaves the model lying on its back.

The rotation itself has the wrong sign. `prep_mesh.py` applies `rotation_matrix(-pi/2, X)`, and at that angle a point at `+Y` lands on `-Z` - it sends "up" to "down". A Y-up source therefore comes out **upside down**, every time, not intermittently. Confirmed visually on a screw (its tip sat at `+Y` in the raw mesh, matching the photograph, and the finished model was inverted) and then by the same axis arithmetic on a second subject too symmetric for the render to show it.

Two ways out, and **pick exactly one**: rotate the finished mesh 180 degrees about X, or fix the sign in `prep_mesh.py` (`-np.pi / 2.0` to `np.pi / 2.0`, which maps `+Y` to `+Z`). Applying the manual flip on top of a fixed script inverts it the other way, and that failure looks identical.

**`--height-mm` is a decision about the print, not a fixed value.** The 200 above is an example, not a default: on an earbud that is roughly **7x life size**. Scale to the part's real dimensions unless the user asked for something else, and say which you chose - a 200 mm default silently turns a small object into a large print.

**It scales the Z extent specifically** (`scale = height_mm / extent[2]`, applied *after* the rotation), so "height" means whatever ends up on Z. For a flat object that is the **smallest** dimension: a hose clamp measuring 0.98 x 0.86 x 0.48 in the raw frame would have come out **202 mm long** at `--height-mm 100`. Size flat parts by the dimension you actually care about and back out the height - that clamp was given `--height-mm 55` to land at 111 mm overall.

### 5. Validate before it ever reaches the phone

```bash
node verify-stl.mjs <final.stl>
```

Expect **watertight**, with 0 degenerate / open / over edges. A model that fails here will fail in the slicer, and it is far cheaper to catch now than after the handoff.

### 6. Deliver to the phone

**If the phone has left the WiFi, reach it over the tailnet.** adbd listens on every interface, so `adb connect <phone-tailnet-ip>:5555` works when the LAN address does not answer — a phone that is out of the house is still reachable, and the whole delivery goes over the tunnel. (The Tailscale caveat elsewhere in these skills is about the *app's* inbound sockets, such as the Blender MCP port; it does not apply to adb.)

**That same listener is the exposure.** adbd binds TCP 5555 on **every** interface (`*:5555`, verified on the device) and authorizes a host by its adb key rather than a per-connection password, so any host the phone trusts — or that gets trusted while the port is open — can reach it from the LAN or the tailnet. The `su -c` copies below then write into the app's private storage. Turn the listener off (`adb usb`, or **Settings ▸ Developer options ▸ Wireless debugging**) once the delivery is done.

The app must be **running** for the import to dispatch — check for its pid before delivering rather than after, and remember the adb server does not survive between shell invocations, so connect and use it in one command.

**Stage through `/sdcard/Download/dsh-agent/`, never the Download root.** That folder is the device's drop box for agent files, and it carries a `.nomedia` so screenshots pushed there stay out of the gallery. The rule covers **everything** you send to the device - models, probe scripts, screenshots - not just the STL.

**Leave models there; take your scaffolding back out.** The staged STL is the copy the user can actually find, because the app's own export directory is transient and gets cleared from the UI - so it stays. One-off probe scripts, log dumps and screenshots have no value once they have run, and a folder of them is what the user has to clean up by hand.

The app hot-loads from its own private directory, so the file must be copied in there — the sdcard alone is not enough:

```powershell
adb push final.stl /sdcard/Download/dsh-agent/<unique-name>.stl
adb shell "su -c 'cp /sdcard/Download/dsh-agent/<unique-name>.stl /data/data/com.tomppi.enderslicercura/files/blender/exports/'"
```

**The filename must be new every time.** The app dedupes by path + size + mtime and dispatches each revision exactly once, so reusing a name can be silently ignored. Include a timestamp: `model-<epoch>.stl`.

The app polls that directory every 500 ms and hot-loads what it finds — no interaction needed. See [blender-mcp-engine](../blender-mcp-engine/SKILL.md) for the handoff in detail.

**`su -c cp` writes the file owned by root, and the app cannot read it.** The copy must be followed by handing it to the app's uid, or the import fails with no error reported anywhere:

```bash
adb shell "su -c 'chown u0_a123:u0_a123 <exports>/<file>.stl && \
    chmod 600 <exports>/<file>.stl'"
```

Check with `ls -la`: the owner must read `u0_a123`, the same uid as the directory, not `root`.

**Ownership is only half of it - the SELinux context is the other half.** The app's data directory carries a per-app context with its own categories:

```text
/data/data/com.tomppi.enderslicercura/files/...   u:object_r:app_data_file:s0:c234,c258,c512,c768
/sdcard/... (where files are staged)              u:object_r:fuse:s0
```

A file created inside the exports directory normally inherits the directory's context, so the plain `cp` above is usually enough - but *usually* is the operative word, and a file carrying `fuse:s0` is invisible to the app no matter who owns it. Verify with `ls -laZ` rather than `ls -la`, and fix with `chcon` if the context is wrong (the binary is present on the device):

```bash
adb shell "su -c 'chcon u:object_r:app_data_file:s0:c234,c258,c512,c768 <exports>/<file>.stl'"
```

Both halves of this have bitten: the run that produced a root-owned file failed with **nothing reported anywhere** - no app error, no log line, just a model that never appeared.

### 7. Hibernate the box

Only once the model is confirmed on the phone. Follow [gpu-box-power](../gpu-box-power/SKILL.md).

## Cancelling does not stop the generation

If this run is cancelled with `session/cancel`, **the generation keeps going**. The agent stops; the `gen_hy3d*` process it launched over ssh does not, and will keep holding VRAM on the box.

Kill it explicitly and hibernate the box - see [gpu-box-power](../gpu-box-power/SKILL.md). A cancelled run leaves the machine awake, which nothing reports.

## The model cannot tell you it was wrong

Shape generation is a learned mapping from appearance to form. It will produce a confident, clean, watertight mesh of something that is not what was photographed, and **nothing in the pipeline flags it** - not the validator, not the generator.

That makes rendering and looking at the result a required step, not a nicety. The judgement in this pipeline is yours; the model supplies no check on itself.

**Do not try to render with the box's Blender.** `/usr/bin/blender` there is broken — `undefined symbol: _ZTVN17MaterialX_v1_39_510TypedValueIbEE`, a MaterialX ABI mismatch left behind by the 6.19 → 7.2.4 hop. Render on the PC's Blender, or use `render_numpy.py`, which was written for exactly this and needs nothing installed on the box. Four views — front, side, top, iso — are enough to tell an earbud from a blob.

## Pitfalls that have already cost time

**A generated mesh that looks tipped over.** `MarchingCubeHelper.forward` applies `v_pos[..., [2,1,0]]` — a torchmcubes convention. skimage already returns vertices in the caller's frame, so on the skimage path that swap tips the mesh. Fixed by pre-swapping in `mc_fallback.marching_cubes` and removing the swap in `gen_dog_clean.py`.

**A speckled, blobby TripoSR mesh.** Its density field is sparse — measured 97% of voxels ≈ 0, p99 = 1526, max = 38936 — so a naive iso-level gives a shell plus noise. Binarise occupancy, box-blur, then cut at 0.5. `gen_dog_clean.py` does this.

**CUDA OOM at 512³** ("Tried to allocate 1.50 GiB" with 890 MiB free). Sample density slice by slice rather than allocating the whole grid; 512³ and 768³ then fit in ~1.9 GiB.

**The model never appears in the app.** Check, in this order: the file is in `files/blender/exports/`, the name is unique, and the app's Blender engine service is running. The app's own file manager (**Plate → Blender files**) shows what it can see.

**A failed wake.** The Pi must be up and must send the packet — it is the only device on the box's ethernet segment that is always on. A magic packet cannot route, so nothing on the main LAN can substitute.

## Reference results

| output | source | generator | tris | size |
|---|---|---|---|---|
| `dog-labrador-100mm.stl` | dog photo | TripoSR, cleaned | 173,696 | 82.4 × 83.5 × 100 mm |
| `spreader-diffuser-detail-200mm.stl` | diffuser photo | Hunyuan3D-2mini (380/384) | **354,648** | 71.79 × 65.38 × 200 mm |
| `spreader-dmc-print.stl` | *same* diffuser photo | **DMC 512³** | **644,596** | 71.78 × 65.37 × 200 mm |
| `airpod-1789207049003.stl` | earbud photo | **DMC 512³**, 50 steps, guidance 5.0 | **1,044,576** | 146.01 × 102.39 × 200 mm |
| `screw-glide-1789210076129.stl` | screw photo | **DMC 512³** | **677,544** | 35.20 × 35.48 × 100 mm |
| `hose-clamp-1789216861083.stl` | hose clamp in a hand | **DMC 512³** | **1,640,128** | 111.06 × 97.74 × 55 mm |

All watertight.

**Triangle count is driven by the subject, not the settings.** Rows 2 and 3 are one photograph through two extractors, which is the like-for-like comparison that justifies DMC - it roughly doubles the count on identical input. Row 4 is a different, busier subject at the same DMC settings, and lands higher still. **So no single number here is the expected output.** Do not treat a count that differs from these as a fault, and never decimate to bring one in line with another.
