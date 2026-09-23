import math

def write(path):
    tris = []
    def tri(a, b, c):
        tris.append((a, b, c))
    def quad(a, b, c, d):
        tri(a, b, c)
        tri(a, c, d)
    # 70x70x2 base
    quad((0,0,0),(0,70,0),(70,70,0),(70,0,0))
    quad((0,0,2),(70,0,2),(70,70,2),(0,70,2))
    quad((0,0,0),(0,0,2),(0,70,2),(0,70,0))
    quad((70,0,0),(70,70,0),(70,70,2),(70,0,2))
    quad((0,0,0),(70,0,0),(70,0,2),(0,0,2))
    quad((0,70,0),(0,70,2),(70,70,2),(70,70,0))
    # spherical cap: top at z=8, rim z=2, rim radius 26.8
    # centre below: R = 8 - c, R*sin(lat_rim) = 26.8, cos(lat_rim) = (2-c)/R
    # -> 60 - 12c = 26.8^2 -> c = -54.853, R = 62.853
    cx, cy, c, R = 35.0, 35.0, -54.853333, 62.853333
    lat_rim = math.acos((2.0 - c) / R)
    nlat, nlon = 16, 32
    def pt(i, j):
        lat = lat_rim * (1.0 - i / nlat)  # i=0 rim, i=nlat top
        lon = 2 * math.pi * (j / nlon)
        return (cx + R * math.sin(lat) * math.cos(lon),
                cy + R * math.sin(lat) * math.sin(lon),
                c + R * math.cos(lat))
    top = (cx, cy, 8.0)
    for j in range(nlon):
        p = pt(1, j); q = pt(1, (j + 1) % nlon)
        tri(p, top, q)
    for i in range(1, nlat):
        for j in range(nlon):
            a = pt(i, j); b = pt(i, (j + 1) % nlon)
            d = pt(i + 1, j); e = pt(i + 1, (j + 1) % nlon)
            tri(a, b, d)
            tri(b, e, d)
    lines = ["solid broad-dome"]
    for (a, b, cc) in tris:
        u = (b[0]-a[0], b[1]-a[1], b[2]-a[2])
        v = (cc[0]-a[0], cc[1]-a[1], cc[2]-a[2])
        n = (u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
        mid = ((a[0]+b[0]+cc[0])/3, (a[1]+b[1]+cc[1])/3, (a[2]+b[2]+cc[2])/3)
        out = (mid[0]-cx, mid[1]-cy, mid[2]-c)
        if n[0]*out[0] + n[1]*out[1] + n[2]*out[2] < 0:
            a, b, cc = a, cc, b
        lines.append("  facet normal %g %g %g" % n)
        lines.append("    outer loop")
        for pp in (a, b, cc):
            lines.append("      vertex %g %g %g" % pp)
        lines.append("    endloop")
        lines.append("  endfacet")
    lines.append("endsolid broad-dome")
    with open(path, "w") as f:
        f.write("\n".join(lines))
    print("wrote", path, len(tris), "triangles, lat_rim=%.1f deg" % math.degrees(lat_rim))

write(r"C:\Users\FREDRIK\Documents\enderslicercura\.build\curvi-test\broad-dome.stl")
