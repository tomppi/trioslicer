# Klipper in the app - feasibility assessment

Status: assessment only. No implementation started, pending the user's decision.

## What is being proposed

The phone replaces the Raspberry Pi: TrioSlicer runs the Klipper host (klippy, the
Python process that plans moves, plus its C helper), talks to the printer's
Klipper-flashed MCU over USB host, and feeds it G-code produced by the app's own
slicer. No Pi, no Moonraker, no OctoPrint in the path.

Two architectures, and they are very different sizes:

1. **App as Klipper host** - everything above. Large, novel, the interesting one.
2. **App as Moonraker client** - the app talks to a Klipper host that already
   exists, exactly as it talks to OctoPrint today. Small, useful immediately, and
   worth doing regardless as the interim: it gives the Klipper workflow to anyone
   with a Pi while the hard version is being built.

## Prior art

- [klipper-on-termux-android](https://github.com/K0smic/klipper-on-termux-android)
  runs Klipper, Moonraker, Mainsail and KlipperScreen on a phone. Its README lists
  the requirements plainly: **root**, Termux from F-Droid, AnLinux to install a
  Debian/Ubuntu chroot, and **Octo4a for the USB port**. This is the strongest
  evidence available that the software runs on arm64 Android, and equally strong
  evidence that nobody has done it as a normal app: it is a rooted hobbyist stack,
  a full glibc userland in a chroot, with a second app providing the serial link.
- [Octo4a](https://github.com/feelfreelinux/octo4a) runs OctoPrint on Android and
  exists mostly to solve the USB problem: apps do not get a /dev/tty for the
  printer board, they get bulk USB endpoints, so it presents the host software with
  a [virtual serial port](https://deepwiki.com/feelfreelinux/octo4a/5.1-virtual-serial-port).
  Its README also carries the practical warning that many phones cannot charge
  while using OTG.
- [Klipper's own debugging docs](https://raw.githubusercontent.com/Klipper3d/klipper/master/docs/Debugging.md)
  document simulating an MCU with simulavr, so a host can be tested with no
  hardware attached. [Chaquopy](https://chaquo.com/chaquopy/) embeds CPython in an
  Android app (commercial licence for closed source).
- Relevant local precedent: TrioSlicer already ships Blender and three slicer
  engines, so bundling a non-trivial runtime and native payload in this app is a
  solved problem in this codebase.

## Load-bearing unknowns

Ordered by how likely they are to kill the project.

**U1 - Host timing on Android.** Klippy must keep the MCU's move queue fed with
timestamps it can still meet. A host that stalls too long produces a timer shutdown
mid-print. Phones doze, throttle, freeze background apps and pause threads for GC;
a Pi is slow but dedicated. This decides whether the app can *print*, not whether
it can demo.
*Cheapest disproof:* run klippy on the phone against a simulated MCU for hours,
with the app slicing a large model at the same time, logging the margin between
when a command arrives and when it is due. If the margin survives that, it will
survive a print. **This is the experiment to run first.**

**U2 - chelper and Python in an app-native runtime.** Klipper's C helper is built
for glibc in the Termux case; an app ships bionic. Two routes: build chelper for
Android from the NDK, or bundle a glibc userland the way the Termux project does.
*Cheapest disproof:* build klippy plus chelper for the phone's ABI both ways and
run a G-code file through Klipper's batch mode - no hardware, no USB, one day.

**U3 - USB transport from a non-rooted app.** No tty for the board; a bridge is
needed between the app's bulk transfers and a pty that chelper can open.
*Cheapest disproof:* implement the bridge and drive a real board's handshake
(info, get_config) with no motion commanded. Octo4a proves the shape of it.

**U4 - Power.** OTG and charging at the same time is not universal (Octo4a's
README points at a community list of phones that manage it). A twenty-hour print
on a phone that cannot charge while printing is dead on arrival.
*Cheapest disproof:* check the Fold 5 against that list, or plug it in and look.

**U5 - Licensing.** Klipper is GPLv3. Shipping klippy inside TrioSlicer makes the
combined work subject to it, or forces a process/app boundary so the two stay
separate programs. This is a decision, not a technical risk, but it shapes the
architecture and is cheapest to make now rather than after the code exists.

**U6 - First-time setup.** The MCU needs Klipper firmware flashed and a working
printer.cfg before any of this can talk to it. Treat as a desktop step for v1,
not an app feature.

## Staged plan, with honest ranges

- **S0 - Runtime spike** (2-4 days). Python plus klippy plus chelper running on the
  phone, feeding a G-code file through batch mode. Proves U2.
- **S1 - Timing measurement** (1-2 weeks). Simulated MCU, long run, load, margin
  logging. Proves or kills U1. This is the go/no-go gate.
- **S2 - Real link** (2-4 weeks). USB bridge, real board, handshake, then a bench
  motion test - homes and draws a square. Proves U3.
- **S3 - Product** (4-8 weeks and up). Config management, start/pause/resume/stop,
  error surfaces that a person can act on, streaming the app's own sliced output,
  and the UI to drive it.
- **S4 - Hardening**. Long prints, recovery after a crash or a cable nudge, thermal
  behaviour, what happens when the phone rings.

If S1 fails, the fallback is architecture 2 - a Moonraker client - which is days of
work and still a real feature.

## What I need from the user

1. **Which Klipper version** to design against (klippy and chelper change often).
2. **Which board and MCU** (STM32, RP2040, AVR), and which USB chip the phone will
   see (the board's own USB, or a CH340/CP2102/FTDI adapter).
3. **Rooted or not.** The known-good precedent needs root; the goal here is
   presumably a normal install. LineageOS on the Fold 5 is already rooted, which
   helps the experiments but must not become a requirement.
4. **Power**: does the Fold 5 charge while in OTG host mode?
5. **Licensing stance** on GPLv3 (U5), because it decides whether klippy lives
   inside the app or beside it.
6. **Is the phone the only host**, or is a Pi allowed to stay in the loop for
   anything?

## Recommendation

Run S0 and S1 before committing to anything else. Together they cost a couple of
weeks at most and answer the only question that can kill the project - whether a
phone can be trusted to feed an MCU for the length of a print. Everything after
that is engineering, not risk.
