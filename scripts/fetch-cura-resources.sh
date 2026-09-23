#!/usr/bin/env bash
# Stages the Cura resources the CuraEngine path resolves against.
#
# The whole definition tree ships, not just the chain the default machine needs:
# resources/definitions holds 706 machine definitions (33 Creality, 18 Anycubic, 19 UltiMaker,
# 16 Sovol, 15 Elegoo, ...) and resources/extruders the 544 matching extruder trains, with
# variants/ and quality/ alongside. Together that is under 3 MB, and it is what lets the app
# offer Cura's own printer catalogue instead of a single machine.
#
# The tag must match the embedded CuraEngine exactly: the engine resolves these definitions at
# slice time, so a version skew between them is a silent behaviour change. It is the version
# recorded in CuraEngineRunner.BUNDLED_DEFINITION_FILES and in docs/CURAENGINE_ANDROID.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/app/src/main/assets/cura}"
CURA_TAG="${CURA_TAG:-5.14.0-alpha.0}"
WORK="${CURA_WORK:-$ROOT/.build/cura-resources}"

# A blobless sparse checkout fetches the resource directories rather than the whole Cura
# repository; the per-file curl loop this replaces would now need 1250 requests.
if [ ! -d "$WORK/.git" ]; then
  rm -rf "$WORK"
  git clone --depth 1 --branch "$CURA_TAG" --filter=blob:none --sparse \
    https://github.com/Ultimaker/Cura.git "$WORK"
fi
git -C "$WORK" sparse-checkout set resources/definitions resources/extruders

# definitions/ and extruders/ are the catalogue: a machine names its extruder train by bare file
# name, so they land in one directory, which is how the bundled Ender 3 chain already works.
# Cura's resources/variants (7 MB) and resources/quality (26 MB) are frontend concepts -
# nozzle-size choices and per-material quality levels - that this app models with its own
# machine envelope and settings, so they are deliberately not shipped.
rm -rf "$DEST/variants" "$DEST/quality"
mkdir -p "$DEST/definitions"
for dir in definitions extruders; do
  cp -r "$WORK/resources/$dir/." "$DEST/definitions/"
done

# Two source directories collapsing into one must not have shadowed a file.
collisions=$(comm -12 \
  <(ls "$WORK/resources/definitions" | sort) \
  <(ls "$WORK/resources/extruders" | sort) | wc -l)
[ "$collisions" -eq 0 ] || { echo "FATAL: $collisions name(s) exist in both definitions and extruders" >&2; exit 1; }

# Three extruder trains ship with an uppercase extension (tizyx_evy_extruder_0.def.JSON and the
# two of tizyx_evy_dual). Machines reference them by bare lowercase name, so normalize the
# extension here - otherwise those references would dangle for the app's name-based lookups.
while IFS= read -r -d '' upper; do
  mv "$upper" "${upper%.JSON}.json"
done < <(find "$DEST/definitions" -name '*.def.JSON' -print0)

python3 - "$DEST" "$CURA_TAG" <<'PY'
import json
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
tag = sys.argv[2]
files = {
    path.name.removesuffix(".def.json"): path
    for path in (root / "definitions").glob("*.def.json")
}

# The chain the app slices with unless the user picks another machine. Validating it proves the
# fetched tree still carries everything that chain needs, whatever else is in it.
roots = ["creality_ender3"]
seen = set()
missing = []


def dependencies(definition_id: str):
    path = files.get(definition_id)
    if path is None:
        missing.append(definition_id)
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    result = []
    parent = data.get("inherits")
    if parent:
        result.append(parent)
    trains = data.get("metadata", {}).get("machine_extruder_trains", {})
    result.extend(str(value) for value in trains.values())
    return result


def visit(definition_id: str):
    if definition_id in seen:
        return
    seen.add(definition_id)
    for dependency in dependencies(definition_id):
        visit(dependency)


for definition_id in roots:
    visit(definition_id)

if missing:
    raise SystemExit("Missing Cura definition dependencies: " + ", ".join(sorted(set(missing))))

# The app slices the Ender 3 chain unless the user picks another machine, so that chain has to
# keep resolving to exactly the five files the app shipped before the catalogue existed: anything
# else would change the default slice under the user.
DEFAULT_CHAIN = {
    "fdmprinter",
    "fdmextruder",
    "creality_base",
    "creality_base_extruder_0",
    "creality_ender3",
}
if seen != DEFAULT_CHAIN:
    raise SystemExit(
        "The default chain changed, expected exactly "
        + ", ".join(sorted(DEFAULT_CHAIN))
        + " but resolved "
        + ", ".join(sorted(seen))
    )

# The app offers every machine in the tree, not just the Ender chain, so every reference in the
# tree has to resolve: a dangling parent or extruder train would reach the engine as a machine
# it cannot build, long after this script had a chance to notice.
dangling = []
for definition_id, path in files.items():
    data = json.loads(path.read_text(encoding="utf-8"))
    parent = data.get("inherits")
    if parent and parent not in files:
        dangling.append(f"{definition_id} inherits {parent}")
    for value in data.get("metadata", {}).get("machine_extruder_trains", {}).values():
        if str(value) not in files:
            dangling.append(f"{definition_id} uses extruder train {value}")
if dangling:
    raise SystemExit("Unresolved Cura definition references: " + "; ".join(sorted(dangling)))

machines = [
    path
    for path in files.values()
    if json.loads(path.read_text(encoding="utf-8")).get("metadata", {}).get("visible", True) is not False
]
print(f"Reference check: {len(files)} definition files, {len(machines)} visible")

required = {
    "fdmprinter",
    "fdmextruder",
    "creality_base",
    "creality_base_extruder_0",
    "creality_ender3",
}
not_fetched = required - files.keys()
if not_fetched:
    raise SystemExit("Required definitions were not fetched: " + ", ".join(sorted(not_fetched)))


def contains_key(value, key):
    if isinstance(value, dict):
        return key in value or any(contains_key(child, key) for child in value.values())
    if isinstance(value, list):
        return any(contains_key(child, key) for child in value)
    return False


fdmprinter = json.loads(files["fdmprinter"].read_text(encoding="utf-8"))
if not contains_key(fdmprinter, "roofing_layer_count"):
    raise SystemExit("fdmprinter.def.json does not define roofing_layer_count")

# CuraEngine 5.14 main reads several settings (Inner Wall Inset + the support base feature) that
# the pinned 5.14.0-alpha.0 frontend definitions predate. Inject them with their safe defaults
# (no wall inset, support base disabled) so the engine can retrieve them without failing. This
# has to happen to fdmprinter.def.json specifically: every machine inherits from it, so one
# injection covers the whole catalogue.
ENGINE_DRIFT_SETTINGS = {
    "shell": {
        "wall_x_inset": {
            "label": "Inner Wall Inset",
            "description": "Inset applied to the path of the inner wall(s).",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_mesh": True,
        },
    },
    "support": {
        "support_base_inside_width": {
            "label": "Support Base Inside Width",
            "description": "The width of the inside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
        "support_base_outside_width": {
            "label": "Support Base Outside Width",
            "description": "The width of the outside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
        "support_outer_brim_enable": {
            "label": "Enable Outer Support Base",
            "description": "Generate a base around the support infill regions.",
            "type": "bool",
            "default_value": False,
            "settable_per_extruder": True,
        },
        "support_inside_base_curve_magnitude": {
            "label": "Support Inside Base Slope",
            "description": "The magnitude factor used for the slope of the inside support base.",
            "type": "float",
            "default_value": 4.0,
            "settable_per_extruder": True,
        },
        "support_inside_base_height": {
            "label": "Support Inside Base Height",
            "description": "The height of the inside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
        "support_outside_base_curve_magnitude": {
            "label": "Support Outside Base Slope",
            "description": "The magnitude factor used for the slope of the outside support base.",
            "type": "float",
            "default_value": 4.0,
            "settable_per_extruder": True,
        },
        "support_outside_base_height": {
            "label": "Support Outside Base Height",
            "description": "The height of the outside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
    },
}
injected = False
for category, settings in ENGINE_DRIFT_SETTINGS.items():
    children = fdmprinter.setdefault("settings", {}).setdefault(category, {}).setdefault("children", {})
    for name, definition in settings.items():
        if name not in children:
            children[name] = definition
            injected = True
if injected:
    # newline="\n" explicitly: Path.write_text translates to the platform's line endings, so the
    # same fetch produced CRLF on Windows and LF on the runner, and a digest over the chain
    # could never be the same number in both places.
    files["fdmprinter"].write_text(
        json.dumps(fdmprinter, indent=4) + "\n", encoding="utf-8", newline="\n")

# What the app's machine catalogue (CuraMachineCatalog) offers: a definition whose chain
# reaches fdmprinter, that is not an extruder train, is not marked hidden, whose full closure
# (machine chain plus the extruder trains it declares) resolves, and whose id does not name
# shared scaffolding (*_base, *_common) that other definitions inherit. Counting anything
# shorter here would report a different number than the picker shows.
definitions = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in files.items()}
inherited = {data.get("inherits") for data in definitions.values() if data.get("inherits")}
extruder_train = re.compile(r"_extruder(_\d+|_(left|right|\d+))?$")
max_inheritance_depth = 16


def parent_of(definition):
    inherits = definition.get("inherits")
    return inherits if isinstance(inherits, str) and inherits and inherits != "null" else None


def reaches_fdmprinter(definition_id: str) -> bool:
    visited = set()
    while definition_id and definition_id not in visited:
        if definition_id == "fdmprinter":
            return True
        visited.add(definition_id)
        definition = definitions.get(definition_id)
        if definition is None:
            return False
        definition_id = parent_of(definition)
    return False


def extruder_trains_of(definition) -> list:
    trains = (definition.get("metadata") or {}).get("machine_extruder_trains") or {}
    slots = sorted(trains, key=lambda slot: int(slot) if str(slot).isdigit() else 2 ** 31)
    return [
        str(trains[slot]).strip().removesuffix(".def.json")
        for slot in slots
        if str(trains[slot]).strip()
    ]


def walk_inheritance(definition_id: str) -> bool:
    guard = 0
    while definition_id is not None and guard < max_inheritance_depth:
        guard += 1
        definition = definitions.get(definition_id)
        if definition is None:
            return False
        definition_id = parent_of(definition)
    return True


def closure_resolves(machine_id: str) -> bool:
    if machine_id not in definitions:
        return False
    trains = set()
    current = machine_id
    guard = 0
    while current is not None and guard < max_inheritance_depth:
        guard += 1
        definition = definitions.get(current)
        if definition is None:
            return False
        trains.update(extruder_trains_of(definition))
        current = parent_of(definition)
    return all(walk_inheritance(train) for train in trains)


def is_shared_base(definition_id: str) -> bool:
    return definition_id in inherited and any(
        part in ("base", "common") for part in definition_id.split("_"))


candidates = [
    name
    for name, definition in definitions.items()
    if not is_shared_base(name)
    and not extruder_train.search(name)
    and reaches_fdmprinter(name)
    and definition.get("metadata", {}).get("visible", True) is not False
    and closure_resolves(name)
]
print("Validated Cura definition closure: " + " -> ".join(sorted(seen)))
print(f"Catalogue: {len(files)} definitions, {len(candidates)} candidate machines")
PY

cat > "$DEST/definitions/version.txt" <<EOF
Cura resources: $CURA_TAG
Setting version: 27
Files: $(ls "$DEST/definitions" | wc -l) definitions and extruder trains
EOF

printf 'Fetched and validated Cura %s resources into %s (%s)\n' "$CURA_TAG" "$DEST" "$(du -sh "$DEST" | cut -f1)"
