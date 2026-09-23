import re

def read_stl(path):
    raw = open(path, 'rb').read(100)
    if raw.startswith(b'solid'):
        text = (raw + open(path, 'rb').read()).decode('utf-8', 'replace')
        tris = re.findall(r'vertex\s+([-0-9.eE+]+)\s+([-0-9.eE+]+)\s+([-0-9.eE+]+)', text)
        pts = [(float(a), float(b), float(c)) for a, b, c in tris]
        return [tuple(pts[i:i+3]) for i in range(0, len(pts) - 2, 3)]
    import struct
    with open(path, 'rb') as f:
        f.read(80)
        n = struct.unpack('<I', f.read(4))[0]
        out = []
        for _ in range(n):
            data = f.read(50)
            tri = struct.unpack('<12fH', data)[:12]
            out.append(((tri[0],tri[1],tri[2]),(tri[3],tri[4],tri[5]),(tri[6],tri[7],tri[8])))
        return out

for name in ['cliff70.stl','cliff80.stl','dome.stl','ring.stl']:
    tris = read_stl(r'C:/Users/FREDRIK/Documents/enderslicercura/.build/brick-test/' + name)
    xs = [c[0] for t in tris for c in t]
    ys = [c[1] for t in tris for c in t]
    zs = [c[2] for t in tris for c in t]
    print(name, 'tris', len(tris), 'x', min(xs), max(xs), 'y', min(ys), max(ys), 'z', min(zs), max(zs))
