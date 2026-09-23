#!/usr/bin/env python3
"""Chain L-profile simulation: vertical wall -> 45 deg chain band -> vertical.
Per-layer: outline-driven (chain at that layer's outline, rows = insets).
Entry: chain pressed into the base wall (press = 5%% w). Exit: last chain
bead's top face == first plain layer's bottom face (weld at the layer plane).
"""
import math, os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

W, H = 400.0, 200.0
BASE = 2
PRESS = 0.05 * W
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "chain-sim")

def main():
    theta = 45.0
    t = math.radians(theta)
    s = H / math.tan(t)
    f_chain = min(1.0, max(0.60, s / W + 0.15))
    wc = f_chain * W
    n_band = 6
    n_pre = 2
    n_post = 3
    # per layer: (kind, row_end, k, d_i, xc)
    layers = []
    # pre-band: vertical wall, outer face at BASE*W; chain "virtual" position
    xc0 = BASE * W - PRESS + wc / 2.0   # chain center on first band layer
    for n in range(-n_pre, 0):
        layers.append(("pre", n, BASE * W, BASE, W, None, 0.0))
    for n in range(0, n_band):
        xc = xc0 + n * s
        row_end = xc - wc / 2.0 + PRESS
        k = max(1, int(math.ceil(row_end / (1.35 * W))))
        d_i = row_end / k
        layers.append(("band", n, row_end, k, d_i, xc, s))
    # post-band: vertical wall at the final outline; outer face = last xc + wc/2
    x_outer = xc0 + (n_band - 1) * s + wc / 2.0
    for n in range(n_band, n_band + n_post):
        layers.append(("post", n, None, BASE, None, None, 0.0))

    fig, ax = plt.subplots(figsize=(5.6, 7.0))
    rr = 22.0
    z = 0.0
    for (kind, n, row_end, k, d_i, xc, step) in layers:
        if kind == "pre":
            for j in range(k):
                ax.add_patch(FancyBboxPatch((j * W, z), W, H,
                             boxstyle="round,pad=0,rounding_size=%g" % rr, fc="#444", ec="none", zorder=2))
        elif kind == "band":
            for j in range(k):
                ax.add_patch(FancyBboxPatch((j * d_i, z), d_i, H,
                             boxstyle="round,pad=0,rounding_size=%g" % min(rr, d_i * 0.45), fc="#444", ec="none", zorder=2))
            ax.add_patch(FancyBboxPatch((xc - wc / 2, z), wc, H,
                         boxstyle="round,pad=0,rounding_size=%g" % min(rr, wc * 0.45), fc="#e8701a", ec="none", zorder=3))
        else:
            for j in range(k):
                ax.add_patch(FancyBboxPatch((x_outer - (j + 1) * W, z), W, H,
                             boxstyle="round,pad=0,rounding_size=%g" % rr, fc="#444", ec="none", zorder=2))
        z += H
    ax.axvline(0, color="#2d6cdf", lw=1.2, ls="--", zorder=1)
    ax.set_aspect("equal")
    ax.set_xlim(-W, x_outer + 2.0 * W)
    ax.set_ylim(-0.6 * W, z + 0.6 * W)
    ax.set_title("L profile: 2 vertical + 6 x 45deg chain + 3 vertical\n"
                 "chain %.0fx%.0f (f=%.2f)  step %.0f  rows k 2->%d  (no extra-bead code needed: outline-driven)"
                 % (wc, H, f_chain, s, max(l[3] for l in layers if l[0] == "band")), fontsize=8)
    ax.set_xlabel("x (um)"); ax.set_ylabel("z (um)")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "reeb-L.png"), dpi=150)
    print("wrote reeb-L.png; per-band k:", [l[3] for l in layers if l[0] == "band"],
          "| chain bead %.0fx%.0f" % (wc, H))

if __name__ == "__main__":
    main()
