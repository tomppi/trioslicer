import re, sys
path = sys.argv[1]
types = {}
cur_type = None
cur_layer = 0
layer0_extr = 0
for line in open(path, encoding='utf-8').read().splitlines():
    if line.startswith(';TYPE:'):
        cur_type = line[6:].strip()
        types.setdefault(cur_type, 0)
        continue
    if line.startswith(';LAYER:'):
        cur_layer = int(line.split(':')[1])
        continue
    if line.startswith('G0') or line.startswith('G1'):
        if 'E' in line and cur_type is not None:
            types[cur_type] = types.get(cur_type, 0) + 1
            if cur_layer == 0:
                layer0_extr += 1
print(path.split('/')[-1], '->', sorted(types.items()), 'layer0 extrusions:', layer0_extr)
