# Prints each GLB's parts, materials, triangles and extents.  Usage: python glbstat.py <glb>...
import sys, json, struct
import numpy as np
for path in sys.argv[1:]:
    d = open(path, 'rb').read(); n = struct.unpack('<I', d[12:16])[0]; j = json.loads(d[20:20 + n]); bin_ = d[20 + n + 8:]
    def acc(i):
        a = j['accessors'][i]; bv = j['bufferViews'][a['bufferView']]
        dt = {5126: 'f4', 5123: 'u2', 5125: 'u4', 5121: 'u1'}[a['componentType']]; k = {'SCALAR': 1, 'VEC3': 3, 'VEC2': 2}[a['type']]
        return np.frombuffer(bin_, dt, a['count'] * k, bv.get('byteOffset', 0) + a.get('byteOffset', 0)).reshape(-1, k)
    tot = 0; lo = np.full(3, 1e9); hi = -lo; rows = []
    for nd in j['nodes']:
        if 'mesh' not in nd: continue
        assert not any(k in nd for k in ('translation', 'rotation', 'scale', 'matrix')), nd
        t = 0; ms = []; l = np.full(3, 1e9); h = -l
        for p in j['meshes'][nd['mesh']]['primitives']:
            assert 'NORMAL' in p['attributes'] and p.get('mode', 4) == 4
            t += len(acc(p['indices'])) // 3; ms.append(j['materials'][p['material']]['name'])
            P = acc(p['attributes']['POSITION']); l = np.minimum(l, P.min(0)); h = np.maximum(h, P.max(0))
        tot += t; lo = np.minimum(lo, l); hi = np.maximum(hi, h); rows.append((nd['name'], t, ms, l, h))
    print(path.split('/')[-1], 'triangles', tot, 'x %.3f..%.3f y %.3f..%.3f z %.3f..%.3f' % (lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))
    for nm, t, ms, l, h in rows: print('   %-22s %5d  %-28s y %.3f..%.3f z %.3f..%.3f' % (nm, t, '+'.join(ms), l[1], h[1], l[2], h[2]))
