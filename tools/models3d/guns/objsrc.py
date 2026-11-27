# Reads a Quaternius OBJ (data only: its own tiny parser) and splits it into its loose pieces.
import numpy as np

def read_obj(path):
    V = []; F = []; M = []; cur = None
    for l in open(path, errors='replace'):
        p = l.split()
        if not p: continue
        if p[0] == 'v': V.append([float(c) for c in p[1:4]])
        elif p[0] == 'usemtl': cur = p[1]
        elif p[0] == 'f': F.append([int(c.split('/')[0]) - 1 for c in p[1:]]); M.append(cur)
    return np.array(V), F, M

def pieces(V, F, M, tol=1e-4):
    """Weld equal points, then group faces into connected pieces. Returns (V welded, faces, mats, piece index per face)."""
    key = {}; remap = np.empty(len(V), int); W = []
    for i, v in enumerate(V):
        k = tuple(np.round(v / tol).astype(int))
        if k not in key: key[k] = len(W); W.append(v)
        remap[i] = key[k]
    F2 = []; M2 = []
    for f, m in zip(F, M):   # welding can leave a point twice in a face
        g = []
        for i in (int(remap[i]) for i in f):
            if i not in g: g.append(i)
        if len(g) >= 3: F2.append(g); M2.append(m)
    F, M = F2, M2
    par = list(range(len(W)))
    def find(a):
        while par[a] != a: par[a] = par[par[a]]; a = par[a]
        return a
    for f in F:
        r = find(f[0])
        for i in f[1:]: par[find(i)] = r
    roots = {}; comp = []
    for f in F:
        r = find(f[0])
        comp.append(roots.setdefault(r, len(roots)))
    return np.array(W), F, M, comp
