# Generates the brick-wall spike fixture battery:
#  - cliff<angle>: base block + wedge whose underside overhangs at the angle
#  - jump: block with ZERO overlap over the base (fail-closed case)
#  - ledge: block with 1 mm overlap (small-anchor staircase case)
#  - double70: two 70-degree wedges on opposite sides (two islands per layer)
#  - ring: block with a through-hole (skin territory, no bricks expected)
#  - dome: hemisphere cap over a base (curved overhang stress test)
import math
import os

def _write(path, tris):
    lines = ["solid fixture"]
    for (a, b, c) in tris:
        u = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
        v = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        lines.append("  facet normal %g %g %g" % n)
        lines.append("    outer loop")
        for p in (a, b, c):
            lines.append("      vertex %g %g %g" % p)
        lines.append("    endloop")
        lines.append("  endfacet")
    lines.append("endsolid fixture")
    with open(path, "w") as f:
        f.write("\n".join(lines))
    print("wrote %s (%d triangles)" % (path, len(tris)))

def box(x0, x1, y0, y1, z0, z1, tris):
    def quad(a, b, c, d):
        tris.append((a, b, c))
        tris.append((a, c, d))
    quad((x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0))  # -z
    quad((x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1))  # +z
    quad((x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0))  # -x
    quad((x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1))  # +x
    quad((x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1))  # -y
    quad((x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0))  # +y

def wedge(x0, x1, y0, y1, z0, angle_deg, tris):
    # solid ABOVE the ramp: vertical face at x0, top at z1 = z0 + (x1-x0)*tan
    z1 = z0 + (x1 - x0) * math.tan(math.radians(angle_deg))
    A = (x0, y0, z0); D = (x0, y0, z1); C = (x1, y0, z1)
    A2 = (x0, y1, z0); D2 = (x0, y1, z1); C2 = (x1, y1, z1)
    def tri(a, b, c):
        tris.append((a, b, c))
    tri(A, C, D)      # y0 cap (-y)
    tri(A2, D2, C2)   # y1 cap (+y)
    tri(A, C2, C)     # ramp underside
    tri(A, A2, C2)    # ramp underside
    tri(A, D, D2)     # x0 face (-x)
    tri(A, D2, A2)    # x0 face
    tri(D, C, C2)     # top (+z)
    tri(D, C2, D2)    # top

def cliff(path, angle_deg):
    tris = []
    box(0.0, 20.0, 0.0, 20.0, 0.0, 2.0, tris)
    wedge(20.0, 24.0, 0.0, 20.0, 2.0, angle_deg, tris)
    _write(path, tris)

def jump(path):
    tris = []
    box(0.0, 20.0, 0.0, 20.0, 0.0, 2.0, tris)
    box(20.0, 24.0, 0.0, 20.0, 2.0, 6.0, tris)  # zero overlap
    _write(path, tris)

def ledge(path):
    tris = []
    box(0.0, 20.0, 0.0, 20.0, 0.0, 2.0, tris)
    box(19.0, 23.0, 0.0, 20.0, 2.0, 6.0, tris)  # 1 mm overlap
    _write(path, tris)

def double_cliff(path):
    tris = []
    box(0.0, 20.0, 0.0, 20.0, 0.0, 2.0, tris)
    wedge(20.0, 24.0, 0.0, 9.0, 2.0, 70.0, tris)
    wedge(20.0, 24.0, 11.0, 20.0, 2.0, 70.0, tris)
    _write(path, tris)

def ring(path):
    tris = []
    # outer solid with a square through-hole
    box(0.0, 10.0, 0.0, 30.0, 0.0, 8.0, tris)
    box(10.0, 20.0, 0.0, 10.0, 0.0, 8.0, tris)
    box(10.0, 20.0, 20.0, 30.0, 0.0, 8.0, tris)
    box(20.0, 30.0, 0.0, 30.0, 0.0, 8.0, tris)
    _write(path, tris)

def dome(path):
    tris = []
    box(0.0, 20.0, 0.0, 20.0, 0.0, 2.0, tris)
    cx, cy, cz, r = 10.0, 10.0, 2.0, 10.0
    nlat, nlon = 8, 16
    def pt(lat_i, lon_i):
        lat = math.pi * (lat_i / nlat) / 2  # 0..pi/2 from top
        lon = 2 * math.pi * (lon_i / nlon)
        return (cx + r * math.sin(lat) * math.cos(lon),
                cy + r * math.sin(lat) * math.sin(lon),
                cz + r * math.cos(lat))
    def tri(a, b, c):
        # ensure outward winding using the sphere center trick
        u = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
        v = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        mid = ((a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3)
        outward = (mid[0] - cx, mid[1] - cy, mid[2] - cz)
        if n[0] * outward[0] + n[1] * outward[1] + n[2] * outward[2] < 0:
            a, b, c = a, c, b
        tris.append((a, b, c))
    top = (cx, cy, cz + r)
    for j in range(nlon):
        p = pt(1, j); q = pt(1, (j + 1) % nlon)
        tri(p, top, q)
    for i in range(1, nlat):
        for j in range(nlon):
            a = pt(i, j); b = pt(i, (j + 1) % nlon)
            c = pt(i + 1, j); d = pt(i + 1, (j + 1) % nlon)
            tri(a, b, c)
            tri(b, d, c)
    # the equator sits on the base top (z=2.0), so the bottom is already closed
    _write(path, tris)

def mushroom(path):
    # stem + hemisphere cap: the cap overhangs the stem all around
    tris = []
    box(8.0, 12.0, 8.0, 12.0, 0.0, 4.0, tris)
    cx, cy, cz, r = 10.0, 10.0, 4.0, 10.0
    nlat, nlon = 8, 16
    def pt(lat_i, lon_i):
        lat = math.pi * (lat_i / nlat) / 2
        lon = 2 * math.pi * (lon_i / nlon)
        return (cx + r * math.sin(lat) * math.cos(lon),
                cy + r * math.sin(lat) * math.sin(lon),
                cz + r * math.cos(lat))
    def tri(a, b, c):
        u = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
        v = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        mid = ((a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3)
        outward = (mid[0] - cx, mid[1] - cy, mid[2] - cz)
        if n[0] * outward[0] + n[1] * outward[1] + n[2] * outward[2] < 0:
            a, b, c = a, c, b
        tris.append((a, b, c))
    top = (cx, cy, cz + r)
    for j in range(nlon):
        p = pt(1, j); q = pt(1, (j + 1) % nlon)
        tri(p, top, q)
    for i in range(1, nlat):
        for j in range(nlon):
            a = pt(i, j); b = pt(i, (j + 1) % nlon)
            c = pt(i + 1, j); d = pt(i + 1, (j + 1) % nlon)
            tri(a, b, c)
            tri(b, d, c)
    _write(path, tris)

def shelf(path):
    # vertical wall with a 6 mm deep shelf sticking out at mid height
    tris = []
    box(0.0, 4.0, 0.0, 20.0, 0.0, 10.0, tris)
    box(4.0, 10.0, 0.0, 20.0, 4.0, 5.0, tris)
    _write(path, tris)

out_dir = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".build", "brick-test"))
os.makedirs(out_dir, exist_ok=True)
cliff(os.path.join(out_dir, "cliff45.stl"), 45.0)
cliff(os.path.join(out_dir, "cliff60.stl"), 60.0)
cliff(os.path.join(out_dir, "cliff70.stl"), 70.0)
cliff(os.path.join(out_dir, "cliff80.stl"), 80.0)
jump(os.path.join(out_dir, "jump.stl"))
ledge(os.path.join(out_dir, "ledge.stl"))
double_cliff(os.path.join(out_dir, "double70.stl"))
ring(os.path.join(out_dir, "ring.stl"))
mushroom(os.path.join(out_dir, "mushroom.stl"))
shelf(os.path.join(out_dir, "shelf.stl"))
dome(os.path.join(out_dir, "dome.stl"))
