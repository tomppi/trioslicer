import re, math

# Port of NozzlePathBounds.printedBounds (1% per-axis trim, 2 iterations).
def printed_bounds(vertices):
    n = len(vertices) // 3
    if n < 2:
        return None
    keep = [True] * n
    kept = n
    for _ in range(2):
        if kept <= 2:
            break
        xs = [vertices[v*3] for v in range(n) if keep[v]]
        ys = [vertices[v*3+1] for v in range(n) if keep[v]]
        xs.sort(); ys.sort()
        lo = int(kept * 0.01)
        hi = int(kept * 0.99)
        if hi == lo:
            break
        minx, maxx, miny, maxy = xs[lo], xs[hi], ys[lo], ys[hi]
        remaining = 0
        for v in range(n):
            if not keep[v]:
                continue
            x, y = vertices[v*3], vertices[v*3+1]
            if minx <= x <= maxx and miny <= y <= maxy:
                remaining += 1
            else:
                keep[v] = False
        if remaining == kept:
            break
        kept = remaining
    if kept < 2:
        return None
    pts = [(vertices[v*3], vertices[v*3+1], vertices[v*3+2]) for v in range(n) if keep[v]]
    return (min(p[0] for p in pts), min(p[1] for p in pts), min(p[2] for p in pts),
            max(p[0] for p in pts), max(p[1] for p in pts), max(p[2] for p in pts))

def parse(path):
    verts = []
    for line in open(path, encoding='utf-8').read().splitlines():
        if not (line.startswith('G0') or line.startswith('G1')):
            continue
        if 'E' not in line:
            continue
        x = re.search(r'X(-?[0-9.]+)', line)
        y = re.search(r'Y(-?[0-9.]+)', line)
        if not (x and y):
            continue
        z = re.search(r'Z(-?[0-9.]+)', line)
        vz = float(z.group(1)) if z else 0.0
        verts.append((float(x.group(1)), float(y.group(1)), vz))
    return verts

# --- matrix helpers (column-major, android style: m = m * R post-multiply) ---
def ident():
    return [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]

def mul(a, b):
    r = [0.0]*16
    for c in range(4):
        for row in range(4):
            s = 0.0
            for k in range(4):
                s += a[k*4+row] * b[c*4+k]
            r[c*4+row] = s
    return r

def translate(m, x, y, z):
    t = ident()
    t[12], t[13], t[14] = x, y, z
    return mul(m, t)

def rotate_deg(m, deg, ax, ay, az):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    n = math.sqrt(ax*ax + ay*ay + az*az)
    x, y, z = ax/n, ay/n, az/n
    r = ident()
    r[0] = c + x*x*(1-c);     r[4] = x*y*(1-c) - z*s; r[8]  = x*z*(1-c) + y*s
    r[1] = y*x*(1-c) + z*s;   r[5] = c + y*y*(1-c);     r[9]  = y*z*(1-c) - x*s
    r[2] = z*x*(1-c) - y*s;   r[6] = z*y*(1-c) + x*s;   r[10] = c + z*z*(1-c)
    return mul(m, r)

def look_at(eye, center, up):
    fx, fy, fz = center[0]-eye[0], center[1]-eye[1], center[2]-eye[2]
    fl = math.sqrt(fx*fx+fy*fy+fz*fz)
    fx, fy, fz = fx/fl, fy/fl, fz/fl
    sx, sy, sz = up[1]*fz-up[2]*fy, up[2]*fx-up[0]*fz, up[0]*fy-up[1]*fx
    sl = math.sqrt(sx*sx+sy*sy+sz*sz)
    sx, sy, sz = sx/sl, sy/sl, sz/sl
    ux, uy, uz = fy*sz-fz*sy, fz*sx-fx*sz, fx*sy-fy*sx
    m = ident()
    m[0], m[1], m[2] = sx, ux, -fx
    m[4], m[5], m[6] = sy, uy, -fy
    m[8], m[9], m[10] = sz, uz, -fz
    m[12] = -(sx*eye[0] + sy*eye[1] + sz*eye[2])
    m[13] = -(ux*eye[0] + uy*eye[1] + uz*eye[2])
    m[14] = -(-fx*eye[0] - fy*eye[1] - fz*eye[2])
    return m

def perspective(fov_deg, aspect, near, far):
    f = 1.0 / math.tan(math.radians(fov_deg)/2)
    m = ident()
    m[0] = f/aspect
    m[5] = f
    m[10] = (far+near)/(near-far)
    m[11] = -1.0
    m[14] = (2*far*near)/(near-far)
    m[15] = 0.0
    return m

def apply(m, p):
    x, y, z = p
    return (m[0]*x + m[4]*y + m[8]*z + m[12],
            m[1]*x + m[5]*y + m[9]*z + m[13],
            m[2]*x + m[6]*y + m[10]*z + m[14],
            m[3]*x + m[7]*y + m[11]*z + m[15])

def main(path):
    verts = parse(path)
    print('extrusion vertices:', len(verts))
    flat = [c for p in verts for c in p]
    b = printed_bounds(flat)
    print('printed bounds:', tuple(round(v,2) for v in b) if b else None)
    if not b:
        return
    minx, miny, minz, maxx, maxy, maxz = b
    cx, cy, cz = (minx+maxx)/2, (miny+maxy)/2, (minz+maxz)/2
    radius = math.sqrt(max(maxx-minx,1)**2 + max(maxy-miny,1)**2 + max(maxz-minz,1)**2) * 0.5
    dist = max(radius * 2.8 / 1.0, 2.0)
    near = max(0.1, dist - radius*1.6)
    far = max(near+100, dist + radius*2.8 + 100)
    aspect = 1080.0/2340.0
    P = perspective(42.0, aspect, near, far)
    V = look_at((0.0, -dist, dist*0.58), (0.0,0.0,0.0), (0.0,0.0,1.0))
    S = ident()
    S = rotate_deg(S, 58.0, 1,0,0)
    S = rotate_deg(S, -32.0, 0,0,1)
    S = translate(S, -cx, -cy, -cz)
    M = mul(mul(P, V), S)
    # project the bounds corners + a sample
    corners = [(minx,miny,minz),(maxx,maxy,minz),(minx,maxy,maxz),(maxx,miny,maxz),
               (minx,miny,maxz),(maxx,maxy,maxz),(minx,maxy,minz),(maxx,miny,minz)]
    vis = 0
    for p in corners:
        x, y, z, w = apply(M, p)
        if w <= 0:
            continue
        nx, ny = x/w, y/w
        if -1 <= nx <= 1 and -1 <= ny <= 1 and -1 <= z/w <= 1:
            vis += 1
        print('corner', tuple(round(v,1) for v in p), '-> ndc', round(nx,2), round(ny,2), round(z/w,2), 'VISIBLE' if -1<=nx<=1 and -1<=ny<=1 else '')
    print('visible corners:', vis, '/', len(corners))
    # fraction of sample points visible
    step = max(1, len(verts)//500)
    seen = 0
    for p in verts[::step]:
        x, y, z, w = apply(M, p)
        if w <= 0:
            continue
        nx, ny, nz = x/w, y/w, z/w
        if -1 <= nx <= 1 and -1 <= ny <= 1 and -1 <= nz <= 1:
            seen += 1
    print('sample points visible: %.1f%%' % (100.0 * seen / (len(verts)//step)))

main(r'C:/Users/FREDRIK/Documents/PrintShare/print_20260821_192850_111.gcode')
