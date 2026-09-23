#!/usr/bin/env python3
"""Chain-overhang cross-section simulation v2 - REAL bead dimensions.

A 3D-printed layer bead is a rounded rectangle: width = flow * line_width,
height = layer_height (the nozzle at the layer plane fixes the top; flow
widens the bead, it does not thicken the layer). Contacts:
  chain-chain weld (horizontal overlap at the layer plane):
      weld_cc = f_chain * W - s              [um], require > 0
  chain-row weld same layer (side contact):
      weld_cr = press                        [um] by construction
  chain rows above the row below (press into the row's outer edge):
      vertical faces meet; overlap = press   [um]
Level: inner face fixed x = 0 (vertical); every top at n*h_eff (flat).
Axis-aligned faces from x=0 to x=row_end make the section void-free by
construction; the wedge is exactly row_end(n) - BASE*W = n*s.
"""
import math, os, csv
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

W = 400.0
H = 200.0
BASE = 2
K_CAP = 1.35
S_TARGET = 0.50
PRESS = 0.05 * W
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "chain-sim")

def run(theta_deg, n_layers=8, weld_target=0.15):
    t = math.radians(theta_deg)
    h_eff = min(H, S_TARGET * W * math.tan(t)) if math.tan(t) < H / (S_TARGET * W) else H
    s = h_eff / math.tan(t)
    # chain flow: width overlap weld = f*W - s, at least weld_target*W
    f_chain = min(1.0, max(0.60, s / W + weld_target))
    wc = f_chain * W
    x0 = BASE * W - PRESS + wc / 2
    rows = []
    for n in range(n_layers):
        xc = x0 + n * s
        row_end = xc - wc / 2 + PRESS
        k = max(1, int(math.ceil(row_end / (K_CAP * W))))
        d_i = row_end / k
        rows.append((n, k, d_i, xc, row_end))
        if n > 0:
            weld_cc = wc - s
            if weld_cc <= 0:
                raise AssertionError("chain detached at %.0f deg" % theta_deg)
    last = rows[-1]
    return {
        "theta": theta_deg, "h_eff": h_eff, "s": s, "f_chain": f_chain, "x0": x0,
        "w_chain": f_chain * W, "weld_cc": f_chain * W - s,
        "weld_pct": (f_chain * W - s) / W * 100.0,
        "f_inner_start": rows[0][2] / W, "f_inner_end": last[2] / W,
        "k_start": rows[0][1], "k_end": last[1], "rows": rows,
    }

def draw(res, path):
    fig, ax = plt.subplots(figsize=(5.4, 6.6))
    s, he = res["s"], res["h_eff"]
    wc = res["w_chain"]
    rr = 22.0  # corner rounding, um
    for (n, k, d_i, xc, row_end) in res["rows"]:
        z0 = n * he
        for j in range(k):
            x = (j + 0.5) * d_i
            ax.add_patch(FancyBboxPatch((x - d_i / 2, z0), d_i, he,
                         boxstyle="round,pad=0,rounding_size=%g" % min(rr, he * 0.45, d_i * 0.45),
                         fc="#444", ec="none", zorder=2))
    for n in range(len(res["rows"])):
        xc = res["x0"] + n * s
        z0 = n * he
        ax.add_patch(FancyBboxPatch((xc - wc / 2, z0), wc, he,
                     boxstyle="round,pad=0,rounding_size=%g" % min(rr, he * 0.45, wc * 0.45),
                     fc="#e8701a", ec="none", zorder=3))
    ax.axvline(0, color="#2d6cdf", lw=1.2, ls="--", zorder=1)
    ax.axhline(len(res["rows"]) * he, color="#2d6cdf", lw=1.2, ls=":", zorder=1)
    ax.set_aspect("equal")
    ax.set_xlim(-1.8 * W, res["x0"] + len(res["rows"]) * s + 2.2 * W)
    ax.set_ylim(-0.5 * W, (len(res["rows"]) + 1.3) * he)
    ax.set_title("chain bend %.0f deg  (bead = %.0f x %.0f um)" % (res["theta"], wc, he)
                 + "\nf_chain=%.2f weld_cc=%.0fum (%.0f%%)  f_inner=%.2f->%.2f k=%d->%d"
                 % (res["f_chain"], res["weld_cc"], res["weld_pct"],
                    res["f_inner_start"], res["f_inner_end"], res["k_start"], res["k_end"]),
                 fontsize=8)
    ax.set_xlabel("x (um)"); ax.set_ylabel("z (um)")
    fig.tight_layout(); fig.savefig(path, dpi=150); plt.close(fig)

def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for theta in [10, 15, 20, 25, 30, 45, 60, 70, 80]:
        r = run(theta)
        rows.append({k: v for k, v in r.items() if not isinstance(v, (list, tuple))})
        draw(r, os.path.join(OUT, "reeb-%02d.png" % theta))
        print("%3d deg | layer %.0f x %.0f | step %5.0f | chain %.3f (%.0f x %.0f) weld_cc=%3.0fum(%4.1f%%) | inner %.2f->%.2f k %d->%d"
              % (r["theta"], r["f_chain"] * W, r["h_eff"], r["s"], r["f_chain"],
                 r["w_chain"], r["h_eff"], r["weld_cc"], r["weld_pct"],
                 r["f_inner_start"], r["f_inner_end"], r["k_start"], r["k_end"]))
    with open(os.path.join(OUT, "values-v2.csv"), "w", newline="") as fh:
        wtr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        wtr.writeheader(); wtr.writerows(rows)
    print("\nwritten", os.path.abspath(OUT))

if __name__ == "__main__":
    main()