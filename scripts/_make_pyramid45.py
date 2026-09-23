import struct

# Truncated pyramid widening upward: base 20x20 at z=0, top 40x40 at z=10.
# Each face leans outward at 45 degrees (10 mm outward over 10 mm height).
base_half = 10.0
top_half = 20.0
z_bottom = 0.0
z_top = 10.0

triangles = []


def add(v0, v1, v2):
    triangles.append((v0, v1, v2))


corners_bottom = [
    (-base_half, -base_half, z_bottom),
    (base_half, -base_half, z_bottom),
    (base_half, base_half, z_bottom),
    (-base_half, base_half, z_bottom),
]
corners_top = [
    (-top_half, -top_half, z_top),
    (top_half, -top_half, z_top),
    (top_half, top_half, z_top),
    (-top_half, top_half, z_top),
]

# Four side faces (each split into two triangles).
for i in range(4):
    b0 = corners_bottom[i]
    b1 = corners_bottom[(i + 1) % 4]
    t0 = corners_top[i]
    t1 = corners_top[(i + 1) % 4]
    add(b0, b1, t1)
    add(b0, t1, t0)

# Bottom cap so the model is closed.
add(corners_bottom[0], corners_bottom[2], corners_bottom[1])
add(corners_bottom[0], corners_bottom[3], corners_bottom[2])
# Top cap.
add(corners_top[0], corners_top[1], corners_top[2])
add(corners_top[0], corners_top[2], corners_top[3])

out = bytearray()
out += b"\0" * 80
out += struct.pack("<I", len(triangles))
for v0, v1, v2 in triangles:
    (ax, ay, az), (bx, by, bz), (cx, cy, cz) = v0, v1, v2
    ux, uy, uz = bx - ax, by - ay, bz - az
    vx, vy, vz = cx - ax, cy - ay, cz - az
    nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
    out += struct.pack("<3f", nx, ny, nz)
    for x, y, z in (v0, v1, v2):
        out += struct.pack("<3f", x, y, z)
    out += struct.pack("<H", 0)

path = r"C:\Users\FREDRIK\Documents\enderslicercura\.build\brick-test\pyramid45.stl"
with open(path, "wb") as f:
    f.write(out)
print("wrote", path, len(triangles), "triangles")
