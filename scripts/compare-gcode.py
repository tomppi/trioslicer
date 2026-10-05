#!/usr/bin/env python3
"""Compare two G-code files as tool paths.

The question this answers is not "are these files the same" - they never are.
It is "did the two slicers lay down the same plastic in the same places", which
is what decides whether a print comes out the same.

So the comparison works on extrusion segments, not on the file:

  * A segment is one extruding move: where it started, where it ended, in X, Y
    and Z. Every segment from one file is looked for in the other, within a
    tolerance.
  * Segments are matched as a multiset, not a sequence. Two slicers can emit the
    same wall in a different order, or start a closed loop at a different point
    - that is the seam, and it moves where the loop begins without moving any of
    the plastic. Neither shows up here, deliberately.
  * What does show up is a segment that exists in one file and nowhere in the
    other, or one that exists in both at measurably different coordinates. That
    is a real difference in the tool path.

Usage:
    compare-gcode.py A.gcode B.gcode [--tolerance-mm 0.001] [--json out.json]
"""

import argparse
import json
import math
import re
import sys
from collections import defaultdict

WORD = re.compile(r"([A-Z])(-?\d*\.?\d+(?:[eE][-+]?\d+)?)")


def parse(path):
    """Read a G-code file into layers of extrusion segments."""
    layers = []
    layer = None
    absolute_position = True
    absolute_extrusion = True
    x = y = z = 0.0
    e = 0.0
    current_type = ""

    def new_layer(number, z_value):
        return {"number": number, "z": z_value, "segments": [], "types": defaultdict(int)}

    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.strip()
            if not line:
                continue

            if line.startswith(";"):
                upper = line.upper()
                if upper.startswith(";LAYER:"):
                    try:
                        number = int(line.split(":", 1)[1].strip())
                    except ValueError:
                        number = len(layers)
                    layer = new_layer(number, z)
                    layers.append(layer)
                elif upper.startswith(";TYPE:"):
                    current_type = line.split(":", 1)[1].strip()
                elif upper.startswith(";LAYER_CHANGE"):
                    # PrusaSlicer and OrcaSlicer mark layers this way, with the Z
                    # on a following ";Z:" line rather than in the marker. Without
                    # this the whole file parses as one layer, which reads as a
                    # perfect per-layer match and hides everything a per-layer
                    # comparison is for.
                    layer = new_layer(len(layers), z)
                    layers.append(layer)
                elif upper.startswith(";Z:"):
                    try:
                        layer_z = float(line.split(":", 1)[1].strip())
                    except ValueError:
                        layer_z = None
                    if layer_z is not None:
                        if layer is None:
                            layer = new_layer(0, layer_z)
                            layers.append(layer)
                        layer["z"] = layer_z
                        z = layer_z
                continue

            command = line.split(";", 1)[0].strip()
            if not command:
                continue
            verb = command.split(" ", 1)[0]

            if verb in ("G90",):
                absolute_position = True
                continue
            if verb in ("G91",):
                absolute_position = False
                continue
            if verb in ("M82",):
                absolute_extrusion = True
                continue
            if verb in ("M83",):
                absolute_extrusion = False
                continue
            if verb in ("G92",):
                for letter, value in WORD.findall(command):
                    if letter == "E":
                        e = float(value)
                    elif letter == "X":
                        x = float(value)
                    elif letter == "Y":
                        y = float(value)
                    elif letter == "Z":
                        z = float(value)
                continue
            if verb not in ("G0", "G1"):
                continue

            values = dict(WORD.findall(command))
            nx, ny, nz, ne = x, y, z, e
            if "X" in values:
                nx = float(values["X"]) if absolute_position else x + float(values["X"])
            if "Y" in values:
                ny = float(values["Y"]) if absolute_position else y + float(values["Y"])
            if "Z" in values:
                nz = float(values["Z"]) if absolute_position else z + float(values["Z"])
            if "E" in values:
                ne = float(values["E"]) if absolute_extrusion else e + float(values["E"])

            extruding = "E" in values and (
                ne > e + 1e-9 if absolute_extrusion else float(values["E"]) > 1e-9
            )

            if extruding and (abs(nx - x) > 1e-9 or abs(ny - y) > 1e-9):
                if layer is None:
                    layer = new_layer(0, z)
                    layers.append(layer)
                layer["segments"].append((x, y, z, nx, ny, nz))
                layer["types"][current_type or "UNSET"] += 1

            x, y, z, e = nx, ny, nz, ne

    return layers


def key(segment, tolerance):
    """A segment's identity at a given tolerance, direction included."""
    x0, y0, z0, x1, y1, z1 = segment
    scale = 1.0 / tolerance if tolerance > 0 else 1e6
    return (
        round(x0 * scale), round(y0 * scale), round(z0 * scale),
        round(x1 * scale), round(y1 * scale), round(z1 * scale),
    )


def summarise(layers, tolerance):
    counts = defaultdict(int)
    for layer in layers:
        for segment in layer["segments"]:
            counts[key(segment, tolerance)] += 1
    return counts


def coverage(a, b):
    """How many of a's segments have a partner in b, and how many are left."""
    matched = 0
    unmatched = []
    remaining = dict(b)
    for segment_key, count in a.items():
        available = remaining.get(segment_key, 0)
        used = min(count, available)
        matched += used
        if used:
            remaining[segment_key] -= used
        for _ in range(count - used):
            unmatched.append(segment_key)
    leftovers = sum(v for v in remaining.values() if v > 0)
    return matched, unmatched, leftovers


def path_length(layers):
    total = 0.0
    for layer in layers:
        for x0, y0, z0, x1, y1, z1 in layer["segments"]:
            total += math.dist((x0, y0, z0), (x1, y1, z1))
    return total


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("a")
    parser.add_argument("b")
    parser.add_argument("--tolerance-mm", type=float, default=0.001)
    parser.add_argument("--json")
    parser.add_argument("--label-a", default="A")
    parser.add_argument("--label-b", default="B")
    parser.add_argument("--max-layers", type=int, default=12,
                        help="how many differing layers to list in full")
    parser.add_argument("--geometric", action="store_true",
                        help="compare per-layer coverage instead of exact segments")
    parser.add_argument("--cell-mm", type=float, default=0.2,
                        help="grid size for the geometric comparison")
    args = parser.parse_args()

    if args.geometric:
        mean = geometric(args.a, args.b, args.cell_mm, args.max_layers)
        return 0 if mean > 0.98 else 1

    tolerance = args.tolerance_mm
    layers_a = parse(args.a)
    layers_b = parse(args.b)

    total_a = sum(len(l["segments"]) for l in layers_a)
    total_b = sum(len(l["segments"]) for l in layers_b)

    print("=" * 78)
    print(f"{args.label_a}: {args.a}")
    print(f"{args.label_b}: {args.b}")
    print(f"tolerance: {tolerance} mm")
    print("=" * 78)
    print(f"layers            {len(layers_a):>8}   {len(layers_b):>8}")
    print(f"extrusion segments{total_a:>8}   {total_b:>8}")
    print(f"tool-path length  {path_length(layers_a):>8.2f}   {path_length(layers_b):>8.2f}  mm")

    z_a = [round(l["z"], 4) for l in layers_a]
    z_b = [round(l["z"], 4) for l in layers_b]
    if z_a == z_b:
        print(f"layer Z values    {'identical':>8}   {len(z_a)} layers")
    else:
        differing = [i for i, (p, q) in enumerate(zip(z_a, z_b)) if p != q]
        print(f"layer Z values    DIFFER at {len(differing)} of {max(len(z_a), len(z_b))} layers")
        for index in differing[:args.max_layers]:
            print(f"    layer {index}: {args.label_a}={z_a[index]}  {args.label_b}={z_b[index]}")

    keys_a = summarise(layers_a, tolerance)
    keys_b = summarise(layers_b, tolerance)
    matched_ab, unmatched_a, leftovers_b = coverage(keys_a, keys_b)
    matched_ba, unmatched_b, leftovers_a = coverage(keys_b, keys_a)

    print()
    print(f"segments of {args.label_a} found in {args.label_b}: {matched_ab}/{total_a}"
          f"  ({100.0 * matched_ab / total_a if total_a else 100:.3f}%)")
    print(f"segments of {args.label_b} found in {args.label_a}: {matched_ba}/{total_b}"
          f"  ({100.0 * matched_ba / total_b if total_b else 100:.3f}%)")

    if not unmatched_a and not unmatched_b:
        print()
        print("RESULT: the two files lay down the same plastic, to within"
              f" {tolerance} mm, in the same places.")
        identical = True
    else:
        print()
        print(f"RESULT: {len(unmatched_a)} segment(s) only in {args.label_a},"
              f" {len(unmatched_b)} only in {args.label_b}.")
        identical = False

    # Per-layer detail, so a difference can be located rather than merely counted.
    per_layer = []
    for index in range(max(len(layers_a), len(layers_b))):
        la = layers_a[index] if index < len(layers_a) else None
        lb = layers_b[index] if index < len(layers_b) else None
        ka = summarise([la], tolerance) if la else {}
        kb = summarise([lb], tolerance) if lb else {}
        na = sum(ka.values())
        nb = sum(kb.values())
        m, ua, lb_left = coverage(ka, kb)
        entry = {
            "index": index,
            "z_a": la["z"] if la else None,
            "z_b": lb["z"] if lb else None,
            "segments_a": na,
            "segments_b": nb,
            "matched": m,
            "only_a": len(ua),
            "only_b": lb_left,
            "types_a": dict(la["types"]) if la else {},
            "types_b": dict(lb["types"]) if lb else {},
        }
        per_layer.append(entry)

    differing = [e for e in per_layer if e["only_a"] or e["only_b"]]
    if differing:
        print()
        print(f"layers with any difference: {len(differing)} of {len(per_layer)}")
        print(f"{'layer':>6} {'Z':>8} {'app':>7} {'pc':>7} {'only app':>9} {'only pc':>9}")
        for entry in differing[:args.max_layers]:
            z = entry["z_a"] if entry["z_a"] is not None else entry["z_b"]
            print(f"{entry['index']:>6} {z:>8.3f} {entry['segments_a']:>7} {entry['segments_b']:>7}"
                  f" {entry['only_a']:>9} {entry['only_b']:>9}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump({
                "a": args.a, "b": args.b, "tolerance_mm": tolerance,
                "layers_a": len(layers_a), "layers_b": len(layers_b),
                "segments_a": total_a, "segments_b": total_b,
                "matched_a_in_b": matched_ab, "matched_b_in_a": matched_ba,
                "identical": identical,
                "z_identical": z_a == z_b,
                "per_layer": per_layer,
            }, handle, indent=1)
        print(f"\nwrote {args.json}")

    return 0 if identical else 1


# ---------------------------------------------------------------------------
# Geometric comparison
#
# The engine is not deterministic run to run (see docs/gcode-correctness/cura.md),
# so a segment-by-segment diff cannot be the primary measure: two runs of the SAME
# engine agree on only ~74% of segments. What can still be asked is the question
# that decides whether a print comes out the same - did the same plastic land in
# the same places - and that is measured by rasterising each layer and comparing
# coverage. Two prints whose coverage agrees to within a fraction of a nozzle
# width are the same print, whatever order the moves came out in.
# ---------------------------------------------------------------------------

def rasterise(layers, cell_mm):
    """Per-layer sets of occupied cells, plus the cell's segment count."""
    grids = []
    for layer in layers:
        cells = set()
        for x0, y0, z0, x1, y1, z1 in layer["segments"]:
            steps = int(math.hypot(x1 - x0, y1 - y0) / (cell_mm * 0.5)) + 1
            for step in range(steps + 1):
                t = step / steps if steps else 0.0
                cx = int((x0 + (x1 - x0) * t) // cell_mm)
                cy = int((y0 + (y1 - y0) * t) // cell_mm)
                cells.add((cx, cy))
        grids.append(cells)
    return grids


def geometric(a_path, b_path, cell_mm, max_layers, z_tolerance=0.02):
    a = parse(a_path)
    b = parse(b_path)
    ga = rasterise(a, cell_mm)
    gb = rasterise(b, cell_mm)

    # Align layers by Z, never by index. A slicer that puts the start G-code's
    # prime line in its own ;LAYER: block emits one more layer than one that does
    # not, and every layer after it shifts by one. An index-aligned comparison
    # then pits the first layer against the second, which reads as a catastrophic
    # difference and is nothing of the sort: the tool paths are in the same place,
    # the layer counters are not.
    def z_of(layers):
        return [round(l["z"], 4) for l in layers]

    za, zb = z_of(a), z_of(b)
    pairs = []
    used = set()
    unmatched = 0
    for index, z in enumerate(za):
        best, best_gap = None, z_tolerance
        for j, other in enumerate(zb):
            if j in used:
                continue
            gap = abs(other - z)
            if gap <= best_gap:
                best, best_gap = j, gap
        if best is None:
            unmatched += 1
            pairs.append((index, None, z))
        else:
            used.add(best)
            pairs.append((index, best, z))

    print("=" * 78)
    print(f"GEOMETRIC comparison at a {cell_mm} mm grid, layers aligned by Z")
    print(f"A: {a_path}")
    print(f"B: {b_path}")
    print("=" * 78)
    print(f"layers: A={len(ga)} B={len(gb)}  matched by Z={len(ga) - unmatched}  unmatched={unmatched}")

    print(f"{'layer':>6} {'Z':>7} {'app cells':>10} {'pc cells':>10} {'both':>9} {'IoU':>7} {'app only':>9} {'pc only':>9}")
    ious = []
    worst = []
    for index, other, z in pairs:
        ca = ga[index] if index < len(ga) else set()
        cb = gb[other] if other is not None and other < len(gb) else set()
        both = len(ca & cb)
        union = len(ca | cb)
        iou = (both / union) if union else 1.0
        ious.append(iou)
        worst.append((iou, index, z, len(ca), len(cb), both, len(ca - cb), len(cb - ca)))
        if index < max_layers or iou < 0.90:
            print(f"{index:>6} {z:>7.3f} {len(ca):>10} {len(cb):>10} {both:>9} {iou:>7.4f} {len(ca - cb):>9} {len(cb - ca):>9}")

    mean_iou = sum(ious) / len(ious) if ious else 1.0
    print()
    print(f"mean per-layer IoU: {mean_iou:.4f}")
    print(f"worst layer IoU:    {min(ious):.4f}" if ious else "")
    worst.sort()
    for iou, index, z, na, nb, both, oa, ob in worst[:5]:
        print(f"   worst {index:>4} Z={z:>7.3f}  IoU={iou:.4f}  app={na} pc={nb} both={both} app-only={oa} pc-only={ob}")
    return mean_iou

if __name__ == "__main__":
    sys.exit(main())
