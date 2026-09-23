import re, sys
path = sys.argv[1]
per = {}
cur_type = None
cur_layer = None
for line in open(path, encoding='utf-8').read().splitlines():
    if line.startswith(';TYPE:'):
        cur_type = line[6:].strip()
        continue
    if line.startswith(';LAYER:'):
        cur_layer = int(line.split(':')[1])
        continue
    if line.startswith('G0') or line.startswith('G1'):
        if 'E' in line and cur_type and cur_layer is not None:
            per.setdefault(cur_layer, {}).setdefault(cur_type, 0)
            per[cur_layer][cur_type] += 1
print('layers with WALL-OUTER:')
for layer in sorted(per):
    if 'WALL-OUTER' in per[layer] or 'WALL-INNER' in per[layer]:
        print(layer, per[layer].get('WALL-OUTER', 0), 'outer,', per[layer].get('WALL-INNER', 0), 'inner, bead:', per[layer].get('BEAD-ANGLE-OVERHANG', 0))
