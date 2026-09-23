import math, struct

def sphere_tris(cx, cy, cz, r, lat_n, lon_n):
    out = []
    def pt(lat, lon):
        y = math.sin(lat)
        rr = math.cos(lat)
        return (cx + r * rr * math.cos(lon), cy + r * rr * math.sin(lon), cz + r * y)
    for i in range(lat_n):
        lat0 = -math.pi / 2 + math.pi * i / lat_n
        lat1 = -math.pi / 2 + math.pi * (i + 1) / lat_n
        for j in range(lon_n):
            lon0 = 2 * math.pi * j / lon_n
            lon1 = 2 * math.pi * (j + 1) / lon_n
            p0, p1 = pt(lat0, lon0), pt(lat0, lon1)
            p2, p3 = pt(lat1, lon0), pt(lat1, lon1)
            out.append((p0, p1, p2))
            out.append((p1, p3, p2))
    return out

tris = sphere_tris(0, 0, 8.0, 8.0, 24, 36)
tris += sphere_tris(0, 0, 22.0, 8.0, 24, 36)  # second ball, 6mm above the first top (16) -> contact at 14.. hmm
# overlapping: lower sphere z 0..16, upper z 13..29 -> overlap 3mm
tris = sphere_tris(0, 0, 8.0, 8.0, 24, 36)
tris += sphere_tris(0, 0, 21.0, 8.0, 24, 36)

with open(r'C:/Users/FREDRIK/Documents/enderslicercura/.build/brick-test/snowman.stl', 'wb') as f:
    f.write(b'snowman fixture')
    f.write(b' ' * (80 - len('snowman fixture')))
    f.write(struct.pack('<I', len(tris)))
    for t in tris:
        n = (0.0, 0.0, 0.0)
        f.write(struct.pack('<12fH', n[0], n[1], n[2], t[0][0], t[0][1], t[0][2], t[1][0], t[1][1], t[1][2], t[2][0], t[2][1], t[2][2], 0))
print('snowman.stl written, tris', len(tris))
