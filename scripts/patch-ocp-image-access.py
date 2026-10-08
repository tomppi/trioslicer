# Copy of the patch that runs in the CAD payload build tree (/root/occt-port), kept in the
# repository so the recipe survives the tree it was written against. See
# docs/CAD_PAYLOAD_ANDROID.md: run it in that tree, then rebuild OCP and restage the payload.
# Safe to re-run - it refuses to apply twice.
import os, shutil, sys
# Give Image_PixMap a way to actually read its pixels from Python.
#
# pywrap binds Standard_Byte* as the type it points at, not as an address: pybind11's
# arithmetic caster takes unsigned char* and yields the *value* of the first byte. So
# Image_PixMap::Data(), ChangeData() and Row() all "work" and return nonsense - Data() on a
# freshly rendered frame came back as 77, which is the blue channel of the top-left pixel.
# ctypes.string_at() on such a value segfaults the engine, which is what happened before the
# PPM fallback was written.
#
# Two host-side reproductions of the same rule, on pybind11 3.1.0:
#   give() with the first byte 0xAB  -> 171, and with 0x00 -> 0
#   a function taking unsigned char* refuses an integer address (range-checked as a byte)
#
# ReadBytes() is the accessor that was missing, and the only change the renderer needs:
# the whole buffer as a Python bytes object, so Pillow can take the frame with no PPM file,
# no OCCT serializer and no second trip through the disk.
OCP = "/root/occt-port/OCP/OCP"

def class_block(path, decl):
    lines = open(path, encoding="utf-8").read().split("\n")
    start = next((i for i, l in enumerate(lines) if decl in l), None)
    if start is None:
        return None, None, None
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if "py::class_<" in lines[i]:
            end = i
            break
    return lines, start, end

def patch(filename, decl, marker, block, label, after="// methods"):
    path = os.path.join(OCP, filename)
    if not os.path.exists(path):
        print("NO FILE: %s" % filename); return False
    lines, start, end = class_block(path, decl)
    if lines is None:
        print("MISSING CLASS: %s" % label); return False
    if any(marker in l for l in lines[start:end]):
        print("already applied: %s" % label); return True
    try:
        at = next(i for i in range(start, end) if after in lines[i]) + 1
    except StopIteration:
        at = start + 1
    lines[at:at] = block
    shutil.copy(path, path + ".pixels.bak")
    open(path, "w", encoding="utf-8").write("\n".join(lines))
    print("applied: %s" % label); return True

ok = patch("Image.cpp", "py::class_<Image_PixMap ,", '"ReadBytes"', [
    '        .def("ReadBytes",',
    '             [](const Image_PixMap &theImage) {',
    '                 return py::bytes(reinterpret_cast<const char *>(theImage.Data()),',
    '                                  (Py_ssize_t )theImage.SizeBytes());',
    '             },',
    '             R"#(The whole pixel buffer as bytes, SizeBytes() of them, in memory order. The rows are bottom-up unless SetTopDown(True) was called, and a row is SizeRowBytes() bytes wide - pass that stride to a raw image decoder rather than assuming SizeX() * SizePixelBytes(). This is the accessor pywrap could not emit: Standard_Byte* binds as the byte it points at, so Data() returns a pixel value instead of an address.)#" )',
], "Image_PixMap.ReadBytes")
sys.exit(0 if ok else 1)
