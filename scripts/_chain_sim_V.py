#!/usr/bin/env python3
"""Chain V-profile simulation: vertical -> steep descending leg (75 deg) ->
V valley -> steep ascending leg -> vertical. The steep legs reproduce the
'outer wall with 1 bead' case: the chain is nearly vertical (step ~54 um),
each bead deeply welded into the 2 beads under it.
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
    theta = 75.0
    t = math.radians(theta)
    s = H / math.tan(t)
    f_chain = min(1.0, max(0.60, s / W + 0.15))
    wc = f_chain * W
    n_leg = 5
    n_pre, n_post = 2, 2
    # descending leg: chain center moves +s per layer (outward), rows grow n*s
    xc0 = BASE * W - PRESS + wc / 2.0
    xs = [xc0 + n * s for n in range(0, n_leg)]
    # valley: one layer at the bottom (turn), then ascending leg: chain center
    # returns inward by s per layer (the outline moves back toward the wall)
    xs2 = [xs[-1] + s - 1.0 * s * i for i in range(1, n_leg)]
    # rows behind: wedge grows on descent by n*s; on ascent the row narrows
    rows_d = [(xc - wc / 2 + PRESS) for xc in xs]
    rows_a = [(xc - wc / 2 + PRESS) for xc in xs2]

    fig, ax = plt.subplots(figsize=(5.8, 7.2))
    rr = 22.0
    z = 0.0
    # pre-band vertical
    for _ in range(n_pre):
        for j in range(BASE):
            ax.add_patch(FancyBboxPatch((j * W, z), W, H, boxstyle="round,pad=0,rounding_size=%g" % rr, fc="#444", ec="none", zorder=2))
        z += H
    rows = []
    # descending leg
    for (xc, row_end) in zip(xs, rows_d):
        k = max(1, int(math.ceil(row_end / (1.35 * W))))
        d_i = row_end / k
        rows.append((k, d_i, row_end))
        for j in range(k):
            ax.add_patch(FancyBboxPatch((j * d_i, z), d_i, H, boxstyle="round,pad=0,rounding_size=%g" % min(rr, d_i * 0.45), fc="#444", ec="none", zorder=2))
        ax.add_patch(FancyBboxPatch((xc - wc / 2, z), wc, H, boxstyle="round,pad=0,rounding_size=%g" % min(rr, wc * 0.45), fc="#e8701a", ec="none", zorder=3))
        z += H
    # ascending leg
    for (xc, row_end) in zip(xs2, rows_a):
        k = max(1, int(math.ceil(row_end / (1.35 * W))))
        d_i = row_end / k
        rows.append((k, d_i, row_end))
        for j in range(k):
            ax.add_patch(FancyBboxPatch((j * d_i, z), d_i, H, boxstyle="round,pad=0,rounding_size=%g" % min(rr, d_i * 0.45), fc="#444", ec="none", zorder=2))
        ax.add_patch(FancyBboxPatch((xc - wc / 2, z), wc, H, boxstyle="round,pad=0,rounding_size=%g" % min(rr, wc * 0.45), fc="#e8701a", ec="none", zorder=3))
        z += H
    # post-band vertical at the final outline (outer face = xs2[-1] + wc/2)
    x_outer = xs2[-1] + wc / 2.0
    for _ in range(n_post):
        for j in range(BASE):
            ax.add_patch(FancyBboxPatch((x_outer - (j + 1) * W, z), W, H, boxstyle="round,pad=0,rounding_size=%g" % rr, fc="#444", ec="none", zorder=2))
        z += H
    ax.axvline(0, color="#2d6cdf", lw=1.2, ls="--", zorder=1)
    ax.set_aspect("equal")
    ax.set_xlim(-W, max(xs) + 2.0 * W)
    ax.set_ylim(-0.6 * W, z + 0.6 * W)
    kk = [k for (k, d, e) in rows]
    weld = wc - s
    ax.set_title("V profile: 2 vert + 5x75deg down + 5x75deg up + 2 vert\n"
                 "chain %.0fx%.0f (f=%.2f) step %.0f weld_cc=%.0fum (%.0f%%)\nrows k %d->%d->%d (outline-driven, valley = inset union)"
                 % (wc, H, f_chain, s, weld, weld / W * 100, kk[0], max(kk), kk[-1]), fontsize=8)
    ax.set_xlabel("x (um)"); ax.set_ylabel("z (um)")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "reeb-V.png"), dpi=150)
    print("wrote reeb-V.png | k down:", kk[:n_leg], "k up:", kk[n_leg:], "| weld_cc %.0fum" % weld)

if __name__ == "__main__":
    main()