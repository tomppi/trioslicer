#!/usr/bin/env bash
# The Klipper side of this app may not reach into the side that was here first.
#
# The app slices for two machines: a printer at the far end of OctoPrint, which is usually
# Marlin, and the Klipper host running inside the app. The second was added later, and it is
# allowed to add to itself and not to change the first - a rule that was already broken once,
# when a shared default start G-code was replaced with Klipper's and left a Marlin printer
# being sent BED_MESH_CALIBRATE instead of loading its UBL mesh.
#
# This checks the dependency half of that rule: outside the app's own Klipper folders, nothing
# may import the Klipper packages. The files below are the deliberate exceptions - the places
# where the app has to know that both routes exist at all - and adding to this list is a
# decision, which is the point.
#
# Two more rules keep the two G-code routes apart from each other, because separate
# implementations that can call one another are not separate for long:
#
#   - neither route file may name the other, so neither can call the other;
#   - only the seam may name a route class at all, so every caller goes through
#     GcodeRoute.forFlavor and none can be written for one route alone.
#
# The other half - that Marlin *output* is unchanged - is MarlinRouteContractTest, which
# pins the two G-code texts as they were at TrioSlicer 1.4.0, the last release before Klipper
# existed, and holds every layer event's output in a golden file.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/tomppi/enderslicer"

# Where the two routes are chosen, and where the host is started. Each one has to import the
# Klipper side to do its job.
ALLOWED=(
  "MainActivity.kt"                 # starts the host when a printer is attached by USB
  "ui/IntegratedEnderSlicerApp.kt"  # creates the Klipper view model for the Print destination
  "ui/PrinterTabContent.kt"         # offers "This device" or "OctoPrint"
)

is_allowed() {
  local relative="$1"
  for allowed in "${ALLOWED[@]}"; do
    [ "$relative" = "$allowed" ] && return 0
  done
  return 1
}

violations=()
while IFS= read -r file; do
  relative="${file#"$SRC"/}"
  case "$relative" in
    printer/*|nativebridge/*|ui/klipper/*) continue ;;
  esac
  if grep -qE '^import com\.tomppi\.enderslicer\.(printer|nativebridge\.Klipper)' "$file"; then
    is_allowed "$relative" || violations+=("$relative")
  fi
done < <(find "$SRC" -name '*.kt')

if [ "${#violations[@]}" -gt 0 ]; then
  echo "the Klipper side has leaked into files that belong to the shared side:" >&2
  printf '  %s\n' "${violations[@]}" >&2
  echo >&2
  echo "Move the Klipper-specific part behind an interface in printer/, or add the file to" >&2
  echo "ALLOWED in this script if it is one of the places the routes are chosen." >&2
  exit 1
fi

ROUTES="$SRC/engine/gcode"
problems=()

# Naming the other route in a comment is how the two files explain themselves, and is
# wanted; importing it or constructing it is what would make them one implementation again.
if grep -qE "import .*\.KlipperGcodeRoute$|KlipperGcodeRoute\(" "$ROUTES/FrozenGcodeRoute.kt"; then
  problems+=("FrozenGcodeRoute.kt uses the Klipper route")
fi
if grep -qE "import .*\.FrozenGcodeRoute$|FrozenGcodeRoute\(" "$ROUTES/KlipperGcodeRoute.kt"; then
  problems+=("KlipperGcodeRoute.kt uses the frozen route")
fi

while IFS= read -r file; do
  relative="${file#"$SRC"/}"
  case "$relative" in
    engine/gcode/*) continue ;;
  esac
  if grep -qE "import .*\.(Frozen|Klipper)GcodeRoute$|(Frozen|Klipper)GcodeRoute\(" "$file"; then
    problems+=("$relative uses a route class instead of GcodeRoute.forFlavor")
  fi
done < <(find "$SRC" -name "*.kt")

if [ "${#problems[@]}" -gt 0 ]; then
  echo "the two G-code routes are not separate:" >&2
  printf "  %s\n" "${problems[@]}" >&2
  exit 1
fi

echo "route separation: no Klipper imports outside the Klipper folders"
echo "route separation: the frozen and Klipper routes do not call each other"
