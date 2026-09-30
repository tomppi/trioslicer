# Calibrating the input shaper with a phone

A move that stops at a corner rings, and the ringing prints as ripples beside the corner.
Input shaping drives the steppers with a pattern that cancels that ring: a type, and the
frequency to cancel. Wrong values make the ripples worse, so change one axis at a time and print
something with corners.

## The two ways

Klipper can measure the frequencies itself with `SHAPER_CALIBRATE`. That needs an accelerometer
**wired to the board** and a `[resonance_tester]` section. If the printer has neither - the usual
case for a stock Ender 3 - `SHAPER_CALIBRATE` refuses, and the two ways left are below.

## With this phone

The phone is the accelerometer Klipper has not got. Klipper's own sweep is still the test: the
printer excites the axis exactly as `TEST_RESONANCES` would, while the phone records itself. The
playback is a module the app adds beside Klipper (`PLAY_RESONANCES AXIS=X`); the mechanism and the
fidelity to Klipper's own generator are described in [KLIPPER_UI.md](KLIPPER_UI.md).

### 1. Put the phone where it can feel the axis

**On the gantry for X, on the base for Y.** Not on the toolhead and not on the bed: there its own
weight would change the resonance it is measuring. On the gantry the phone is measuring what the
toolhead reacts against; on the base it is measuring what the moving bed excites. It has to be
heavy against something rigid rather than sitting on the part that moves.

### 2. Tell it the masses

The phone rides the structure, so its weight lowers the frequency it reports. The screen asks for
**the phone's mass** (weigh it, or take the figure from the phone's own specification) and **the
moving mass** - the toolhead and everything on it for X, the bed with its plate, heater, carriage,
wheels and whatever is sitting on top for Y.

The reported frequencies are then corrected for the phone's own weight, 253 g against 400 g on
this printer. **Treat the result as a place to start, not a calibration**: the correction comes
from a single-mass model, and the mode being measured is not only the bed on the belt.

### 3. Play the sweep, once per axis

The sweep runs for about a minute. The numbers appear at the top of the Shaping screen when they
are ready and stay there, so the screen can be left while it runs - the printer is moving, so keep
clear of it.

### 4. Run it twice, and believe what repeats

Where the phone is standing decides which peaks are loud, so a single run is not evidence. Run
each axis twice: the screen lists the peaks and says how many measurements each was seen in.
"Seen in 2 of 2 measurements" is the one to take. Each peak is also shown against the machine's
own noise - **3x the machine's own noise** is a real peak, 1x is the floor.

### 5. Apply, one axis at a time

Each axis takes a shaper type and a frequency. The types are not interchangeable: each has a
lowest frequency that means anything (zv from 21 Hz, mzv from 23, zvd and ei from 29, 2hump_ei
from 39, 3hump_ei from 48 on this printer), and a type asked for below its floor is not doing
what its name says.

**Putting a frequency in the field does not apply it.** Set the type and the frequency, apply,
print something with corners, and judge it by the ripples. Change one axis at a time: changing
both at once, or judging by eye without a print, is how a shaper is made worse.

### 6. Save, which restarts the host

Both axes are written into `printer.cfg` and the host restarts to read them. The printer is
unavailable for a few seconds and **a print in progress is lost**, so save between prints.

## Without the phone: by eye

Print a tall shape fast, measure the distance between the ripples it leaves, and divide the speed
by that distance. The result is the frequency to enter. It is cruder than a measurement and it
needs a print you can see the ripples on, but it needs nothing but the printer.

## What has been measured here

On this printer, two runs per axis on the phone gave peaks at **51.1 Hz** and **100.9 Hz** on Y,
both well above the noise floor, both seen in both runs. Shapers are set per axis in
`printer.cfg`, and the frequency that survives a change of position is the one that is the
machine's rather than the phone's.
