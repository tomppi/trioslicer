import sys
src, dst, name = sys.argv[1], sys.argv[2], sys.argv[3]
data = open(src, "rb").read()
with open(dst, "w") as f:
    f.write("// generated from " + src + "\n")
    f.write("static const unsigned char " + name + "[] = {\n")
    for i in range(0, len(data), 12):
        f.write("  " + ", ".join("0x%02x" % b for b in data[i:i+12]) + ",\n")
    f.write("};\n")
    f.write("static const unsigned int " + name + "_len = " + str(len(data)) + ";\n")
print("embedded", len(data), "bytes")
