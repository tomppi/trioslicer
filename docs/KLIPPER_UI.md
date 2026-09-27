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
| Dashboard | What the machine is doing now | `print_stats`, `virtual_sdcard`, `display_status`, `gcode_move`, `fan`, `toolhead`, `mcu`, `exclude_object` |
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

Every screen is always present, whether or not the printer has the section it describes.
A tab that appears and disappears is a tab nobody learns to find; a screen whose
configuration is missing says so instead.

## What the app asks klippy for

The subscription is decided at every connection from the printer's own object list
(`objects/list`), not from a list compiled into the app. A chamber sensor, a second
micro-controller, a fan someone named: each is a section in `printer.cfg`, so each
appears in the screens without this app being told about it. klippy refuses a
subscription that names an object it does not have - it refuses the whole request - so
asking for what is published is also the only way to subscribe at all on a printer with
no `[bed_mesh]` section.

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
| `restart_method` | it describes a real serial port, and klippy refuses the option on a pipe connection |
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
