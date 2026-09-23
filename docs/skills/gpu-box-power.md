---
name: gpu-box-power
description: Hibernate the GPU box and wake it again over Wake-on-LAN from the Raspberry Pi, including the network topology that makes the magic packet reachable and the known hibernation-resume failure.
whenToUse: When putting the GPU box to sleep, waking it after hibernation, debugging a wake that did not work, or checking that the wake-on-LAN configuration survived a reboot or kernel update.
---

# GPU box power (hibernate + wake)

The GPU box sleeps and is woken by a magic packet sent from the Raspberry Pi. **Waking is reliable; resuming the session is not.**

## The machines

| | address | access |
|---|---|---|
| GPU box (GPU box) | `<gpu-box-lan-ip>`, tailnet `<gpu-box-tailscale-ip>` | ssh `<user>`, sudo password in `box-credentials.json` |
| Raspberry Pi (pi4) | `<pi-lan-ip>` (wlan0), `<pi-link-ip>` (eth0) | ssh `<user>` / `<pi-password>` |
| Windows PC (harness host) | `<pc-lan-ip>`, tailnet `<pc-tailscale-ip>` | local |

Helper scripts live in `C:\Users\<you>\Documents\img2mesh\blender-mcp\`: `box.py` (ssh to the box, `--sudo` supported), `pi_send_wol.py` (wake, self-healing), `pi_run.py` (run any command on the Pi, `--sudo` supported), `pi_net_diag.py` (why the Pi has no address), `pi_login.py`.

## Why the packet has to come from the Pi

A magic packet is a **layer-2 broadcast and does not route**, so the sender must be on the same physical segment as the box's ethernet port.

```text
Pi --ethernet--> ASUS "vr router" (bridging, NO dhcp of its own) --WAN--> box enp6s0 (<gpu-box-link-ip>, dnsmasq shared mode)
```

The ASUS bridges rather than routes — proven by the Pi's `eth0` taking a **`<pi-link-ip>` lease from the box's own dnsmasq**. That is the one fact this whole arrangement depends on. If the Pi ever gets a `<windows-hotspot-ip>` address instead, the ASUS has gone back to routing and the wake will silently stop working.

**Neither WiFi nor the main LAN can wake the box.** The box's WiFi is on `<lan-ip>` (a different segment) and WiFi cannot receive magic packets anyway.

## Wake it

```powershell
cd 'C:\Users\<you>\Documents\img2mesh\blender-mcp'
.venv\Scripts\python.exe pi_send_wol.py              # wake it, and wait until it answers
.venv\Scripts\python.exe pi_send_wol.py --no-wait    # send only
```

The packet targets MAC `<wake-target-mac>` (the box's **ethernet**; its WiFi MAC cannot be woken) broadcast to `<link-broadcast>:9` and `255.255.255.255:9`, 102 bytes: six `0xFF` then the MAC sixteen times. The box answers roughly 20–60 seconds later, and a successful resume keeps its uptime — a *cold boot* means the hibernation image did not restore.

To send it by hand from the Pi:

```bash
python3 -c "import socket; p=b'\xff'*6+bytes.fromhex('<wake-target-mac-plain>')*16; \
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1); \
s.bind(('<pi-link-ip>',0)); s.sendto(p,('<link-broadcast>',9))"
```

## A wake that reports success and does nothing

That hand-send is where this breaks, because of where it sends **from**: `<pi-link-ip>`.

That address came originally from a **DHCP lease handed out by the box's own dnsmasq** — the machine that is asleep whenever you need it. The Pi's `netplan-eth0` profile is `dhcp4: true` and **there is no other DHCP server on that segment**, so a Pi that reboots while the box sleeps comes up with no address at all and cannot obtain one until the box is already awake. The wake path deadlocks against itself.

The send then dies with a signature worth recognising on sight:

```text
OSError: [Errno 99] Cannot assign requested address
```

That is `bind` failing on an address the interface does not have. No packet leaves, and nothing reports it.

It cannot simply be renewed either, for the same reason. As of 2026-09-12 the address on the Pi is one **added by hand**, which NetworkManager reports as `eth0:connected (externally)`; that survives the box going down, but still not a Pi reboot. `pi_send_wol.py` re-adds it whenever it is missing.

`pi_send_wol.py` now handles it: it re-adds `<pi-link-ip>/24` when missing, checks the send's exit status instead of trusting the connection, and waits for the box to answer, exiting non-zero when it does not.

Observed on 2026-09-12, which is why that change exists: the script of the day reported `exit code 0` and the box stayed down for three more minutes. It never checked the remote exit status, and the Pi's `eth0` had no address. The agent on the other end worked it out anyway — `ip addr add <pi-link-ip>/24 dev eth0` on the Pi, re-send, box up in 18 seconds — but it had to improvise that under time pressure.

If it fails again:

```powershell
.venv\Scripts\python.exe pi_net_diag.py                                        # what the Pi actually has
.venv\Scripts\python.exe pi_run.py --sudo "ip addr add <pi-link-ip>/24 dev eth0"  # put it back by hand
```

**Verify against the box, never against the sender.** A packet sent to a machine that is already running proves nothing, and a send that failed silently looks identical to one that worked:

```powershell
Test-Connection -ComputerName <gpu-box-lan-ip> -Count 1 -Quiet
```

The durable fix is a **static (or fallback) address on the Pi's `eth0`** rather than a lease from a machine that spends its life asleep. That is a change to the Pi's network configuration, and it was **offered and declined on 2026-09-12** - the self-healing script covers the failure, so do not re-raise it unprompted.

## Hibernate it

```powershell
.venv\Scripts\python.exe box.py --sudo "systemctl hibernate"
```

Confirm it actually went down before expecting a wake — a hibernation that is *refused* leaves the machine running, and a magic packet sent to a running machine looks like a success while proving nothing.

## **Resume is unreliable — expect a cold boot**

Roughly a third of hibernations fail to restore and the machine boots fresh instead, **losing everything in RAM**. Two distinct kernel errors, both ending in `resume failed (-1)`:

```text
PM: hibernation: Failed to load image, recovering.          <- image not where resume= looks
PM: hibernation: Image mismatch: architecture specific data <- image does not match this machine
```

**Never promise a user their session will survive hibernation on this box.** The wake always works; the session may not.

Root cause is not fully settled. Contributing factors:

- Hibernation targets `/dev/sdd4` (**8.8 GB against 15 GB of RAM**) via `resume=UUID=4c2a6571-6a0b-484e-b618-3d5c14a9c2eb`.
- A second swap area, `/swapfile` (64 GB), sits at the **same priority (-1)**, so which area receives the image is not pinned to the one `resume=` names.
- The box went **6.19 → 7.2.4 in one hop** and still carries ~2,900 fc43 packages. Hibernation images go stale across a release transition.

**Attempted fix that failed — do not repeat it.** Pointing `resume=` at the 64 GB swapfile (`resume=UUID=<root> resume_offset=67116288`) removed the ambiguity but made systemd refuse hibernation outright:

```text
Call to Hibernate failed: Specified resume device is missing or is not an active swap device
```

That is strictly worse than an intermittent resume. It was reverted; `/etc/fstab` and `/etc/default/grub` backups from the attempt are `.before-hibernate-fix`. **The real fix is a dedicated swap partition larger than RAM**, which is a partitioning job, not a kernel-argument tweak.

## The pieces that make waking work

| piece | where | why |
|---|---|---|
| BIOS | ErP **Disabled**, Power On By PCIe **Enabled** | ErP cuts standby power to the PCIe slots, so no packet can ever work |
| NIC setting | NetworkManager `802-3-ethernet.wake-on-lan: magic` | persists across reboots |
| `wol-enable.service` | `/etc/systemd/system/`, script at `/usr/local/sbin/wol-enable.sh` | enables the PCI wake flags **at boot** |
| `99-wol-wakeup` | `/usr/lib/systemd/system-sleep/` | re-arms them **after every resume** |

**All four PCI devices on the path need `power/wakeup=enabled`** — `0000:00:01.2` (root port), `0000:02:00.2` (bridge), `0000:03:09.0`, `0000:06:00.0` (the NIC). They reset on every boot *and* every resume.

**A udev rule is not enough** — an `ACTION=="add"` rule misses the root port and one bridge, whose `power/wakeup` attribute does not exist yet when udev handles them. That produced a box that woke exactly once and then silently stopped. The boot service plus the sleep hook is what actually works.

## Cancelling an agent does not stop the work it started

`session/cancel` stops the **agent**, not the processes it launched. A generation started over ssh survives the cancellation, orphaned, still holding VRAM - so a cancelled task can leave the box warm and the GPU busy while the harness reports the session finished.

After cancelling anything that drives this machine, check for and kill the leftovers explicitly:

```bash
pkill -f gen_hy3d_vram.py; pkill -f gen_hy3d
nvidia-smi --query-gpu=utilization.gpu,memory.used,power.draw --format=csv,noheader
```

Then hibernate. **The wake/hibernate pair is not automatically closed by cancelling** - a cancelled run leaves the box awake, which is easy to miss because nothing reports it.

Two related facts about prompting a session:

- **A `steer` arriving with no live turn starts one.** Useful for waking an agent with new instructions; surprising when the intent was to stop it, because a queued steer can undo a cancel.
- So a session reading `running: true` after a cancel may simply be working a steer that arrived behind it.

## Verifying

```bash
cat /proc/cmdline | tr ' ' '\n' | grep resume=
cat /sys/bus/pci/devices/0000:00:01.2/power/wakeup     # must read "enabled"
ethtool enp6s0 | grep Wake-on                          # must read "g"
systemctl is-active wol-enable lactd coolercontrold
journalctl -t wol-wakeup --no-pager | tail -3          # one line per successful resume
```

After a kernel update, re-check the wake flags and `wol-enable.service` — a new kernel can drop the service's enablement, and the failure mode is silent.
