# Subdivision-surface bodies: a low-poly, all-quad control cage per part; the shape is its Catmull-Clark surface.
# The grip's cage has box topology: a front grid, a back grid and five loops that follow the outline between them
# (face break, front roll, seam, back roll, back break). Its vertices start on the pass-4 surface and are then moved
# by cagefit.py; the moved cage is stored in 8b-cage.json next to this file.
import json, os
from lib import *
from mathutils.bvhtree import BVHTree

HERE = os.path.dirname(os.path.abspath(__file__))
NX, NY, RINGS = [int(v) for v in os.environ.get('CAGE_SHAPE', '2 3 1').split()]   #       # cells across and along the grip; loops between the two grids (1: the seam only)
V5 = (5, 9, 3)                # the dense pass-5 cage, kept only as the surface the minimal cage starts from
LEVELS = 3                    # export level (three levels at most)

def set_crease(me, crease):
    if not crease: return
    a = me.attributes.new('crease_edge', 'FLOAT', 'EDGE')
    for e in me.edges:
        k = tuple(sorted(e.vertices))
        if k in crease: a.data[e.index].value = crease[k]

def oriented(F):
    """The faces with one consistent winding, found from the topology alone (so it never depends on the positions)."""
    F = [tuple(f) for f in F]; ed = {}
    for fi, f in enumerate(F):
        for a, b in zip(f, f[1:] + f[:1]): ed.setdefault(frozenset((a, b)), []).append(fi)
    done = {0}; todo = [0]
    while todo:
        fi = todo.pop(); f = F[fi]
        for a, b in zip(f, f[1:] + f[:1]):
            for fj in ed[frozenset((a, b))]:
                if fj in done: continue
                g = F[fj]
                if any(x == a and y == b for x, y in zip(g, g[1:] + g[:1])): F[fj] = g[::-1]
                done.add(fj); todo.append(fj)
    return F

def cage_obj(name, V, F, crease, material='plastic', orient=True, fmat=None):
    me = bpy.data.meshes.new(name); me.from_pydata([tuple(map(float, v)) for v in V], [], oriented(F) if orient else [tuple(f) for f in F])
    for m_ in ([material] if isinstance(material, str) else material): me.materials.append(mat(m_))
    if fmat is not None: me.polygons.foreach_set('material_index', [int(i) for i in fmat])
    set_crease(me, crease)
    ob = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(ob)
    return ob

def subdivide(name, V, F, crease, levels=LEVELS, material='plastic', sharp=50.0, orient=True, fmat=None):
    """The cage's subdivision surface as a mesh object. Its first len(V) vertices are the cage's, on the limit surface."""
    ob = cage_obj(name + '_cage_tmp', V, F, crease, material, orient, fmat)
    m = ob.modifiers.new('sd', 'SUBSURF'); m.levels = levels; m.render_levels = levels; m.subdivision_type = 'CATMULL_CLARK'
    dg = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(ob.evaluated_get(dg)); me.name = name
    bpy.data.objects.remove(ob)
    out = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(out)
    bm = bmesh.new(); bm.from_mesh(me); bmesh.ops.recalc_face_normals(bm, faces=bm.faces); lim = math.radians(sharp)
    for f in bm.faces: f.smooth = True
    for e in bm.edges:
        if len(e.link_faces) == 2: e.smooth = e.calc_face_angle(0.0) < lim
    bm.to_mesh(me); bm.free()
    return out

def limit_points(V, F, crease, levels=LEVELS):
    ob = subdivide('tmp_limit', V, F, crease, levels); n = len(ob.data.vertices)
    A = np.empty(n * 3); ob.data.vertices.foreach_get('co', A); bpy.data.objects.remove(ob)
    return A.reshape(-1, 3)

def subdiv_matrix(V, F, crease, levels=LEVELS):
    """S with (subdivided vertices) = S @ (cage vertices): subdivision is linear in the cage for a fixed topology."""
    n = len(V); cols = []
    for a in range(0, n, 3):
        E = np.zeros((n, 3))
        for c in range(3):
            if a + c < n: E[a + c, c] = 1
        L = limit_points(E, F, crease, levels)
        for c in range(3):
            if a + c < n: cols.append(L[:, c])
    return np.array(cols).T

def auto_crease(V, F, value, angle=50.0):
    V = np.asarray(V, float); nrm = []
    for f in F:
        p = V[list(f)]; nn = np.cross(p[1] - p[0], p[2] - p[0]) + np.cross(p[2] - p[0], p[3] - p[0]); nrm.append(nn / np.linalg.norm(nn))
    ed = {}
    for fi, f in enumerate(F):
        for a, b in zip(f, f[1:] + f[:1]): ed.setdefault(tuple(sorted((a, b))), []).append(fi)
    c = math.cos(math.radians(angle))
    return {k: value for k, fs in ed.items() if len(fs) == 2 and abs(np.dot(nrm[fs[0]], nrm[fs[1]])) < c}

# ------------------------------------------------------------------ grip
def perim(k):
    k %= 2 * (NX + NY)
    if k <= NX: return NX - k, 0
    if k <= NX + NY: return 0, k - NX
    if k <= 2 * NX + NY: return k - NX - NY, NY
    return NX, NY - (k - 2 * NX - NY)

def coons(B):
    """Fill a (NX+1) x (NY+1) grid from its perimeter points B[k] (transfinite interpolation, then relaxed)."""
    G = np.zeros((NX + 1, NY + 1, B.shape[1])); known = np.zeros((NX + 1, NY + 1), bool)
    for k in range(len(B)): i, j = perim(k); G[i, j] = B[k]; known[i, j] = True
    for i in range(1, NX):
        for j in range(1, NY):
            u, v = i / NX, j / NY
            G[i, j] = ((1 - v) * G[i, 0] + v * G[i, NY] + (1 - u) * G[0, j] + u * G[NX, j]
                       - ((1 - u) * (1 - v) * G[0, 0] + u * (1 - v) * G[NX, 0] + (1 - u) * v * G[0, NY] + u * v * G[NX, NY]))
    for _ in range(6):
        H = G.copy()
        for i in range(1, NX):
            for j in range(1, NY): H[i, j] = 0.5 * G[i, j] + 0.125 * (G[i - 1, j] + G[i + 1, j] + G[i, j - 1] + G[i, j + 1])
        G = H
    return G

def grip_topology():
    M = 2 * (NX + NY); ng = (NX + 1) * (NY + 1)
    fg = lambda i, j: j * (NX + 1) + i
    ring = lambda r, k: ng + r * M + (k % M)                 # seam only, or front roll, seam, back roll
    bg = lambda i, j: ng + RINGS * M + j * (NX + 1) + i
    F = []
    for i in range(NX):
        for j in range(NY):
            F.append((fg(i, j), fg(i, j + 1), fg(i + 1, j + 1), fg(i + 1, j)))
            F.append((bg(i, j), bg(i + 1, j), bg(i + 1, j + 1), bg(i, j + 1)))
    loops = [[fg(*perim(k)) for k in range(M)]] + [[ring(r, k) for k in range(M)] for r in range(RINGS)] + [[bg(*perim(k)) for k in range(M)]]
    for A, B in zip(loops, loops[1:]):
        for k in range(M): F.append((A[k], B[k], B[(k + 1) % M], A[(k + 1) % M]))
    return F, loops

def grip_targets(P, I, ref):
    """Cage vertex positions on the reference (pass-4) surface, plus creases and the vertices on the flat inner wall."""
    P = np.asarray(P, float); n = len(P); nrm, w, Rf, Rb, zs = I['nrm'], I['w'], I['Rf'], I['Rb'], I['zs']
    dg = bpy.context.evaluated_depsgraph_get(); bvh = BVHTree.FromObject(ref, dg)
    seg = np.hypot(*(np.roll(P, -1, 0) - P).T); s = np.concatenate([[0], np.cumsum(seg)])[:n]
    wall = np.where(w < 1e-3)[0]; i_end = int(wall.min()) if wall.min() > n // 2 else int(np.where(np.diff(wall) > 1)[0][0] + 1 if len(np.where(np.diff(wall) > 1)[0]) else wall.min())
    # corners: top inner (start of the outline), top outer, the lobe's lower outer end, bottom inner (where the wall starts)
    c0 = int(np.argmin(np.hypot(P[:, 0] - P[:, 0].max(), P[:, 1] - P[:, 1][P[:, 0] > P[:, 0].max() - 0.01].max())))
    c3 = int(np.argmin(np.hypot(P[:, 0] - P[:, 0].max(), P[:, 1] - P[:, 1][P[:, 0] > P[:, 0].max() - 0.01].min())))
    c1 = int(np.argmax(-P[:, 0] + 0.6 * P[:, 1])); c2 = int(np.argmax(-0.6 * P[:, 0] - P[:, 1]))
    def span(a, b, m):
        L = (s[b] - s[a]) % (s[-1] + seg[-1]); tot = s[-1] + seg[-1]
        return [int(np.argmin(np.abs(((s - s[a]) % tot) - L * t / m))) for t in range(m)]
    idx = span(c0, c1, NX) + span(c1, c2, NY) + span(c2, c3, NX) + span(c3, c0, NY)
    idx = np.array(idx); M = len(idx)
    def cast(o, d):
        hit = bvh.ray_cast(Vector(o), Vector(d))
        return None if hit[0] is None else np.array(hit[0])
    def zray(xy, sign):
        h = cast((xy[0], xy[1], 8 * sign), (0, 0, -sign))
        return h[2] if h is not None else np.nan
    Pi, Ni, wi = P[idx], nrm[idx], w[idx]
    grids = []; rolls = []
    for sign, R in ((1, Rf), (-1, Rb)):
        inset = np.minimum(wi * R[idx] * 0.7, 1.15)
        B = Pi - Ni * inset[:, None]
        G = coons(B)
        Z = np.zeros((NX + 1, NY + 1))
        for i in range(NX + 1):
            for j in range(NY + 1):
                q = G[i, j] - 0.004 * np.array([1.0, 0.0]) if (i == NX) else G[i, j]
                Z[i, j] = zray(q, sign)
        if np.isnan(Z).any():   # a ray that slipped past an edge: take the nearest valid neighbour
            Zf = Z.copy(); ok = ~np.isnan(Z)
            for i, j in zip(*np.where(~ok)):
                d = [(abs(i - a) + abs(j - b), Z[a, b]) for a, b in zip(*np.where(ok))]; Zf[i, j] = min(d)[1]
            Z = Zf
        grids.append(np.dstack([G, Z]))
        zb = np.array([Z[perim(k)] for k in range(M)])
        roll = []
        for k in range(M):
            z1 = zs[idx[k]] + 0.55 * (zb[k] - zs[idx[k]])
            h = cast((Pi[k][0] + Ni[k][0] * 1.5, Pi[k][1] + Ni[k][1] * 1.5, z1), (-Ni[k][0], -Ni[k][1], 0))
            roll.append(h if h is not None and np.hypot(*(h[:2] - Pi[k])) < 1.5 else np.array([Pi[k][0], Pi[k][1], z1]))
        rolls.append(np.array(roll))
    seam = np.column_stack([Pi, zs[idx]])
    order = lambda G: np.array([G[i, j] for j in range(NY + 1) for i in range(NX + 1)])
    T = np.vstack([order(grids[0])] + ([rolls[0], seam, rolls[1]] if RINGS == 3 else [seam]) + [order(grids[1])])
    F, loops = grip_topology()
    crease = {}
    for lp in (loops[0], loops[-1]):          # crisp edges where the flat inner wall meets the face and the back
        for k in range(M):
            c = 1 - 0.5 * (wi[k] + wi[(k + 1) % M])
            if c > 0.3: crease[tuple(sorted((lp[k], lp[(k + 1) % M])))] = float(min(1.0, c))
    wallv = sorted({lp[k] for lp in loops for k in range(M) if wi[k] < 1e-3})
    return T, F, crease, dict(wall=wallv, seam=loops[1 + RINGS // 2], idx=idx.tolist())

def fit_limit(T, F, crease, iters=8):
    """Move the cage until its limit surface passes through the target points."""
    C = T.copy()
    for _ in range(iters):
        L = limit_points(C, F, crease)[:len(T)]; C += T - L
    return C, float(np.abs(limit_points(C, F, crease)[:len(T)] - T).max())

STATE = {}
REG = []; SIDE = [0]          # every cage that makes up the model, for the cage export and the wire still
def register(name, C, F, crease, mirror=True):
    REG.append(dict(name=name, C=np.array(C, float), F=[tuple(f) for f in F], crease=dict(crease), side=SIDE[0], mirror=mirror))

def subd_part(name, T, F, crease, levels=LEVELS, material='plastic', fmat=None, fit=True, mirror=True):
    """A small part as a subdivision cage whose surface passes through the points T."""
    T = np.array(T, float); C = fit_limit(T, F, crease, 10)[0] if fit else T
    register(name, C, F, crease, mirror)
    return subdivide(name, C, F, crease, levels, material, fmat=fmat)

def box_faces(nx):
    """Quads of a box nx cells long: vertices ordered [k][j][i] with k, j in 0..1 and i in 0..nx."""
    v = lambda i, j, k: (k * 2 + j) * (nx + 1) + i; F = []
    for i in range(nx):
        F += [(v(i, 0, 0), v(i + 1, 0, 0), v(i + 1, 1, 0), v(i, 1, 0)), (v(i, 0, 1), v(i, 1, 1), v(i + 1, 1, 1), v(i + 1, 0, 1))]
        F += [(v(i, j, 0), v(i, j, 1), v(i + 1, j, 1), v(i + 1, j, 0)) for j in (0, 1)]
    F += [(v(i, 0, 0), v(i, 1, 0), v(i, 1, 1), v(i, 0, 1)) for i in (0, nx)]
    return F

def ring_part(name, at, rows, bottom, top, n=8, material='plastic', row_mats=None, cap_mat=0, crease_rows=(), levels=2):
    """A turned part from a handful of points: rings of n points at (radius, z) rows, closed by a centre point at
    each end with four quads. The rings are widened so the subdivided circle has the stated radius."""
    k = 3 / (2 + math.cos(2 * math.pi / n)); V = []
    for r, z in rows: V += [(at[0] + r * k * math.cos(2 * math.pi * i / n), at[1] + r * k * math.sin(2 * math.pi * i / n), z) for i in range(n)]
    cb = len(V); V.append((at[0], at[1], bottom)); ct = len(V); V.append((at[0], at[1], top))
    F = []; fm = []; R = len(rows)
    for a in range(R - 1):
        for i in range(n): F.append((a * n + i, a * n + (i + 1) % n, (a + 1) * n + (i + 1) % n, (a + 1) * n + i)); fm.append(row_mats[a] if row_mats else 0)
    for i in range(0, n, 2):
        F.append((cb, (i + 2) % n, (i + 1) % n, i)); fm.append(cap_mat)
        b = (R - 1) * n; F.append((ct, b + i, b + (i + 1) % n, b + (i + 2) % n)); fm.append(cap_mat)
    crease = {}
    for a, c in crease_rows:
        for i in range(n): crease[tuple(sorted((a * n + i, a * n + (i + 1) % n)))] = c
    register(name, V, F, crease, mirror=False)
    return subdivide(name, V, F, crease, levels, material, fmat=fm)

def cut_wells(body, cutters):
    """Real recesses, cut into the subdivided mesh only (the cage stays as light as it is)."""
    for c in cutters:
        m = body.modifiers.new('well', 'BOOLEAN'); m.operation = 'DIFFERENCE'; m.object = c; m.solver = 'EXACT'
        try: m.material_mode = 'TRANSFER'
        except Exception: pass
    dg = bpy.context.evaluated_depsgraph_get(); me = bpy.data.meshes.new_from_object(body.evaluated_get(dg))
    body.modifiers.clear(); old = body.data; body.data = me; me.name = old.name; bpy.data.meshes.remove(old)
    for c in cutters: bpy.data.objects.remove(c)
    bm = bmesh.new(); bm.from_mesh(me); lim = math.radians(40)
    for f in bm.faces: f.smooth = True
    for e in bm.edges:
        if len(e.link_faces) == 2: e.smooth = e.calc_face_angle(0.0) < lim
    bm.to_mesh(me); bm.free()
    return body
def grip_body(name, P, I, ref):
    """The grip's body from the minimal cage. Without a stored cage it is fitted here to the pass-5 surface: every
    vertex of the subdivided surface is pulled to its nearest point on that surface (least squares over the cage)."""
    global NX, NY, RINGS
    path = os.path.join(HERE, '8b-cage.json'); C = None
    T, F, crease, meta = grip_targets(P, I, ref)
    if os.path.exists(path):
        d = json.load(open(path))
        if d.get('shape') == [NX, NY, RINGS] and len(d['grip']) == len(T): C = np.array(d['grip'], float)
    if C is None:
        keep = (NX, NY, RINGS); NX, NY, RINGS = V5
        T5, F5, cr5, _ = grip_targets(P, I, ref)
        ref5 = subdivide('8b_grip_ref5', np.array(json.load(open(os.path.join(HERE, '8b-cage-v5.json')))['grip'], float), F5, cr5, levels=2, orient=False)   # exactly as pass 5 built it
        NX, NY, RINGS = keep
        T, F, crease, meta = grip_targets(P, I, ref5)
        bvh = BVHTree.FromObject(ref5, bpy.context.evaluated_depsgraph_get())
        C, _ = fit_limit(T, F, crease, 4); Sm = subdiv_matrix(C, F, crease)
        for _ in range(8):
            L = Sm @ C; Q = np.array([tuple(bvh.find_nearest(Vector(p))[0]) for p in L])
            C = np.linalg.lstsq(np.vstack([Sm, 0.02 * np.eye(len(C))]), np.vstack([Q, 0.02 * C]), rcond=None)[0]
        L = Sm @ C; d = np.array([bvh.find_nearest(Vector(p))[3] for p in L])
        print('minimal cage %d points fitted to the pass-5 surface: mean %.3f mm, max %.3f mm' % (len(C), 10 * d.mean(), 10 * d.max()))
        bpy.data.objects.remove(ref5)
    STATE.update(T=T, F=F, crease=crease, meta=meta, C=C); register(name, C, F, crease)
    return subdivide(name, C, F, crease, material=['plastic', 'well'])

def ride(objs, old, new):
    """Carry parts that lie on the old surface over to the new one: each vertex moves as its nearest surface point does."""
    dg = bpy.context.evaluated_depsgraph_get(); b0 = BVHTree.FromObject(old, dg); b1 = BVHTree.FromObject(new, dg)
    for o in objs:
        for v in o.data.vertices:
            q0 = b0.find_nearest(v.co)[0]; q1 = b1.find_nearest(q0)[0]
            v.co = v.co + (q1 - q0)

# ------------------------------------------------------------------ bridge
def bridge_cage(X, y0, y1, zfront, back):
    xs = [-X, 0, X]; ys = [y0, y1]          # 12 points: the eight corners and a loop round the middle for the waist
    nx, ny = len(xs), len(ys); vid = lambda i, j, k: (k * ny + j) * nx + i
    V = [(xs[i], ys[j], zfront if k == 0 else back(xs[i], ys[j])) for k in range(2) for j in range(ny) for i in range(nx)]
    F = []
    for i in range(nx - 1):
        for j in range(ny - 1):
            F.append((vid(i, j, 0), vid(i + 1, j, 0), vid(i + 1, j + 1, 0), vid(i, j + 1, 0)))
            F.append((vid(i, j, 1), vid(i, j + 1, 1), vid(i + 1, j + 1, 1), vid(i + 1, j, 1)))
        for j in (0, ny - 1): F.append((vid(i, j, 0), vid(i, j, 1), vid(i + 1, j, 1), vid(i + 1, j, 0)))
    for j in range(ny - 1):
        for i in (0, nx - 1): F.append((vid(i, j, 0), vid(i, j + 1, 0), vid(i, j + 1, 1), vid(i, j, 1)))
    # partial creases round the box edges inward while the faces stay flat on the cage, so the cage itself sits at the
    # outline and at the measured depths and needs no correction
    V = np.array(V, float); crease = auto_crease(V, F, 0.9); C = V.copy()
    STATE.update(bridge=dict(C=C, F=F, crease=crease)); SIDE[0] = 0; register('8b_bridge', C, F, crease)
    return subdivide('8b_bridge', C, F, crease)
