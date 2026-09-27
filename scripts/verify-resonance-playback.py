#!/usr/bin/env python3
"""Checks the playback module's sweep against Klipper's own generator, element by element.

The value of playing the sweep ourselves is that it is the same sweep: same frequencies, same
accelerations, same timing. That is a claim about a copy of somebody else's arithmetic, so it
is checked against the original rather than trusted - the generator is lifted out of the
vendored resonance_tester.py and run beside ours.

The vendored file cannot simply be imported: it pulls in chelper, which is built for the
phone's architecture and cannot load on a build machine. Its gen_test is pure Python, so it is
parsed out with ast and run against a stub instead.

Usage: scripts/verify-resonance-playback.py
"""
import ast
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KLIPPER = ROOT / "app/src/main/assets/klipper/klippy/extras/resonance_tester.py"
MODULE = ROOT / "native/klipper-playback/resonance_playback.py"

# The defaults both sides use when a caller says nothing.
CASES = [
    dict(freq_start=5., freq_end=135., accel_per_hz=60., hz_per_sec=1.),
    dict(freq_start=20., freq_end=120., accel_per_hz=60., hz_per_sec=2.),
    dict(freq_start=5., freq_end=40., accel_per_hz=200., hz_per_sec=0.5),
]


def klippers_generator():
    """VibrationPulseTestGenerator.gen_test, lifted from the vendored file."""
    source = KLIPPER.read_text()
    tree = ast.parse(source)
    for node in tree.body:
        if isinstance(node, ast.ClassDef) and node.name == "VibrationPulseTestGenerator":
            for item in node.body:
                if isinstance(item, ast.FunctionDef) and item.name == "gen_test":
                    module = ast.Module(body=[item], type_ignores=[])
                    namespace = {}
                    exec(compile(ast.fix_missing_locations(module), str(KLIPPER), "exec"), namespace)
                    return namespace["gen_test"]
    raise SystemExit("resonance_tester.py no longer defines VibrationPulseTestGenerator.gen_test")


def our_generator():
    namespace = {}
    exec(compile(MODULE.read_text(), str(MODULE), "exec"), namespace)
    playback = namespace["ResonancePlayback"].__new__(namespace["ResonancePlayback"])
    return playback._generate


def main():
    theirs = klippers_generator()
    ours = our_generator()
    failures = 0
    for case in CASES:
        reference = theirs(type("Stub", (), {
            "freq_start": case["freq_start"], "freq_end": case["freq_end"],
            "test_accel_per_hz": case["accel_per_hz"], "test_hz_per_sec": case["hz_per_sec"],
        })())
        mine = ours(**case)
        if len(reference) != len(mine):
            print("FAIL %s: %d segments against %d" % (case, len(reference), len(mine)))
            failures += 1
            continue
        for index, (a, b) in enumerate(zip(reference, mine)):
            if a != b:
                print("FAIL %s: segment %d differs: %r against %r" % (case, index, a, b))
                failures += 1
                break
        else:
            print("ok   %s: %d segments, %.1f s, %.0f Hz at the end"
                  % (case, len(mine), mine[-1][0], mine[-1][2]))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
