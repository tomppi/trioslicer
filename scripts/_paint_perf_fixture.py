import struct, sys, math

def write_stl(path, triangles):
    with open(path, 'wb') as f:
        f.write(b'\0' * 80)
        f.write(struct.pack('<I', len(triangles)))
        for (a, b, c) in triangles:
            ux, uy, uz = b[0]-a[0], b[1]-a[1], b[2]-a[2]
            vx, vy, vz = c[0]-a[0], c[1]-a[1], c[2]-a[2]
            nx, ny, nz = uy*vz-uz*vy, uz*vx-ux*vz, ux*vy-uy*vx
            n = math.sqrt(nx*nx + ny*ny + nz*nz) or 1.0
            nx, ny, nz = nx/n, ny/n, nz/n
            f.write(struct.pack('<3f', nx, ny, nz))
            for v in (a, b, c):
                f.write(struct.pack('<3f', *v))
            f.write(struct.pack('<H', 0))

def box(x0, y0, z0, x1, y1, z1):
    t = []
    p = [(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0),(x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)]
    quads = [(0,1,2,3),(4,7,6,5),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)]
    for q in quads:
        t.append((p[q[0]], p[q[1]], p[q[2]]))
        t.append((p[q[0]], p[q[2]], p[q[3]]))
    return t

def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    n_prisms = int(sys.argv[2]) if len(sys.argv) > 2 else 500
    tris = []
    # plate 60x60x2 centred, top at z=32
    tris += box(-30, -30, 30, 30, 30, 32)
    # stem 4x4 from z=0 to z=30
    tris += box(-2, -2, 0, 2, 2, 30)
    write_stl(out_dir + '/model.stl', tris)

    prisms = []
    s = 0.9            # prism footprint
    spacing = 0.7      # < s so prisms overlap
    grid = int(math.ceil(math.sqrt(n_prisms)))
    for i in range(n_prisms):
        gx = (i % grid) - grid / 2
        gy = (i // grid) - grid / 2
        x0 = gx * spacing - s / 2
        y0 = gy * spacing - s / 2
        # prisms under the plate overhang, away from the stem
        cx, cy = x0 + s / 2, y0 + s / 2
        if abs(cx) < 6 and abs(cy) < 6:
            continue
        prisms += box(x0, y0, 26, x0 + s, y0 + s, 30)
    write_stl(out_dir + '/prism.stl', prisms)
    print('model triangles:', len(tris))
    print('prism boxes:', n_prisms, 'prism triangles:', len(prisms))

main()
