import re

def read_stl(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    tris = re.findall(r'vertex\s+([-0-9.eE+]+)\s+([-0-9.eE+]+)\s+([-0-9.eE+]+)', text)
    pts = [(float(a), float(b), float(c)) for a, b, c in tris]
    return [tuple(pts[i:i+3]) for i in range(0, len(pts) - 2, 3)]

def cross_section(tris, z):
    segs = []
    for tri in tris:
        zs = [p[2] for p in tri]
        if min(zs) <= z <= max(zs) and max(zs) > min(zs):
            pts = []
            for a, b in ((tri[0], tri[1]), (tri[1], tri[2]), (tri[2], tri[0])):
                za, zb = a[2], b[2]
                if za == zb:
                    continue
                t = (z - za) / (zb - za)
                if 0.0 <= t <= 1.0:
                    pts.append((a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])))
            if len(pts) == 2:
                segs.append(tuple(pts))
    xs = [p[0] for s in segs for p in s]
    ys = [p[1] for s in segs for p in s]
    return (min(xs), max(xs), min(ys), max(ys), len(segs)) if segs else None

tris = read_stl(r'C:/Users/FREDRIK/Documents/enderslicercura/.build/brick-test/cliff70.stl')
for z in [2.1, 3.0, 4.0, 4.2, 4.4, 5.0, 6.0, 8.0, 10.0, 12.5]:
    print('z', z, cross_section(tris, z))
