# Mesh helpers for the overlay models (Blender bpy). Units: 1 = 1 cm.
import bpy, bmesh, math
import numpy as np
from mathutils import Vector, Matrix
from mathutils.geometry import delaunay_2d_cdt

def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)

def mat(name):
    return bpy.data.materials.get(name) or bpy.data.materials.new(name)

# ------------------------------------------------------------------ 2D outlines (lists of (x, y), counter-clockwise)
def rounded_rect(w, h, r, seg=8):
    r = max(min(r, w / 2 - 1e-4, h / 2 - 1e-4), 1e-4)
    pts = []
    for cx, cy, a0 in ((w / 2 - r, h / 2 - r, 0), (-w / 2 + r, h / 2 - r, 90), (-w / 2 + r, -h / 2 + r, 180), (w / 2 - r, -h / 2 + r, 270)):
        for i in range(seg + 1):
            a = math.radians(a0 + 90 * i / seg)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts

def rrect(w, h, r, seg=8):
    """Rounded rectangle as an inset-able outline: f(d) gives it shrunk by d."""
    return lambda d=0.0: rounded_rect(w - 2 * d, h - 2 * d, r - d, seg)

def stadium(w, h, seg=8):
    return rrect(w, h, min(w, h) / 2 - 1e-4, seg)

def circle_pts(r, n=32):
    return [(r * math.cos(2 * math.pi * i / n), r * math.sin(2 * math.pi * i / n)) for i in range(n)]

def circ(r, n=32):
    return lambda d=0.0: circle_pts(r - d, n)

def chaikin(pts, iters=2):
    for _ in range(iters):
        out = []
        n = len(pts)
        for i in range(n):
            a, b = pts[i], pts[(i + 1) % n]
            out.append((0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]))
            out.append((0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]))
        pts = out
    return pts

def normals2d(P):
    """Outward normals of a closed CCW polyline (N x 2 array)."""
    t = np.roll(P, -1, 0) - np.roll(P, 1, 0)
    n = np.stack([t[:, 1], -t[:, 0]], 1)
    return n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)

def inset_pts(pts, d):
    P = np.asarray(pts, float)
    if d == 0: return [tuple(p) for p in P]
    return [tuple(p) for p in P - normals2d(P) * d]

def resample(pts, step, closed=True):
    P = np.asarray(pts, float)
    Q = np.vstack([P, P[:1]]) if closed else P
    seg = np.linalg.norm(np.diff(Q, axis=0), axis=1); s = np.concatenate([[0], np.cumsum(seg)])
    n = max(3, int(round(s[-1] / step)))
    t = np.linspace(0, s[-1], n, endpoint=not closed)
    return np.stack([np.interp(t, s, Q[:, 0]), np.interp(t, s, Q[:, 1])], 1)

def smooth_closed(P, iters=2, k=0.25):
    P = np.asarray(P, float)
    for _ in range(iters):
        P = P + k * (np.roll(P, 1, 0) + np.roll(P, -1, 0) - 2 * P)
    return P

def inside(P, Q):
    """Which points Q (M x 2) are inside closed polygon P (N x 2)."""
    P = np.asarray(P, float); Q = np.asarray(Q, float)
    x, y = Q[:, 0][:, None], Q[:, 1][:, None]
    x0, y0 = P[:, 0][None], P[:, 1][None]; x1, y1 = np.roll(P[:, 0], -1)[None], np.roll(P[:, 1], -1)[None]
    c = ((y0 > y) != (y1 > y)) & (x < (x1 - x0) * (y - y0) / np.where(y1 == y0, 1e-12, (y1 - y0)) + x0)
    return c.sum(1) % 2 == 1

def dist_to(P, Q):
    """Distance from points Q to the closed polyline P (dense vertices assumed), and index of the nearest vertex."""
    P = np.asarray(P, float); Q = np.asarray(Q, float)
    out = np.empty(len(Q)); idx = np.empty(len(Q), int)
    for s in range(0, len(Q), 2000):
        d = np.linalg.norm(Q[s:s + 2000, None, :] - P[None], axis=2)
        idx[s:s + 2000] = d.argmin(1); out[s:s + 2000] = d.min(1)
    return out, idx

def triangulate(pts, loops, extra=None):
    """Constrained Delaunay: pts (N x 2); loops = lists of vertex indices forming closed constraint loops.
    Returns (verts M x 2, tris)."""
    edges = []
    for lp in loops:
        edges += [(lp[i], lp[(i + 1) % len(lp)]) for i in range(len(lp))]
    v, e, f, ov, oe, of = delaunay_2d_cdt([Vector(p) for p in pts], edges, [], 0, 1e-6)
    return np.array([tuple(p) for p in v]), [tuple(t) for t in f], ov

def tri_fill(pts):
    """Triangles (as indices into pts) filling a simple polygon, convex or not."""
    P = np.asarray(pts, float)
    V, T, ov = triangulate(P, [list(range(len(P)))])
    back = {i: (o[0] if o else None) for i, o in enumerate(ov)}
    out = []
    for t in T:
        c = V[list(t)].mean(0)
        if inside(P, c[None])[0] and all(back[i] is not None for i in t):
            out.append(tuple(back[i] for i in t))
    return out

# ------------------------------------------------------------------ mesh objects
def finish(ob, sharp=35.0, recalc=True, cap_sharp=()):
    """Smooth shading with hard edges where the faces meet at more than `sharp` degrees or the material changes."""
    me = ob.data
    bm = bmesh.new(); bm.from_mesh(me)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    bmesh.ops.dissolve_degenerate(bm, edges=bm.edges, dist=1e-6)
    if recalc: bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    lim = math.radians(sharp)
    for f in bm.faces: f.smooth = True
    for e in bm.edges:
        lf = e.link_faces
        if len(lf) == 2:
            e.smooth = e.calc_face_angle(0.0) < lim and lf[0].material_index == lf[1].material_index
    bm.to_mesh(me); bm.free()
    return ob

def mesh_obj(name, verts, faces, mats, fmat=None, sharp=35.0, recalc=True, uvs=None):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(map(float, v)) for v in verts], [], [tuple(int(i) for i in f) for f in faces])
    for m in mats: me.materials.append(mat(m))
    if fmat is not None: me.polygons.foreach_set('material_index', [int(i) for i in fmat])
    if uvs is not None:
        uv = me.uv_layers.new(name='UVMap')
        for l in me.loops: uv.data[l.index].uv = uvs[l.vertex_index]
    ob = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(ob)
    if uvs is None: finish(ob, sharp, recalc)
    return ob

def loft(name, loops, band_mats, caps, mats, sharp=35.0, concave=False):
    """Skin a stack of same-sized vertex loops. band_mats: material index per band; caps = (first, last) indices or None."""
    n = len(loops[0]); verts = [p for lp in loops for p in lp]; faces = []; fm = []
    for i in range(len(loops) - 1):
        a, b = i * n, (i + 1) * n
        for k in range(n):
            k2 = (k + 1) % n
            faces.append((a + k, a + k2, b + k2, b + k)); fm.append(band_mats[i])
    for ci, base in ((caps[0], 0), (caps[1], (len(loops) - 1) * n)):
        if ci is None: continue
        if concave:
            for t in tri_fill([(p[0], p[1]) for p in loops[base // n]]):
                faces.append(tuple(base + i for i in t)); fm.append(ci)
        else:
            faces.append(tuple(range(base, base + n))); fm.append(ci)
    return mesh_obj(name, verts, faces, mats, fm, sharp)

def rprism(name, outline, z0, z1, mats, bt=0.0, bb=0.0, seg=3, at=(0, 0), concave=False, sharp=35.0):
    """Outline extruded from z0 to z1 with rounded top (bt) and bottom (bb) edges. mats = (side, top, bottom):
    the rounded edges take the side material, the flat caps the other two."""
    f = outline if callable(outline) else (lambda d: inset_pts(outline, d))
    lv = []
    if bb > 0:
        for k in range(seg): a = math.pi / 2 * k / seg; lv.append((bb * (1 - math.sin(a)), z0 + bb * (1 - math.cos(a))))
    lv.append((0, z0 + bb))
    if bt > 0:
        for k in range(seg + 1): a = math.pi / 2 * k / seg; lv.append((bt * (1 - math.cos(a)), z1 - bt + bt * math.sin(a)))
    else:
        lv.append((0, z1))
    if bb == 0 and bt == 0: lv = [(0, z0), (0, z1)]
    loops = [[(at[0] + x, at[1] + y, z) for x, y in f(d)] for d, z in lv]
    names = list(dict.fromkeys(mats)); mi = [names.index(m) for m in mats]
    return loft(name, loops, [mi[0]] * (len(loops) - 1), (mi[2], mi[1]), names, sharp, concave)

def lathe(name, prof, at=(0, 0), n=32, mats=('plastic',), seg_mats=None, sharp=35.0):
    """Revolve a profile [(r, z), ...] around the Z axis through `at`. r = 0 makes a pole."""
    verts = []; rings = []
    for r, z in prof:
        if r < 1e-6:
            rings.append([len(verts)]); verts.append((at[0], at[1], z))
        else:
            rings.append(list(range(len(verts), len(verts) + n)))
            verts += [(at[0] + r * math.cos(2 * math.pi * k / n), at[1] + r * math.sin(2 * math.pi * k / n), z) for k in range(n)]
    faces = []; fm = []
    for i in range(len(prof) - 1):
        a, b = rings[i], rings[i + 1]; m = seg_mats[i] if seg_mats else 0
        if len(a) == 1 and len(b) == 1: continue
        for k in range(n):
            k2 = (k + 1) % n
            if len(a) == 1: faces.append((a[0], b[k2], b[k]))
            elif len(b) == 1: faces.append((a[k], a[k2], b[0]))
            else: faces.append((a[k], a[k2], b[k2], b[k]))
            fm.append(m)
    return mesh_obj(name, verts, faces, list(mats), fm, sharp)

def arc(r0, z0, r1, z1, seg=4, convex=True):
    """Quarter-ellipse profile points from (r0, z0) to (r1, z1), excluding the start point."""
    out = []
    for k in range(1, seg + 1):
        a = math.pi / 2 * k / seg
        if convex: out.append((r0 + (r1 - r0) * (1 - math.cos(a)), z0 + (z1 - z0) * math.sin(a)))
        else: out.append((r0 + (r1 - r0) * math.sin(a), z0 + (z1 - z0) * (1 - math.cos(a))))
    return out

def xform(ob, M):
    ob.data.transform(M); ob.data.update(); return ob

def rot(axis, deg): return Matrix.Rotation(math.radians(deg), 4, axis)
def tr(x, y, z): return Matrix.Translation((x, y, z))

def text_mesh(name, body, size, material, at, extrude=0.003, bold=0.0):
    bpy.ops.object.text_add(location=at)
    t = bpy.context.active_object; t.name = name
    t.data.body = body; t.data.align_x = 'CENTER'; t.data.align_y = 'CENTER'; t.data.size = size; t.data.extrude = extrude
    t.data.resolution_u = 3; t.data.offset = bold
    t.data.materials.append(mat(material))
    bpy.ops.object.convert(target='MESH')
    ob = bpy.context.active_object
    ob.data.transform(ob.matrix_world); ob.matrix_world = Matrix.Identity(4)
    bm = bmesh.new(); bm.from_mesh(ob.data)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    # keep only the front faces: the letter is a flat decal
    bmesh.ops.delete(bm, geom=[f for f in bm.faces if f.normal.z < 0.5], context='FACES')
    for f in bm.faces: f.smooth = False
    bm.to_mesh(ob.data); bm.free()
    return ob

# ------------------------------------------------------------------ organic shell from an outline and height fields
def inradius(P):
    """Per vertex of a closed CCW outline: radius of the largest circle touching it there from inside."""
    P = np.asarray(P, float); nrm = normals2d(P)
    D = P[None, :, :] - P[:, None, :]
    dn = (D * nrm[:, None, :]).sum(2); d2 = (D ** 2).sum(2)
    with np.errstate(divide='ignore', invalid='ignore'):
        r = np.where(dn < -1e-9, d2 / (-2 * dn), np.inf)
    return r.min(1)

def shell(name, outline, zf, zb, w, zs, Rf=0.9, Rb=2.0, holes=(), grid=0.22, band=(12, 24, 36, 48),
          mats=('plastic', 'well'), smooth=3):
    """Closed body between a front and a back height field over `outline` (N x 2, CCW, evenly sampled).
    zf(xy), zb(xy): the heights away from the edges. At outline vertex i both surfaces roll over in a quarter ellipse
    (Rf[i] / Rb[i] wide) and meet at height zs[i]; w[i] in 0..1 scales that down to a flat wall (0).
    The steep part of the roll-over is a structured band along the outline; the rest is a triangulated height field.
    holes: dicts with 'outline' and 'depth', cut into the front and given a wall and a floor in the second material.
    Returns the object and the front and back height functions."""
    P = np.asarray(outline, float); n = len(P); nrm = normals2d(P)
    bc = lambda v: np.broadcast_to(np.asarray(v, float), (n,)).copy()
    w, zs, Rf, Rb = bc(w), bc(zs), bc(Rf), bc(Rb)
    rin = inradius(P)
    for R in (Rf, Rb):
        R[:] = np.minimum(R, 0.92 * rin)
        for k in (1, 2, 3): R[:] = np.minimum(R, np.minimum(np.roll(R, k), np.roll(R, -k)))
        R[:] = smooth_closed(R[:, None], 4, 0.25)[:, 0]
    Dn = resample(P, 0.03); t = np.linspace(0, n, len(Dn), endpoint=False)   # dense copy for distances
    per = lambda v: np.interp(t, np.arange(n + 1), np.append(v, v[0]))
    wD, zsD = per(w), per(zs)
    def field(plateau, R):
        RD = per(R)
        def fn(Q):
            Q = np.asarray(Q, float); d, i = dist_to(Dn, Q)
            tt = np.clip(d / np.maximum(wD[i] * RD[i], 1e-6), 0, 1)
            pl = plateau(Q)
            return pl - wD[i] * (pl - zsD[i]) * (1 - np.sqrt(1 - (1 - tt) ** 2))
        return fn
    front, back = field(zf, Rf), field(zb, Rb)
    verts = []; faces = []; fm = []
    hole_outs = [np.asarray(h['outline'], float) for h in holes]
    edge_base = {}
    for side, plateau, R, hl in ((1, zf, Rf, hole_outs), (-1, zb, Rb, [])):
        fn = front if side > 0 else back
        # ---- band: rings of the outline moved inward along its normals
        ring_base = []
        for th in (0,) + tuple(band):
            a = math.radians(th)
            Qr = P - nrm * (w * R * (1 - math.cos(a)))[:, None]
            pl = plateau(Qr)
            z = pl - w * (pl - zs) * (1 - math.sin(a))
            ring_base.append(len(verts)); verts += [(x, y, zz) for (x, y), zz in zip(Qr, z)]
        edge_base[side] = ring_base[0]
        for j in range(len(ring_base) - 1):
            a0, b0 = ring_base[j], ring_base[j + 1]
            for k in range(n):
                k2 = (k + 1) % n
                q = (a0 + k, a0 + k2, b0 + k2, b0 + k)
                faces.append(q if side > 0 else q[::-1]); fm.append(0)
        # ---- inside the last ring: constrained Delaunay over a grid, with the holes
        I = Qr
        xs = np.arange(I[:, 0].min(), I[:, 0].max(), grid); ys = np.arange(I[:, 1].min(), I[:, 1].max(), grid * 0.866)
        G = np.array([(x + (grid / 2 if j % 2 else 0), y) for j, y in enumerate(ys) for x in xs])
        dd, _ = dist_to(resample(I, 0.04), G)
        G = G[inside(I, G) & (dd > 0.55 * grid)]
        pts = [I]; loops = [list(range(n))]; count = n
        for hp in hl:
            pts.append(hp); loops.append(list(range(count, count + len(hp)))); count += len(hp)
            dd, _ = dist_to(resample(hp, 0.03), G)
            G = G[~inside(hp, G) & (dd > 0.4 * grid)]
        V, T, ov = triangulate(np.vstack(pts + [G]), loops)
        assert np.allclose(V[:n], I, atol=1e-5), 'CDT reordered the outline'
        z = fn(V)
        z[:n] = [verts[ring_base[-1] + k][2] for k in range(n)]
        Ta = np.array(T); C = V[Ta].mean(1)
        ok = inside(I, C)
        for hp in hl: ok &= ~inside(hp, C)
        Ta = Ta[ok]
        E = np.vstack([Ta[:, [0, 1]], Ta[:, [1, 2]], Ta[:, [2, 0]]]); E = np.vstack([E, E[:, ::-1]])
        deg = np.bincount(E[:, 0], minlength=len(V)).astype(float)
        for _ in range(smooth):   # soften the creases where the roll-overs of two edges meet
            avg = np.bincount(E[:, 0], weights=z[E[:, 1]], minlength=len(V)) / np.maximum(deg, 1)
            z[count:] = 0.5 * z[count:] + 0.5 * avg[count:]
        base = len(verts); verts += [(x, y, zz) for (x, y), zz in zip(V, z)]
        for t3 in Ta:
            faces.append(tuple(base + int(i) for i in (t3 if side > 0 else t3[::-1]))); fm.append(0)
        if side > 0:
            hstart = n
            for h, hp in zip(holes, hole_outs):
                m = len(hp); zt = z[hstart:hstart + m]; b0 = len(verts)
                zfl = float(zt.min()) - h['depth']
                verts += [(x, y, zfl) for x, y in hp]
                for k in range(m):
                    k2 = (k + 1) % m
                    faces.append((base + hstart + k, base + hstart + k2, b0 + k2, b0 + k)); fm.append(1)
                for t3 in tri_fill(hp): faces.append(tuple(b0 + i for i in t3)); fm.append(1)
                hstart += m
    # ---- the wall between the two outlines where the edge is not fully round
    fb, bb = edge_base[1], edge_base[-1]
    for k in range(n):
        k2 = (k + 1) % n
        if w[k] < 0.999 or w[k2] < 0.999:
            faces.append((fb + k, bb + k, bb + k2, fb + k2)); fm.append(0)
    ob = mesh_obj(name, verts, faces, list(mats), fm, sharp=50.0)
    front.info = dict(P=P, nrm=nrm, w=w, Rf=Rf, Rb=Rb, zs=zs, zf=zf, zb=zb)   # for parts that ride on the rolled edge
    return ob, front, back

def tri_count(objs):
    return sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in objs if o.type == 'MESH')

def export(objs, path):
    for o in bpy.context.scene.objects: o.select_set(False)
    for o in objs: o.select_set(True)
    bpy.ops.export_scene.gltf(filepath=path, use_selection=True, export_format='GLB', export_apply=True,
                              export_yup=False, export_normals=True, export_materials='EXPORT')
