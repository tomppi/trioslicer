# The printer's screens

The app hosts Klipper itself, so its printer interface is the whole front end rather
than a client of one. What follows is what each screen is for, where its numbers come
from, and what is deliberately not there.

Written against Klipper v0.13.0 (the version vendored in `app/src/main/assets/klipper`)
and taking Mainsail 2.19.0 as the reference for what a user of a Klipper printer already
expects to find, and where. Nothing from Mainsail or Moonraker runs on the phone: the
app talks to klippy's own JSON API over its unix socket, which is the same interface
Moonraker speaks to.

## The screens

| Screen | For | Read from |
| --- | --- | --- |
| Dashboard | What the machine is doing now, why a print stopped, and the numbers that decide its speed | `print_stats`, `virtual_sdcard`, `display_status`, `gcode_move`, `motion_report`, `fan`, `toolhead`, `mcu`, `exclude_object` |
| Temperatures | Every heater and sensor, and the last five minutes of them | `extruder`, `heater_bed`, `temperature_sensor *`, `heater_generic *`, `temperature_fan *`, `configfile` |
| Move | Homing, jogging, the steppers | `toolhead`, `gcode_move`, `configfile` |
| Extrude | Feeding filament, pressure advance, retraction | `extruder`, `configfile` |
| Macros | The printer's own `gcode_macro` sections | `configfile.settings` |
| Files | What is on its virtual SD card, and what each file says about itself | The app's own `gcodes` directory, and the G-code header of each file |
| Console | What it says, and a line to answer with | `gcode/subscribe_output`, `gcode/help`, `gcode/script` |
| Z probe | The probe's offset, and the calibration that sets it | `probe`, `manual_probe`, `configfile` |
| Shaping | Input shaping, per axis | `configfile` (klippy publishes no status for the input shaper) |
| Mesh | The bed as the probe found it | `bed_mesh` |
| History | What this app has printed | Its own record, written when a print ends |
| Machine | The host, the boards, the configuration and the log | `info`, `mcu`, `system_stats`, `query_endstops`, `configfile` |

The dashboard carries the numbers a print is diagnosed with, and they are not all from the same
place:

- **Speeds** - what the file asked for (`gcode_move.speed`, mm/s), what M220 makes of it
  (`speed_factor`, a ratio), and what the toolhead is measured doing (`motion_report.live_velocity`),
  beside the printer's own limits. A printer at 200% does not go twice as fast once
  `max_velocity` is reached, and the card says so rather than leaving the slider looking broken.
- **Limits** - the same file's own limit lines against the printer's. Only `M204` means anything
  to klippy, as `max_accel = min(P, T)`; `M201`, `M203` and `M205` are Marlin's and are named as
  ignored, because a file that appears to set a 500 mm/s ceiling is setting nothing.
- **Why it stopped** - `print_stats.message`, which is where klippy puts "Move out of range" and
  "Heater extruder not heating at expected rate". A print that failed says so, whether or not the
  state still says printing.
- **Babystepping** - the same `SET_GCODE_OFFSET Z_ADJUST` nudges as the Z probe screen, on the
  screen the first layer is watched from. The offset lasts until the host restarts; the Z probe
  screen is where a good one becomes the probe's own zero.
- **Host health** - the phone's battery and temperature. The host is this device, and one hot
  enough to throttle shows it in the link first, as stalls and a lookahead that will not stay up.

Two things are always within reach while a print runs: a **stop** on every tab, because the
moment a print is seen to be going wrong is the moment to stop it, and a notification when the
print ends - finished, cancelled, or stopped with klippy's reason in the body. Motion is refused
while printing rather than merely discouraged: homing, jogging, releasing the steppers and hand
extrusion would each leave the printer's idea of where the tool is out of step with the file's.
Pausing brings them back, which is what a filament change needs.

Every screen is always present, whether or not the printer has the section it describes.
A tab that appears and disappears is a tab nobody learns to find; a screen whose
configuration is missing says so instead.

## What the app asks klippy for

The subscription is decided at every connection from the printer's own object list
(`objects/list`), not from a list compiled into the app. A chamber sensor, a second
micro-controller, a fan someone named: each is a section in `printer.cfg`, so each
appears in the screens without this app being told about it. An object the printer has
never heard of answers with an empty status rather than an error - `webhooks.py` returns
`{}` for it - so subscribing blindly would fill the screens with objects that are not there.
Asking for what is published is also the only way to subscribe at all on a printer with no
`[bed_mesh]` section.

G-code output is only pushed to a client that subscribes (`gcode/subscribe_output`), and
the app asks for it with Moonraker's own response template, so the frames on the wire are
the ones every existing front end already parses. Commands this app sends are written to
the same console, which is what makes a button that moved the machine to the wrong place
something that can be looked at afterwards. Errors klippy sends for a command nobody is
waiting on arrive on that same stream and are shown there: before this, a macro that
failed looked exactly like a macro that did nothing.

## What is not here, and why

Mainsail and Fluidd are clients of Moonraker, and a good part of what they show is
Moonraker's rather than the printer's. Those components do not exist on the phone, by
design - the app is the host - so the screens that would show them are absent rather than
empty:

- **Update manager** - it updates the Klipper checkout, Moonraker and the system packages
  of a Linux host. Here the host is the app, and its payload is updated with it.
- **Power devices** - Moonraker's relays and Tasmota plugs. There is no second device to
  switch.
- **Webcam, timelapse, spoolman, job queue** - Moonraker components or external services.
  A job queue is the one of these that could be built on this side; nothing prints from a
  queue today.
- **Klipper's own `configfile.config`** is a parsed dictionary rather than the file, so
  the Machine screen reads `printer.cfg` from disk - which is where the comments are.
- **Editing `printer.cfg` in the app.** It is shown, and the shipped default can be
  put back over it, but there is no text editor for it: four hundred lines in a text
  field driven by a phone keyboard is a poor place to change the pin a stepper is on,
  and the two things people actually edit it for - calibration values and a saved mesh
  - are written by klippy from the screens that do them. Worth adding if it turns out
  to be wanted; the restore button is the way back from a bad edit either way.

## Where the numbers on the screens come from

- The dashboard's lookahead is klippy's own `buffer_time`: `toolhead.print_time` against
  `toolhead.estimated_print_time`, which is exactly the expression Klipper's `toolhead.py`
  uses to decide whether it is starving.
- The host link card and each micro-controller's card read `mcu.last_stats`: `srtt`,
  `rttvar`, `rto`, `bytes_retransmit`, `mcu_awake`.
- The temperature chart samples once a second and keeps five minutes
  (`KlipperTemperatureLog`). klippy pushes a reading every time one changes; a chart of
  that is a chart of the network rather than of the heater.
- The Files screen reads each file's own header - the slicer, the estimated time, the
  filament, the layer height, and the thumbnail the slicer embedded - rather than keeping
  a database of what was sliced.
- The History screen's records are written by this app at the moment a print ends, which
  is the only moment those numbers exist: klippy's `print_stats` describes the print that
  is happening and then the next one.

### Playing the sweep without an accelerometer

Klipper's `TEST_RESONANCES` does two things at once: it excites an axis with a frequency
sweep, and it records what an accelerometer felt. When the sensor is somewhere else — a
phone lying on the base of the printer — only the excitation is wanted, and there is no way
to ask for it: `[resonance_tester]` requires an accelerometer chip, and the test always
captures.

So the app carries one file of its own inside the staged payload:
`native/klipper-playback/resonance_playback.py`, which adds `PLAY_RESONANCES AXIS=X`. It
mirrors `ResonanceTestExecutor.run_test` line for line — the same generator (5→135 Hz at
1 Hz/s, `accel_per_hz` 60, alternating half periods), the same `M204` per segment, the same
explicit velocities passed to `toolhead.move()`, and the same disabling of input shaping for
the duration. That fidelity is not decoration: the moves are `toolhead.move()` calls with
velocities G-code cannot express, so a sweep sent as `G1`s would be planned as its own
trapezoids and would not be the sweep Klipper measures with.

`scripts/verify-resonance-playback.py` lifts `gen_test` out of the vendored
`resonance_tester.py` with `ast` and compares the two schedules element by element at every
staging, so a Klipper that moves is caught on a build machine rather than on a printer.

It has been run on the printer: the module loads through the app's own include, the axis
sweeps from 20 to 120 Hz, and the velocity limits and the input shaper are restored at the
end.

It is a file beside Klipper, not a patch to it: Klipper's three Android patches modify
upstream files, while this adds a command and changes no behaviour of Klipper's own. It is
loaded by a `[resonance_playback]` section, which lives in the app's own `app.cfg` — the
printer's configuration gains exactly one line, `[include app.cfg]`, added above klippy's
saved block so it is never read as part of it.

### Measuring it with the phone

The **Shaping** screen can play that sweep and listen to it with the phone's own
accelerometer — an LSM6DSV on the Fold 5, which reports up to 416 Hz. Against a band that
ends at 120 Hz that is four times oversampled, and against a noise floor of about
0.1 mg/√Hz it is two orders of magnitude above the shaking a printer produces: a frame
moving 50 µm at 35 Hz is roughly 245 mg.

The recording and the sweep are not synchronised and do not need to be. The analysis finds
where the machine started moving inside its own recording, and then, for each frequency in
the band, measures how much of that frequency is in the stretch of recording from when the
sweep was playing it — a single-bin DFT per frequency, which is the classic stepped-sine
method done in one pass. The frequency axis comes from the sweep's own arithmetic: the
generator advances the frequency by `2 · (0.25 / f) · hz_per_sec` every half period, and a
half period lasts `0.25 / f`, so the two cancel to `df/dt = hz_per_sec` exactly — the band is
crossed linearly, at the rate its name says.

The phone goes on the printer's base, never on the toolhead or the bed: it weighs 230 g,
and on the moving mass that would change the machine being measured. What it hears from the
base is the frame's response, which is the same path the ringing is audible through.

### Where the phone goes, and what that costs

The shaper for each axis corrects the resonance of the mass that axis moves: for X on a bed
slinger that is the toolhead on its belts, for Y it is the bed. Klipper's own instructions
follow the same logic — the accelerometer goes on the toolhead for X and on the bed for Y —
so the sensor belongs on the moving part, and the phone cannot go there.

It weighs 253 g. An accelerometer weighs about one. On an Ender-class toolhead, whose
carriage, hotend and extruder come to something like 500 g, that is a 50% increase in the
moving mass, and `f ∝ √(k/m)` puts the mode about 23% lower: a true 90 Hz would be measured
as roughly 73 Hz. On the bed — a few hundred grams of plate, carriage and springs — a
bed-mounted phone reads low by around 15%, which is why a bed reading of 30 Hz and a
toolhead-mounted 35 Hz are the same machine rather than two different ones.

What the phone gets instead, from whatever part of the machine it is lying on, is that
part's response to the moving mass — and the parts are not equally good at it.

**The gantry is the best place a phone can go for X.** It is the rail the toolhead rides on,
so the toolhead's inertia reacts directly against it: the beam is shaken by exactly the
motion the shaper is correcting, which is why a phone lying on the gantry reads within a
couple of hertz of a toolhead-mounted accelerometer.

It is also the place where the phone's weight matters least. The gantry, its uprights and
the base together come to several kilograms, so 253 g is a few per cent of the structure it
is sitting on — a shift of around 2% in any mode that involves it, against 23% on the
toolhead and 15% on the bed. And for the toolhead's own mode, mass on the gantry does not
change the moving mass at all: the toolhead still weighs what it weighed, so what the
reading gives is that mode seen through the beam's response.

**The base is the place for Y**, where the reaction path runs from the bed through the Y
belt and its motor mount into the frame. The gantry is coupled to it as well, being bolted
through the uprights, but further from where the bed's force enters.

The cost either way is that the structure has modes of its own, loud in some places and
quiet in others, so a single reading depends on where the phone is standing — which is what
the agreement across several measurements is for.

That is what the agreement across several measurements is for, and it is worth being clear
about the order of trust:

1. **An accelerometer on the moving mass** — what a printer's own calibration is, and what
   the numbers already in `[input_shaper]` usually came from.
2. **The phone on the base** — a proxy. It found 88–90 Hz against a calibrated 89.8, which
   makes it good for confirming a value and for noticing that one has gone stale after a
   mechanical change.
3. **The phone on the toolhead or the bed** — worse than useless, because it measures the
   machine plus a phone.

### Measuring from the toolhead

The X mode is the toolhead's mass on the belts, so the toolhead is where it lives - and a
phone is a poor accelerometer to put there. An Orbiter v2 direct drive is light for what it is,
but it puts the motor on the carriage where a stock Ender-3 V2 had nothing, so the assembly
comes to roughly 350 g against the stock machine's 210. A 253 g phone on top of that is a 72%
increase in the moving mass, and `f ∝ √(k/m)` therefore reads about 24% low: a true 90 Hz
would be measured near 68.

The screen has a **Phone on the toolhead** mode for this, and it does three things:

- **Half the excitation** (`ACCEL_PER_HZ=30`). The sweep asks for `accel_per_hz * f`, which is
  7200 mm/s² at the top of the band; with 253 g added to the carriage that is more force than
  the belts were ever asked for, and a skipped step during a sweep would be silent and would
  corrupt the measurement.
- **The drive divided out.** On the frame a sensor feels only what the structure transmits. On
  the toolhead it also feels the commanded motion, whose acceleration climbs with frequency -
  a ramp under everything, which would make a peak read against the middle of the curve a
  comparison with the drive rather than with the machine.
- **The phone's weight corrected for**, from a moving mass the screen lets you set, and a note
  saying the correction is a single-mass estimate rather than a calibration.

It also refuses a clipped recording: the commanded motion alone reaches three quarters of a g
at the top of the band, against a sensor that rails at its own limit, and a saturated
recording would otherwise have produced confident nonsense.

**The useful experiment is to measure from both places.** The ratio of the two frequencies
gives the moving mass the phone was sitting on: `m = m_phone / ((f_free / f_loaded)² − 1)`.
A few hundred grams says the two readings are of the same mode - which is how a measurement
taken from the gantry can be shown to be tracking the toolhead rather than something else that
happens to be loud there. An implausible answer says they are not, and that the gantry reading
was a frame mode.

## Input shaping

Two numbers per axis - a shaper type and the frequency it is tuned to - decide the pattern
the steppers are driven with, so that the machine's own ringing cancels itself instead of
printing as ripples beside every corner. The **Shaping** screen sets both, and saves them
into `[input_shaper]` in the printer's configuration.

Two things about it are worth knowing, because they are not what the rest of the interface
does:

- **klippy publishes nothing for the input shaper.** Its status object is empty - queried on
  a running printer, `objects/query?input_shaper` answers `{}` - so the values the screen
  shows are the ones in the configuration, which is what the next restart will use. What the
  printer is using *right now* can only be asked for: `SET_INPUT_SHAPER` with no values makes
  klippy report them, and the answer arrives in the console. The screen has a button for
  exactly that.
- **Nothing needs to be measured to be adjusted.** An accelerometer and a
  `[resonance_tester]` section make finding the frequencies easier; they are not required to
  use them, and the values are routinely set from a ringing test or from a calibration done
  on another host. Where a printer has no accelerometer, the screen says so once and says
  how the numbers are found without one.

Each type has a lowest frequency that means anything - zv 21 Hz, mzv 23, zvd and ei 29,
2hump_ei 39, 3hump_ei 48 - and the screen says so when a frequency is put below the floor
for the type chosen, because below it a shaper pushes the ringing rather than cancelling it.

## The first layer, and the two numbers called Z offset

A printer with a probe has two of them, and only one persists:

- **The probe's offset** is `z_offset` in the printer's `[bltouch]` (or `[probe]`) section. It
  is what the first layer is decided by, and it cannot be calculated - the probe finds the
  bed, somebody brings the nozzle down onto a piece of paper, and where it stopped becomes
  the number.
- **The live offset** is applied on top while printing, for correcting a layer that is going
  down now. It is forgotten when the host restarts.

The **Z probe** screen does the first one: heat (both the nozzle and the bed grow when hot,
and a cold calibration is out by a tenth of a millimetre or more), home, `PROBE_CALIBRATE`,
then the four nudges that find the paper. Those go out as `TESTZ Z=...` rather than as moves,
because klippy is in its manual-probe state and is measuring the offset from each one - a
plain `G1` would move the head without telling the calibration anything. `ACCEPT` takes the
position as the offset, and the screen then offers to save it, which writes it into the
configuration and restarts the host.

The live offset has its own card on the same screen, next to a note saying to reset it after
calibrating, so a correction from an earlier print is not still in the number.

## Bringing your own printer

The app runs Klipper itself, so it has to be told what printer it is driving. It ships
with the configuration of the machine it was developed on - an Ender-3 V2 with a
BLTouch and an Orbiter extruder - and that is what a fresh install uses.

For any other printer, import its `printer.cfg` from **Machine -> Klipper setup**. Choose
the file, and any files it includes, in one go. Everything in it that describes the
printer is left exactly as written - pins, kinematics, rotation distances and directions,
probe offsets, bed size, limits, macros, and the values klippy had already saved into it.
The app changes only the parts that are the phone rather than the printer:

| What | Why |
| --- | --- |
| `[mcu] serial:` | there is no `/dev/serial/by-id` on a phone; klippy is given the pty the app bridges the printer through |
| `[virtual_sdcard] path:` | the files this app slices are in its own storage, and that is where the printer reads them from (added if the file has no such section) |
| `restart_method` | it is made `command`, because klippy reads it here and with it absent falls through to toggling DTR, which a bridge that moves bytes cannot deliver |
| `[mcu <name>]` sections | there is no second board for the app to reach, and klippy will not start while a section names one |
| an `[include ...]` that was not imported | commented out and named, rather than left to stop the printer from starting |

Each change is listed when the import finishes, along with anything the file asks for
that this device cannot supply - a missing `[pause_resume]`, no `PAUSE` macro, a board
reached over CAN - because a button that silently does nothing is worse than one that is
not there.

**Once a configuration is imported it belongs to the user.** The app will not rewrite it
again, not even when it ships a fix to its own default: the app's own configuration is
refreshed on an update, and an imported one never is. The screen says which of the two is
running. **Export** writes the running file out where the user asks for it, and
**Restore the app's configuration** puts the shipped default back, keeping klippy's saved
values - the way back from an import the printer will not start with.

### What the app brings, and what it expects

Importing a configuration tells the app about a printer; it does not put Klipper on the
board. Three things have to be true before any of this moves:

- the board is running **Klipper firmware**, not the stock firmware it shipped with;
- that firmware is from the **same Klipper version** as the host this app carries
  (currently v0.13.0) - Klipper's host and micro-controller speak a versioned protocol, and
  a board flashed some time ago commonly has to be reflashed when the host moves;
- the board is on **USB serial** in a form the app can drive: the chips it knows are the
  CH340/CH341 that Creality boards carry, plus CP210x, FTDI, PL2303 and CDC-ACM boards - so
  an STM32 board flashed with Klipper's own USB support works, and a CAN toolhead board does
  not.

A configuration also has to be for a Klipper that knows its options: an unknown option is a
hard error at startup, and the log on the Machine screen is where it says so.

### What the screens need from a configuration
### Who owns printer.cfg

The app writes the configuration **once**, when it seeds it from the file it ships with the
device-specific values substituted. After that the printer's configuration belongs to whoever
is using the printer, and the app keeps to its own parts of it: the one `[include app.cfg]`
line, and the serial path and gcode directory that the host service resolves at every start.

That rule is written down because the opposite one cost real work. An earlier version also ran
a *refresh* on every connection: if the running configuration differed from the one the app
ships, it was replaced with the shipped file, keeping klippy's saved block. The intent was to
keep an untouched seeded configuration current with the app's own improvements, and the rule it
actually implemented was "this file is the app's until somebody imports one" — so a user who
edited the seeded configuration, rather than importing one, had their edit read as staleness.

On a real printer that looked like this: a measured Y shaper frequency of 44.3 Hz was saved,
the file was written correctly, and the next connection replaced it with the shipped 35.2. The
copy the app keeps of the previous file agreed with the result, because it had been taken from
the same stale text, so nothing on the device showed what had happened. The probe offset
calibrated the same evening survived, because it lives in klippy's `#*#` block and that is
carried across — which is what made the loss look like a save that had never happened.

## The configuration file, and what survives a restart

`PID_CALIBRATE`, `BED_MESH_CALIBRATE` and a Z offset all end in values that live in
`printer.cfg`. klippy writes them there on `SAVE_CONFIG` and then restarts itself, so
the Machine screen offers both, and the host is supervised: klippy exiting is not the end
of the printer, and the app starts it again.

For that to mean anything the file has to survive the restart, which is why it does not
live with the extracted payload any more. The paths are:

| Path (under the app's files directory) | What it is |
| --- | --- |
| `printer-pty` | A symlink to this run's pty, so the serial port in the config never changes |
| `klipper-host/printer.cfg` | The printer's configuration: seeded once from the assets, then its own |
| `klipper-host/printer.cfg.default` | What this version of the app ships, for comparison and restoring |
| `klipper-host/printer-<date>.cfg` | klippy's own backups, written by SAVE_CONFIG |
| `klipper/` | The extracted host payload, replaced when the app is updated |

klippy is given the symlink rather than `/dev/pts/N`, because N is different on every
start and a configuration file that names it would have to be rewritten on every start -
and a file that is rewritten on every start is one `SAVE_CONFIG` can never keep
anything in. `connect_pipe` does nothing but `os.open` the path, so a symlink to the
slave end is opened exactly like the device node it points at.

A saved block is klippy's own `#*# <---- SAVE_CONFIG ---->` section at the end of the
file. It survives an app update: the Machine screen says when the running configuration
is no longer the one this version ships, and restoring it takes the app's default and
puts the saved block back underneath - so a new default arrives without costing the user
a PID calibration or a mesh profile.
