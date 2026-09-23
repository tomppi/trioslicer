#!/usr/bin/env python3
"""Shape sweep: mushroom cap, snowman head, nested dome (negative), hook.
Per-layer outline-driven chain logic on both walls (mirrored for axis shapes):
  s = x(z+h) - x(z);  engage where 0 < s <= W (bead reach); s > W = too
  shallow (stack fallback); s <= 0 = receding/supported (normal walls).
Thin layers on shallow bands: h_eff = min(H, 0.5*W*tan(theta)).
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

def chain_value(s):
    f = min(1.0, max(0.60, s / W + 0.15))
    return f, f * W

def render(name, profile, title, mirror):
    """profile: list of xr per layer (right outline). Left mirrored if mirror."""
    layers = []
    z = 0.0
    for n in range(len(profile)):
        xr = profile[n]
        xp = profile[n - 1] if n > 0 else xr
        s = xr - xp
        eng = 0.0 < s <= W
        tc = 0.0  # theta-ish for the title
        if s > 0:
            tc = math.degrees(math.atan2(H, s))
        h_eff = H
        if eng and s > W * 0.5:
            h_eff = min(H, 0.5 * W * s / H * (W / H))  # placeholder, thin handled below
        # thin layer rule: recompute effective step to 0.5W
        if eng:
            h_eff = min(H, 0.5 * W * math.tan(math.atan2(H, s))) if s < W * 0.5 else H
        layers.append((n, z, xr, s, eng, h_eff))
        z += h_eff
    # draw at h=H rows for visual uniformity; annotate engagement
    fig, ax = plt.subplots(figsize=(5.2, 6.6))
    z = 0.0
    for (n, zn, xr, s, eng, h_eff) in layers:
        if eng:
            f, wc = chain_value(s)
            k = max(1, int(math.ceil((BASE * W + 0) / (1.35 * W))))
            for j in range(k):
                ax.add_patch(FancyBboxPatch((xr - (j + 1) * W, z), W, H,
                             boxstyle="round,pad=0,rounding_size=22", fc="#444", ec="none", zorder=2))
            ax.add_patch(FancyBboxPatch((xr - wc, z), wc, H,
                         boxstyle="round,pad=0,rounding_size=%g" % min(22, wc * 0.45), fc="#e8701a", ec="none",
                         zorder=3))
            if mirror:
                for j in range(k):
                    ax.add_patch(FancyBboxPatch((-xr + j * W, z), W, H,
                                 boxstyle="round,pad=0,rounding_size=22", fc="#444", ec="none", zorder=2))
                ax.add_patch(FancyBboxPatch((-xr, z), wc, H,
                             boxstyle="round,pad=0,rounding_size=%g" % min(22, wc * 0.45), fc="#e8701a", ec="none",
                             zorder=3))
        else:
            for j in range(BASE):
                ax.add_patch(FancyBboxPatch((xr - (j + 1) * W, z), W, H,
                             boxstyle="round,pad=0,rounding_size=22", fc="#555", ec="none", zorder=2))
                if mirror:
                    ax.add_patch(FancyBboxPatch((-xr + j * W, z), W, H,
                                 boxstyle="round,pad=0,rounding_size=22", fc="#555", ec="none", zorder=2))
        z += H
    n_eng = sum(1 for l in layers if l[4])
    ax.set_aspect("equal")
    span = max(abs(p) for p in profile) + 2.5 * W
    ax.set_xlim(-span if mirror else -span * 0.7, span)
    ax.set_ylim(-0.5 * W, z + 0.5 * W)
    ax.set_title(title + "\nengaged layers: %d / %d (chain=orange, walls=grey)" % (n_eng, len(layers)), fontsize=8)
    ax.set_xlabel("x (um)"); ax.set_ylabel("z (um)")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "shape-%s.png" % name), dpi=150)
    plt.close(fig)
    print("%-10s engaged %d/%d" % (name, n_eng, len(layers)))
    eng_rows = [(n, round(s), round(h_eff, 1)) for (n, zn, xr, s, eng, h_eff) in layers if eng]
    print("   engaged (layer, step, h_eff):", eng_rows[:12])

def mushroom():
    # stem radius 3W for 8 layers; cap: grow to 5W over 4 layers; cap side 5W x 4; top dome down to cone
    prof = [3 * W] * 8
    for i in range(4):
        prof.append(3 * W + (2 * W) * (i + 1) / 4.0)
    prof += [5 * W] * 4
    for i in range(4):
        prof.append(5 * W - (1.5 * W) * (i + 1) / 4.0)
    return prof, "Mushroom: stem -> 2W cap overhang -> cap side -> dome top"

def snowman():
    # body sphere max 4W; waist; head sphere max 3W
    prof = []
    for i in range(6):
        r = 4 * W * math.sin(0.9 * (i + 2) / 9.0)
        prof.append(min(4 * W, r))
    prof += [2.2 * W] * 2
    for i in range(5):
        prof.append(2.2 * W + (0.8 * W) * (i + 1) / 5.0)
    prof += [3 * W] * 2
    for i in range(4):
        prof.append(3 * W - (1.2 * W) * (i + 1) / 4.0)
    return prof, "Snowman: body -> waist -> head underside -> head top"

def nested_dome():
    # dome on its widest layer: every upper layer recessed -> no band
    prof = [4 * W] * 3
    for i in range(6):
        prof.append(4 * W - (2.5 * W) * (1 - math.cos(math.pi * (i + 1) / 12.0)))
    return prof, "Nested dome (NEGATIVE test: must stay unengaged)"

def hook():
    # wall goes out rapidly past bead reach (s > W) -> stack fallback, no chain
    prof = [3 * W] * 4
    prof += [3 * W + (1.6 * W), 3 * W + (2.4 * W)]  # big jumps at 2 layers
    prof += [5.4 * W] * 2
    prof.append(4.6 * W)  # recede
    return prof, "Hook/step: expansion > bead reach -> fallback (no chain)"

def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in [("mushroom", mushroom), ("snowman", snowman), ("dome", nested_dome), ("hook", hook)]:
        prof, title = fn()
        render(name, prof, title, mirror=True)

if __name__ == "__main__":
    main()
