# The excitation half of Klipper's resonance test, on its own.
#
# TEST_RESONANCES does two things at once: it drives an axis with a frequency sweep, and it
# records what an accelerometer felt. When the sensor is somewhere else - a phone lying on the
# base of the printer, say - only the excitation is wanted, and there is no way to ask for it:
# [resonance_tester] requires an accelerometer chip, and the test always captures.
#
# This is that half. It mirrors ResonanceTestExecutor.run_test from
# klippy/extras/resonance_tester.py, deliberately line for line, because the moves are the
# point: they are toolhead.move() calls with explicit velocities, and G-code cannot express
# those - a G1 is planned as its own trapezoid, with the junction smoothing and the max_accel
# of the printer, so a sweep sent as G-code would not be the sweep Klipper measures with.
#
# It is not a patch to Klipper. It is a file beside it, loaded when the printer's
# configuration has a [resonance_playback] section, and it changes nothing about how Klipper
# itself behaves.
import math

class ResonancePlayback:
    def __init__(self, config):
        self.printer = config.get_printer()
        self.gcode = self.printer.lookup_object('gcode')
        self.gcode.register_command(
            'PLAY_RESONANCES', self.cmd_PLAY_RESONANCES,
            desc=self.cmd_PLAY_RESONANCES_help)

    cmd_PLAY_RESONANCES_help = (
        "Play the vibration sweep the resonance test uses, without measuring it")

    def _generate(self, freq_start, freq_end, accel_per_hz, hz_per_sec):
        # VibrationPulseTestGenerator.gen_test, unchanged. Alternating half periods at
        # accel_per_hz * frequency, sweeping up by hz_per_sec, which is what makes the
        # excitation's amplitude constant across the sweep.
        freq = freq_start
        res = []
        sign = 1.
        time = 0.
        while freq <= freq_end + 0.000001:
            t_seg = .25 / freq
            accel = accel_per_hz * freq
            time += t_seg
            res.append((time, sign * accel, freq))
            time += t_seg
            res.append((time, -sign * accel, freq))
            freq += 2. * t_seg * hz_per_sec
            sign = -sign
        return res

    def _test_point(self, gcmd, toolhead, axis):
        # The middle of each axis' travel unless the caller says otherwise: the sweep needs
        # room to move in, and the same place every time is what makes two runs comparable.
        # Z is lifted to at least 10mm so that an axis at Z=0 does not sweep along the bed.
        status = toolhead.get_status(self.printer.get_reactor().monotonic())
        low, high, position = status['axis_minimum'], status['axis_maximum'], status['position']
        x = gcmd.get_float('X', (low[0] + high[0]) / 2.)
        y = gcmd.get_float('Y', (low[1] + high[1]) / 2.)
        z = gcmd.get_float('Z', max(position[2], 10.))
        return [x, y, min(z, high[2]), position[3]]

    def cmd_PLAY_RESONANCES(self, gcmd):
        axis = gcmd.get('AXIS', 'X').upper()
        if axis not in ('X', 'Y'):
            raise gcmd.error("AXIS must be X or Y: those are the axes input shaping tunes")
        freq_start = gcmd.get_float('FREQ_START', 5., minval=1.)
        freq_end = gcmd.get_float('FREQ_END', 135., minval=freq_start, maxval=300.)
        accel_per_hz = gcmd.get_float('ACCEL_PER_HZ', 60., above=0.)
        hz_per_sec = gcmd.get_float('HZ_PER_SEC', 1., minval=0.1, maxval=2.)

        reactor = self.printer.get_reactor()
        toolhead = self.printer.lookup_object('toolhead')
        # manual_move refuses unless the axes are homed, which is the check wanted here: a
        # sweep from an unknown position is a crash.
        toolhead.manual_move(self._test_point(gcmd, toolhead, axis), 50.)
        toolhead.wait_moves()
        toolhead.dwell(0.500)

        test_seq = self._generate(freq_start, freq_end, accel_per_hz, hz_per_sec)
        X, Y, Z, E = toolhead.get_position()
        dx, dy = (1., 0.) if axis == 'X' else (0., 1.)

        systime = reactor.monotonic()
        toolhead_info = toolhead.get_status(systime)
        old_max_accel = toolhead_info['max_accel']
        old_minimum_cruise_ratio = toolhead_info['minimum_cruise_ratio']
        max_accel = max([abs(a) for _, a, _ in test_seq])
        self.gcode.run_script_from_command(
            "SET_VELOCITY_LIMIT ACCEL=%.3f MINIMUM_CRUISE_RATIO=0" % (max_accel,))
        # The machine's own shaping has to be off for this. With it on, the sweep excites a
        # machine that is already being corrected, and what the phone hears is the shaper.
        input_shaper = self.printer.lookup_object('input_shaper', None)
        if input_shaper is not None and not gcmd.get_int('INPUT_SHAPING', 0):
            input_shaper.disable_shaping()
            gcmd.respond_info("Disabled [input_shaper] for the test")
        else:
            input_shaper = None
        last_v = last_t = last_freq = 0.
        try:
            for next_t, accel, freq in test_seq:
                t_seg = next_t - last_t
                toolhead.cmd_M204(self.gcode.create_gcode_command(
                    "M204", "M204", {"S": abs(accel)}))
                v = last_v + accel * t_seg
                abs_v = abs(v)
                if abs_v < 0.000001:
                    v = abs_v = 0.
                abs_last_v = abs(last_v)
                v2 = v * v
                last_v2 = last_v * last_v
                half_inv_accel = .5 / accel
                d = (v2 - last_v2) * half_inv_accel
                nX = X + dx * d
                nY = Y + dy * d
                toolhead.limit_next_junction_speed(abs_last_v)
                if v * last_v < 0:
                    # The move first goes to a complete stop, then changes direction
                    d_decel = -last_v2 * half_inv_accel
                    toolhead.move([X + dx * d_decel, Y + dy * d_decel, Z, E], abs_last_v)
                    toolhead.move([nX, nY, Z, E], abs_v)
                else:
                    toolhead.move([nX, nY, Z, E], max(abs_v, abs_last_v))
                if math.floor(freq) > math.floor(last_freq):
                    gcmd.respond_info("Testing frequency %.0f Hz" % (freq,))
                    reactor.pause(reactor.monotonic() + 0.01)
                X, Y = nX, nY
                last_t = next_t
                last_v = v
                last_freq = freq
        finally:
            # The one deliberate difference from the original, which does this on the normal
            # path only: here an interruption is somebody pressing stop, and a printer left
            # with the sweep's acceleration limits and no input shaping would carry both into
            # the next print with nothing on screen to say so.
            if last_v and not self.printer.is_shutdown():
                d_decel = -.5 * (last_v * last_v) / old_max_accel
                toolhead.cmd_M204(self.gcode.create_gcode_command(
                    "M204", "M204", {"S": old_max_accel}))
                toolhead.move([X + dx * d_decel, Y + dy * d_decel, Z, E], abs(last_v))
            self.gcode.run_script_from_command(
                "SET_VELOCITY_LIMIT ACCEL=%.3f MINIMUM_CRUISE_RATIO=%.3f"
                % (old_max_accel, old_minimum_cruise_ratio))
            if input_shaper is not None:
                input_shaper.enable_shaping()
                gcmd.respond_info("Re-enabled [input_shaper]")

def load_config(config):
    return ResonancePlayback(config)
