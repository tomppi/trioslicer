#!/usr/bin/env python3
"""Derive the PrusaSlicer "all settings" catalogue from data the app already ships.

The PrusaSlicer 3.0 console has no settings dump: its whole command line is
--datadir/--load/--export-gcode, the strings for --help-fff, --dump-settings and
--list-presets are not in the binary, and every one of them prints the usage
stub and exits 0. Unlike the OrcaSlicer console we build ourselves (whose
--dump-settings writes app/src/main/assets/orca/all-settings.json), there is
nothing to run here, so the catalogue the All-settings sheet browses is derived
from the two inputs the fetch script already stages:

  * prusa3-base.json - a fully resolved PrusaSlicer 3.0 configuration (the MK4
    0.4 the app slices from). It names every option and carries its real type
    and default the way the console writes them: numbers, {value, is_percent}
    objects for the FloatOrPercent options, per-slot arrays, point lists,
    strings and booleans (0/1 in the saved file, true/false in the JSON view).
  * prusa-presets.json - every value the vendor presets state. The distinct
    values a key takes there are the choices the repository actually offers
    (fill_pattern: cubic/grid/rectilinear), emitted as an enum-ish hint.

Writes app/src/main/assets/prusa/all-settings.json in the shape both readers
accept (ExtraSettingSpec.prusa/orca) and the OrcaSlicer dump already uses:

    {"settings": [{"key", "type", "default", "desc"[, "values"]}, ...]}

The type vocabulary is the OrcaSlicer dump's, and the file is committed: the
console cannot regenerate it, and :app:checkPrusaSlicerPackaged fails a build
that lost it.
"""
import argparse
import json
import os
import re
import sys

# The three buckets the app resolves and writes. prusa3-base.json also carries
# project_settings, three MMU-only values a single-extruder slice never reads.
# print_settings wins a key more than one bucket carries (travel_slope,
# travel_max_lift, travel_ramping_lift): PrusaConfigWriter renders every extra
# setting into print_settings.
BUCKETS = ("print_settings", "printer_settings", "filament_settings")

# The catalogue the app ships today; a run that derives fewer entries than this
# read the wrong file.
MIN_KEYS = 300

# The OrcaSlicer dump's type names for a single scalar, and their per-slot
# (filament/tool) plurals.
PLURALS = {
    "int": "ints",
    "float": "floats",
    "bool": "bools",
    "string": "strings",
    "percent": "percents",
    "float_or_percent": "float_or_percents",
    "point": "points",
}

# Preset identities and pointers, not values a user picks for an option.
CHOICE_KEY_SKIP = re.compile(
    r"(^default_|_id$|_name$|^name$|^id$|^inherits$|^from$|^type$|^version$"
    r"|_notes$|_vendor$|_colour$|_color$|^printer_model$|^output_filename_format$)"
)

# Longest observed string still treated as a choice, and the most choices
# emitted for one key. Anything longer is a template or a format string.
MAX_CHOICE_CHARS = 24
MAX_CHOICES = 32


def fail(message):
    sys.exit(message)


def load_json(path, what):
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        fail(what + " is missing: " + path + "\nRun scripts/fetch-prusa-engine-android.sh first.")
    except json.JSONDecodeError as error:
        fail(what + " is not readable JSON: " + path + ": " + str(error))


def number_text(value):
    """A number the way the engine's own saved configuration spells it."""
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    text = ("%.15f" % value).rstrip("0").rstrip(".")
    if text in ("", "-0"):
        text = "0"
    # %.15f must not have rounded a small value away.
    return text if float(text) == value else repr(value)


def is_point(value):
    return (
        isinstance(value, list)
        and len(value) == 2
        and all(isinstance(item, (int, float)) and not isinstance(item, bool) for item in value)
    )


def option_type(value):
    """The 3.x type of one resolved value, in the OrcaSlicer dump's vocabulary."""
    if isinstance(value, list):
        if value and all(is_point(item) for item in value):
            return "points"
        # gcode_substitutions and post_process are empty string vectors.
        if not value:
            return "strings"
        element_types = {option_type(item) for item in value}
        if len(element_types) == 1:
            only = element_types.pop()
            return only if only == "points" else PLURALS.get(only, "strings")
        return "strings"
    if isinstance(value, dict) and "value" in value:
        return "percent" if value.get("is_percent") else "float_or_percent"
    if isinstance(value, bool):
        return "bool"
    if isinstance(value, int):
        return "int"
    if isinstance(value, float):
        return "float"
    # A string, and the null slot of a per-slot array (idle_temperature).
    return "string"


def render_value(value):
    """The default the way the engine's saved configuration spells it."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return number_text(value)
    if isinstance(value, str):
        return value
    if isinstance(value, dict) and "value" in value:
        text = render_value(value["value"])
        return text + "%" if value.get("is_percent") else text
    if isinstance(value, list):
        # PrusaSlicer joins vectors with "," and writes each point as "x,y".
        parts = []
        for item in value:
            parts.append(number_text(item[0]) + "x" + number_text(item[1]) if is_point(item) else render_value(item))
        return ",".join(parts)
    return ""


def catalog_from_base(base):
    configuration = base.get("configuration")
    if not isinstance(configuration, dict):
        fail("prusa3-base.json has no configuration object")
    settings = {}
    for bucket in BUCKETS:
        values = configuration.get(bucket)
        if not isinstance(values, dict) or not values:
            fail("prusa3-base.json carries no " + bucket + " values")
        for key, value in values.items():
            if key in settings:
                continue
            settings[key] = {
                "key": key,
                "type": option_type(value),
                "default": render_value(value),
                "desc": "",
            }
    if len(settings) < MIN_KEYS:
        fail("prusa3-base.json yielded only " + str(len(settings)) + " settings; expected at least " + str(MIN_KEYS))
    return dict(sorted(settings.items()))


def observed_values(presets):
    """Every scalar the preset documents state per key, plus the composite keys."""
    observed = {}
    composite = set()

    def visit_values(values):
        if not isinstance(values, dict):
            return
        for key, value in values.items():
            if isinstance(value, (str, int, float, bool)):
                observed.setdefault(key, set()).add(value)
            else:
                composite.add(key)

    def visit(node):
        if not isinstance(node, dict):
            return
        visit_values(node.get("values"))
        for variant in node.get("variants") or []:
            visit(variant)

    documents = presets.get("documents")
    if not isinstance(documents, list) or not documents:
        fail("prusa-presets.json carries no documents")
    for document in documents:
        visit(document)
    return observed, composite


def choices_for(entry, observed, composite):
    """The observed choices of [entry], or None when the values are not a choice list."""
    key = entry["key"]
    if key in composite or "gcode" in key or CHOICE_KEY_SKIP.search(key):
        return None
    values = observed.get(key)
    if not values:
        return None
    # A boolean is spelled 0/1 in the presets, and both spellings appear as the
    # YAML and JSON writers round-trip it; the hint is worth having only when
    # the repository really uses both.
    if entry["type"] in ("bool", "bools"):
        spellings = set()
        for value in values:
            if value is True or (isinstance(value, str) and value.lower() == "true"):
                spellings.add("1")
            elif value is False or (isinstance(value, str) and value.lower() == "false"):
                spellings.add("0")
            elif isinstance(value, int) and value in (0, 1):
                spellings.add(str(value))
            elif isinstance(value, str) and value.strip() in ("0", "1"):
                spellings.add(value.strip())
        return ["0", "1"] if spellings == {"0", "1"} else None
    if entry["type"] not in ("string", "strings"):
        return None
    choices = {
        value.strip()
        for value in values
        if isinstance(value, str)
        and value.strip()
        and "\n" not in value
        and len(value.strip()) <= MAX_CHOICE_CHARS
    }
    if len(choices) < 2 or len(choices) > MAX_CHOICES:
        return None
    return sorted(choices)


def validate(settings):
    problems = []
    known_types = set(PLURALS) | set(PLURALS.values())
    for key, entry in settings.items():
        if not key or entry["type"] not in known_types:
            problems.append(key + " has no type")
    for required in ("fill_pattern", "perimeter_speed", "first_layer_temperature"):
        if required not in settings:
            problems.append("the base configuration lost " + required)
    choice_keys = [key for key, entry in settings.items() if entry.get("values")]
    if "fill_pattern" not in choice_keys:
        problems.append("no fill_pattern choices mined from prusa-presets.json")
    if len(choice_keys) < 10:
        problems.append("only " + str(len(choice_keys)) + " keys carry mined choices")
    if problems:
        fail("derived catalogue is not sane:\n  " + "\n  ".join(problems))


def write_catalog(path, settings):
    entries = []
    for entry in settings.values():
        fields = [
            '"key": ' + json.dumps(entry["key"], ensure_ascii=False),
            '"type": ' + json.dumps(entry["type"]),
            '"default": ' + json.dumps(entry["default"], ensure_ascii=False),
            '"desc": ' + json.dumps(entry["desc"]),
        ]
        if entry.get("values"):
            fields.append('"values": ' + json.dumps(entry["values"], ensure_ascii=False))
        entries.append("    {" + ", ".join(fields) + "}")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write('{\n  "settings": [\n' + ",\n".join(entries) + "\n  ]\n}\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("assets", help="app assets directory, e.g. app/src/main/assets")
    args = parser.parse_args()

    base = load_json(os.path.join(args.assets, "prusa3-base.json"), "the resolved base configuration")
    presets = load_json(os.path.join(args.assets, "prusa-presets.json"), "the preset repository view")

    settings = catalog_from_base(base)
    observed, composite = observed_values(presets)
    for key, entry in settings.items():
        choices = choices_for(entry, observed, composite)
        if choices:
            entry["values"] = choices
    validate(settings)

    out = os.path.join(args.assets, "prusa", "all-settings.json")
    write_catalog(out, settings)

    types = {}
    for entry in settings.values():
        types[entry["type"]] = types.get(entry["type"], 0) + 1
    choice_keys = [key for key, entry in settings.items() if entry.get("values")]
    print("Prusa all-settings: " + str(len(settings)) + " keys from prusa3-base.json " + str(types))
    print("  choices mined from prusa-presets.json: " + str(len(choice_keys)) +
          " keys (e.g. fill_pattern -> " + ", ".join(settings["fill_pattern"].get("values", [])) + ")")
    print("  samples: " + "; ".join(
        key + "=" + settings[key]["type"] + "(" + settings[key]["default"] + ")"
        for key in ("fill_pattern", "perimeter_speed", "first_layer_temperature", "bed_shape")
    ))
    print("  wrote " + out + " (" + str(os.path.getsize(out) // 1024) + " KiB)")


if __name__ == "__main__":
    main()
