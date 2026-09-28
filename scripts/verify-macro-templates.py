#!/usr/bin/env python3
"""Parse every gcode_macro body the way klippy will load it.

A macro whose body does not compile is not a macro that fails when it is run: klippy compiles
them all at start, so one bad body stops the host coming up. That makes this cheap check worth
having - it covers the macros in the shipped printer.cfg, which nothing validated before, and the
starter macros the app can add to a printer.

The environment is klippy's own (gcode_macro.py:80): the {% %} statement delimiters with single
braces for expressions, and an undefined name allowed - klippy supplies params, printer and the
rest at run time, so this is a syntax check rather than a rendering one.
"""
import pathlib
import re
import sys

import jinja2

ROOT = pathlib.Path(__file__).resolve().parent.parent
SHIPPED = ROOT / "app/src/main/assets/klipper-host/printer.cfg"
LIBRARY = ROOT / "app/src/main/java/com/tomppi/enderslicer/data/KlipperMacroLibrary.kt"

env = jinja2.Environment("{%", "%}", "{", "}", undefined=jinja2.Undefined)


def bodies_from_config(text: str):
    """Every [gcode_macro NAME] section in a printer.cfg, with its body."""
    section = None
    body = []
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            if section is not None:
                yield section, "\n".join(body)
            section = stripped
            body = []
            continue
        if section is not None and section.startswith("[gcode_macro "):
            body.append(line)
    if section is not None and section.startswith("[gcode_macro "):
        yield section, "\n".join(body)


def bodies_from_library(text: str):
    """Every macro in the Kotlin library, whose sections are trimIndent()-ed raw strings."""
    for block in re.findall(r'section = """(.*?)"""\.trimIndent\(\)', text, re.S):
        lines = block.split("\n")
        while lines and not lines[0].strip():
            lines.pop(0)
        while lines and not lines[-1].strip():
            lines.pop()
        cut = min((len(l) - len(l.lstrip()) for l in lines if l.strip()), default=0)
        dedented = "\n".join(l[cut:] for l in lines)
        header = dedented.split("\n", 1)[0].strip()
        body = dedented.split("gcode:", 1)[1] if "gcode:" in dedented else ""
        yield header, body


def main() -> int:
    checked = 0
    failures = []
    for source, reader in (("printer.cfg", bodies_from_config), ("the macro library", bodies_from_library)):
        if not source or not (SHIPPED if source == "printer.cfg" else LIBRARY).is_file():
            continue
        text = (SHIPPED if source == "printer.cfg" else LIBRARY).read_text()
        for header, body in reader(text):
            checked += 1
            try:
                env.parse(body)
            except jinja2.exceptions.TemplateSyntaxError as error:
                failures.append(f"{source}: {header}: {error}")
    if failures:
        print("macros that klippy would refuse to load:", file=sys.stderr)
        for failure in failures:
            print("  " + failure, file=sys.stderr)
        return 1
    print(f"macro templates: {checked} parsed, none that klippy would refuse")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
