# The pointer's guns, made from Quaternius "Ultimate Gun Pack" meshes (CC0, see ../SOURCES.md) and put in the frame of
# Guns.cs: x right, y up, z toward the stock, the muzzle tip at (0, bore height, MuzzleZ). Each loose piece of a source
# mesh becomes (part of) a named part with a material slot; edges are bevelled and the normals weighted on export.
# Added here: the scopes (to Guns.cs's Scope numbers), bolts, the pistol's magazine body, the bore.
import os, math
import numpy as np
from lib import *
from objsrc import read_obj, pieces

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'source')
BEVEL = 0.0045

# Copied from companion/src/GyroPhone.Host/Guns.cs: boxes (centre x, y, z, half sizes), MuzzleZ, Scope(Y, Front, Back, Radius, Bell).
BOX = {
    'pistol': dict(muzzle=-0.46, bore_y=0.035, scope=None, boxes=[
        0, 0.035, -0.2, 0.035, 0.04, 0.25, 0, -0.02, -0.17, 0.03, 0.025, 0.2, 0, -0.16, 0.0, 0.032, 0.13, 0.055,
        0, -0.07, -0.1, 0.008, 0.03, 0.045, 0, 0.085, 0.02, 0.025, 0.012, 0.01, 0, 0.085, -0.42, 0.006, 0.012, 0.01]),
    'smg': dict(muzzle=-0.56, bore_y=0.0, scope=(0.13, -0.12, 0.16, 0.042, 0.058), boxes=[
        0, 0, 0, 0.05, 0.07, 0.22, 0, 0, -0.32, 0.032, 0.032, 0.12, 0, 0, -0.5, 0.014, 0.014, 0.06, 0, -0.02, 0.42, 0.03, 0.06, 0.2,
        0, -0.2, -0.06, 0.025, 0.12, 0.03, 0, -0.16, 0.15, 0.028, 0.09, 0.04, 0, 0.08, -0.36, 0.01, 0.04, 0.012]),
    'ar': dict(muzzle=-0.93, bore_y=0.0, scope=(0.17, -0.12, 0.3, 0.051, 0.072), boxes=[
        0, -0.05, 0.68, 0.05, 0.09, 0.2, 0, 0, 0.42, 0.03, 0.03, 0.08, 0, 0, 0.12, 0.055, 0.075, 0.22, 0, 0.1, 0.1, 0.028, 0.025, 0.18,
        0, 0, -0.32, 0.055, 0.055, 0.2, 0, 0, -0.7, 0.018, 0.018, 0.18, 0, 0, -0.9, 0.026, 0.026, 0.03, 0, 0.07, -0.5, 0.012, 0.06, 0.015,
        0, -0.2, 0, 0.035, 0.12, 0.06, 0, -0.17, 0.28, 0.03, 0.09, 0.045]),
    'battle': dict(muzzle=-0.975, bore_y=0.0, scope=(0.17, -0.16, 0.3, 0.054, 0.08), boxes=[
        0, -0.04, 0.7, 0.055, 0.1, 0.2, 0, 0, 0.12, 0.06, 0.08, 0.26, 0, 0, -0.36, 0.06, 0.06, 0.24, 0, 0, -0.76, 0.02, 0.02, 0.18,
        0, 0, -0.94, 0.032, 0.032, 0.035, 0, 0.08, -0.56, 0.012, 0.06, 0.015, 0, -0.2, -0.02, 0.04, 0.12, 0.07,
        0, -0.17, 0.32, 0.03, 0.09, 0.045, 0, 0.1, 0.06, 0.03, 0.022, 0.24]),
    'lmg': dict(muzzle=-0.94, bore_y=0.0, scope=(0.17, -0.2, 0.18, 0.051, 0.072), boxes=[
        0, -0.03, 0.64, 0.05, 0.09, 0.2, 0, 0, 0.1, 0.07, 0.09, 0.25, 0, 0.01, -0.36, 0.045, 0.045, 0.22, 0, 0, -0.76, 0.022, 0.022, 0.18,
        0, -0.18, 0.02, 0.07, 0.09, 0.08, 0, -0.17, 0.32, 0.03, 0.09, 0.045, 0, 0.14, -0.02, 0.015, 0.04, 0.09, 0, -0.06, -0.66, 0.012, 0.012, 0.16]),
    'shotgun': dict(muzzle=-0.85, bore_y=0.02, scope=(0.12, -0.1, 0.22, 0.048, 0.067), boxes=[
        0, -0.06, 0.56, 0.045, 0.1, 0.25, 0, 0, 0.1, 0.05, 0.08, 0.18, 0, 0.02, -0.45, 0.03, 0.03, 0.4, 0, -0.05, -0.35, 0.025, 0.025, 0.3,
        0, -0.05, -0.38, 0.042, 0.042, 0.12, 0, 0.06, -0.82, 0.008, 0.01, 0.008]),
    'sniper': dict(muzzle=-0.975, bore_y=0.0, scope=(0.16, -0.26, 0.32, 0.064, 0.104), boxes=[
        0, -0.04, 0.6, 0.05, 0.11, 0.24, 0, 0.06, 0.56, 0.03, 0.03, 0.12, 0, 0, 0.1, 0.05, 0.06, 0.22, 0, 0, -0.58, 0.02, 0.02, 0.34,
        0, 0, -0.94, 0.032, 0.032, 0.035, 0.07, 0.02, 0.2, 0.03, 0.012, 0.012, 0, -0.12, 0.05, 0.03, 0.06, 0.05, 0, -0.16, 0.32, 0.03, 0.09, 0.045]),
}

def box_extent(key):
    b = np.array(BOX[key]['boxes'], float).reshape(-1, 6)
    lo = (b[:, :3] - b[:, 3:]).min(0); hi = (b[:, :3] + b[:, 3:]).max(0)
    lo[2] = min(lo[2], BOX[key]['muzzle'])
    return lo, hi

G, P, W, R, C = 'gunmetal', 'polymer', 'wood', 'rubber', 'chrome'
# Per gun: the source mesh; pieces: piece number -> (part, slot or {source material: slot}); drop: pieces left out;
# sy: squash in height about the bore (so the scope of Guns.cs clears it); extra: boxes in the source's own coordinates (x0, x1, y0, y1, z0, z1) -> (part, slot).
SPEC = {
    'pistol': dict(src='Pistol_5', pieces={
        4: ('slide', G), **{i: ('slide', G) for i in range(5, 13)}, 1: ('slide', G), 19: ('slide', C), 20: ('slide', C),
        3: ('frame', {'Metal': P, 'Black': ('mag', P)}), 0: ('frame', P), 2: ('hammer', G), 13: ('trigger', G), 18: ('barrel', G),
        **{i: ('grip', R) for i in range(14, 18)}}, extra=[]),
    'smg': dict(src='SubmachineGun_2', sy=0.85, pieces={
        6: ('upper', G), 2: ('lower', {'Metal': G, 'DarkMetal': P}), 9: ('stock', G), 10: ('stock', G), 3: ('upper', G), 4: ('lower', G),
        5: ('mag', {'DarkMetal': G, 'Black': P}), 7: ('barrel', G), 0: ('sights', G), 1: ('sights', G), 8: ('lower', G), 11: ('trigger', G),
        **{i: ('rail', G) for i in range(12, 24)}},
        extra=[((0.72, 1.16, 0.60, 0.74, 0.150, 0.170), 'slide', C), ((1.02, 1.10, 0.64, 0.70, 0.165, 0.25), 'slide', G)]),
    'ar': dict(src='AssaultRifle2_1', drop=[9], pieces={
        0: ('grip', P), 1: ('buffer', G), 2: ('upper', G), 3: ('mag', P), 4: ('stock', {'Main': P, 'MainDark': R}), 5: ('trigger', G),
        6: ('barrel', {'Main': G, 'MainDark': ('handguard', P)}), 7: ('frontsight', G), 8: ('lower', G),
        **{i: ('rail', G) for i in range(10, 18)}},
        extra=[((0.73, 1.01, 0.575, 0.645, 0.085, 0.108), 'slide', C), ((-0.22, -0.11, 0.70, 0.755, -0.135, 0.135), 'slide', G),
               ((-0.16, 0.10, 0.705, 0.75, -0.04, 0.04), 'slide', G)]),
    'battle': dict(src='AssaultRifle_5', pieces={
        0: ('stock', P), 1: ('grip', {'Black': P, 'DarkMetal': G}), 2: ('mag', G), 3: ('handguard', P), 5: ('handguard', P), 4: ('receiver', G),
        12: ('receiver', G), 6: ('sights', G), 7: ('receiver', G), 8: ('trigger', G), 9: ('gastube', G), 10: ('barrel', G), 11: ('sights', G),
        13: ('barrel', G)},
        extra=[((0.78, 1.36, 0.615, 0.675, 0.085, 0.118), 'slide', C), ((0.84, 0.93, 0.62, 0.67, 0.11, 0.24), 'slide', G)]),
    'shotgun': dict(src='Shotgun_3', pieces={
        0: ('stock', {'Wood': W, 'Metal': R}), 1: ('pump', W), 2: ('receiver', G), 3: ('receiver', G), 4: ('receiver', G), 5: ('barrel', G),
        6: ('tube', G), 7: ('receiver', G), 8: ('barrel', G), 9: ('bead', C), 10: ('trigger', G)},
        extra=[((0.79, 1.09, 0.12, 0.19, 0.085, 0.106), 'slide', C)]),
    # The LMG: the pack has no machine gun, so this is its long boxy carbine with the stick magazine left out and, made
    # here, a box magazine, a long heavy barrel (barrel_ext, in source units), a folded bipod and a bolt.
    'lmg': dict(src='SubmachineGun_5', drop=[3], barrel_ext=1.3, pieces={
        0: ('sights', G), 1: ('sights', G), 2: ('lower', {'Metal': G, 'DarkMetal': P}), 4: ('upper', G), 5: ('lower', G), 6: ('trunnion', G),
        7: ('upper', G), 8: ('stock', G), 9: ('stock', G), 10: ('lower', G), 40: ('trigger', G), **{i: ('rail', G) for i in range(11, 40)}},
        extra=[((0.52, 1.28, -0.46, 0.13, -0.21, 0.21), 'mag', P), ((0.50, 1.30, 0.10, 0.17, -0.22, 0.22), 'mag', G), ((0.72, 1.00, 0.15, 0.30, -0.07, 0.07), 'mag', G),
               ((1.15, 1.75, 0.60, 0.74, 0.150, 0.170), 'slide', C), ((1.52, 1.62, 0.63, 0.70, 0.165, 0.27), 'slide', G),
               ((3.22, 3.38, 0.40, 0.60, -0.10, 0.10), 'bipod', G), ((3.30, 4.30, 0.39, 0.445, 0.045, 0.10), 'bipod', G), ((3.30, 4.30, 0.39, 0.445, -0.10, -0.045), 'bipod', G),
               ((4.24, 4.34, 0.375, 0.46, 0.035, 0.11), 'bipod', R), ((4.24, 4.34, 0.375, 0.46, -0.11, -0.035), 'bipod', R)]),
    # The sniper: the pack's scope (pieces 3 to 5) is left out for the scope of Guns.cs.
    'sniper': dict(src='SniperRifle_2', drop=[3, 4, 5], pieces={
        0: ('mag', G), 1: ('stock', {'LightMetal': P, 'Black': R}), 2: ('receiver', G), 6: ('rail', G), 7: ('barrel', G), 8: ('slide', C), 9: ('trigger', G)},
        extra=[]),
}

def box_faces(b, off=0):
    x0, x1, y0, y1, z0, z1 = b
    v = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    return v, [tuple(i + off for i in q) for q in f]

def soften(ob, width=BEVEL, segments=2):
    """Rounded edges and face-weighted normals, applied at export."""
    for p in ob.data.polygons: p.use_smooth = True
    m = ob.modifiers.new('bevel', 'BEVEL'); m.limit_method = 'ANGLE'; m.angle_limit = math.radians(28); m.width = width
    m.segments = segments; m.use_clamp_overlap = True; m.harden_normals = False
    w = ob.modifiers.new('wn', 'WEIGHTED_NORMAL'); w.keep_sharp = False; w.weight = 100; w.mode = 'FACE_AREA'
    return ob

def join(name, objs):
    """One object from several, their materials kept."""
    bm = bmesh.new(); names = []
    for o in objs:
        dg = bpy.context.evaluated_depsgraph_get(); me = o.data
        idx = []
        for m in me.materials:
            if m.name not in names: names.append(m.name)
            idx.append(names.index(m.name))
        tmp = bmesh.new(); tmp.from_mesh(me)
        vm = [bm.verts.new(v.co) for v in tmp.verts]
        for f in tmp.faces:
            nf = bm.faces.new([vm[v.index] for v in f.verts]); nf.material_index = idx[f.material_index]; nf.smooth = f.smooth
            for a, b in zip(f.edges, nf.edges): b.smooth = a.smooth
        tmp.free(); bpy.data.objects.remove(o)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    for n in names: me.materials.append(mat(n))
    ob = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(ob)
    return ob

def disc(name, y, z, r, facing, material, n=32, dome=0.0):
    """A lens: a (slightly domed) disc on the z axis at height y, facing -z (facing = -1) or +z."""
    verts = [(0, y, z + facing * dome)] + [(r * math.cos(2 * math.pi * k / n), y + r * math.sin(2 * math.pi * k / n), z) for k in range(n)]
    faces = [(0, 1 + (k + 1) % n, 1 + k) if facing < 0 else (0, 1 + k, 1 + (k + 1) % n) for k in range(n)]
    return mesh_obj(name, verts, faces, [material], sharp=60, recalc=False)

def scope(key, sc, top):
    """The scope of Guns.cs: axis at height Y from Front to Back, eyepiece Radius, objective Bell. top(z0, z1): the gun's top there."""
    Y, F, B, Rr, Bell = sc; L = B - F; Rt = 0.68 * Rr; d = 0.012; t = 0.006
    prof = [(0, F + d), (Bell - t, F + d), (Bell - t, F), (Bell, F), (Bell, F + 0.02), (Bell, F + 0.17 * L)]
    prof += [(Bell + (Rt - Bell) * (0.5 - 0.5 * math.cos(math.pi * k / 6)), F + (0.17 + 0.13 * k / 6) * L) for k in range(1, 7)]
    prof += [(Rt, B - 0.36 * L)] + [(Rt + (Rr - Rt) * (0.5 - 0.5 * math.cos(math.pi * k / 5)), B - (0.36 - 0.08 * k / 5) * L) for k in range(1, 6)]
    prof += [(Rr, B - 0.20 * L), (Rr * 1.04, B - 0.195 * L), (Rr * 1.04, B - 0.14 * L), (Rr, B - 0.135 * L), (Rr, B - 0.02), (Rr, B), (Rr - t, B), (Rr - t, B - d), (0, B - d)]
    sm = [0] * (len(prof) - 1)
    for i, (a, b) in enumerate(zip(prof, prof[1:])):
        if (a[1] <= F + 0.021 and b[1] <= F + 0.021 and min(a[0], b[0]) >= Bell - t - 1e-6) or (a[1] >= B - 0.021 and b[1] >= B - 0.021 and min(a[0], b[0]) >= Rr - t - 1e-6) \
           or (abs(a[0] - Rr * 1.04) < 1e-6 and abs(b[0] - Rr * 1.04) < 1e-6): sm[i] = 1
    parts = [lathe('tube', prof, at=(0, Y), n=36, mats=(G, 'lensring'), seg_mats=sm, sharp=40)]
    zm = F + 0.52 * L; tr_ = 0.42 * Rr
    saddle = [(0, -Rt * 1.25), (Rt * 1.22, -Rt * 1.25), (Rt * 1.22, Rt * 1.25), (0, Rt * 1.25)]
    for axis, deg in (('X', -90), ('Y', 90)):   # elevation turret (up), windage turret (right)
        knob = [(0, 0), (tr_ * 1.1, 0), (tr_ * 1.1, Rt * 1.15), (tr_, Rt * 1.2), (tr_, Rt * 1.75), (tr_ * 0.85, Rt * 1.85), (0, Rt * 1.85)]
        k = lathe('turret', knob, n=20, mats=(G,), sharp=40)
        parts.append(xform(k, tr(0, Y, zm) @ rot(axis, deg)))
    for zr in (F + 0.385 * L, B - 0.43 * L):   # the rings and their feet, down to the gun
        rw = 0.016; ro = Rt + 0.009
        ring = [(Rt - 0.001, zr - rw), (ro, zr - rw), (ro, zr + rw), (Rt - 0.001, zr + rw), (Rt - 0.001, zr - rw)]
        parts.append(lathe('ring', ring, at=(0, Y), n=28, mats=('lensring',), sharp=40))
        yt = top(zr - rw, zr + rw)
        v, f = box_faces((-0.016, 0.016, yt - 0.004, Y - Rt * 0.6, zr - rw, zr + rw))
        parts.append(mesh_obj('foot', v, f, ['lensring'], sharp=40))
        v, f = box_faces((-0.026, 0.026, yt - 0.004, yt + 0.012, zr - rw * 1.5, zr + rw * 1.5))
        parts.append(mesh_obj('clamp', v, f, ['lensring'], sharp=40))
    body = join(f'{key}_scope', parts)
    lens = join(f'{key}_scope_lens', [disc('l0', Y, F + d - 0.0015, Bell - t, -1, 'lensglass', dome=0.004), disc('l1', Y, B - d + 0.0015, Rr - t, 1, 'lensglass', dome=0.003)])
    return [body, lens]

def gun(key):
    spec = SPEC[key]; bx = BOX[key]
    V, F, M, Cc = pieces(*read_obj(os.path.join(SRC, spec['src'] + '.obj')))
    lo, hi = box_extent(key)
    # the muzzle: the source's far +x end; the bore axis is the middle of the points there
    xmax, xmin = V[:, 0].max(), V[:, 0].min()
    tip = V[V[:, 0] > xmax - 1e-3]; by, bz = (tip[:, 1].min() + tip[:, 1].max()) / 2, (tip[:, 2].min() + tip[:, 2].max()) / 2
    brad = (tip[:, 1].max() - tip[:, 1].min()) / 2
    tip_x = xmax; xmax += spec.get('barrel_ext', 0.0)   # a barrel made here, ahead of the source's
    s = (hi[2] - bx['muzzle']) / (xmax - xmin)
    sy = s * spec.get('sy', 1.0)
    T = lambda p: ((p[2] - bz) * s, bx['bore_y'] + (p[1] - by) * sy, bx['muzzle'] + (xmax - p[0]) * s)
    Vt = np.array([T(p) for p in V])
    # faces by part and slot
    groups = {}; drop = set(spec.get('drop', ()))
    if bx['scope']:   # rail teeth and sights the scope would sit in go
        Y, Fz, Bz, Rr, Bell = bx['scope']; L = Bz - Fz
        for c, (part, _) in spec['pieces'].items():
            if part not in ('rail', 'sights'): continue
            B = Vt[sorted({j for i, f in enumerate(F) if Cc[i] == c for j in f})]
            z0, z1 = B[:, 2].min(), B[:, 2].max()
            if z1 < Fz or z0 > Bz: continue
            r = max(Bell if z0 < Fz + 0.32 * L else 0, Rr if z1 > Bz - 0.38 * L else 0, 0.68 * Rr)
            if B[:, 1].max() > Y - r - 0.003: drop.add(c)
    for i, f in enumerate(F):
        c = Cc[i]
        if c in drop: continue
        if c not in spec['pieces']: raise SystemExit(f'{key}: piece {c} has no part')
        part, slot = spec['pieces'][c]
        if isinstance(slot, dict): slot = slot[M[i]]
        if isinstance(slot, tuple): part, slot = slot
        groups.setdefault(part, []).append((f, slot))
    objs = []
    extra = {}
    for b, part, slot in spec['extra']: extra.setdefault(part, []).append((b, slot))
    if key == 'pistol':   # the magazine's body, up inside the grip from its floorplate
        idx = sorted({j for f, _ in groups['mag'] for j in f}); Bv = V[idx]
        c = (Bv.min(0) + Bv.max(0)) / 2; a = np.array([0.243, 0.970, 0]); u = np.array([0.970, -0.243, 0]); w = np.array([0, 0, 1.0])
        corners = [c + u * su * 0.15 + w * sw * 0.075 + a * h for h in (0.02, 0.62) for su, sw in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
        extra.setdefault('mag', []).append((corners, G))
    for part in sorted(set(groups) | set(extra)):
        verts = []; faces = []; fm = []; slots = []; vmap = {}
        def slot_i(sl):
            if sl not in slots: slots.append(sl)
            return slots.index(sl)
        for f, sl in groups.get(part, []):
            q = []
            for j in f:
                if j not in vmap: vmap[j] = len(verts); verts.append(tuple(Vt[j]))
                q.append(vmap[j])
            faces.append(q); fm.append(slot_i(sl))
        for b, sl in extra.get(part, []):
            if isinstance(b, tuple): v, fq = box_faces(b, len(verts))
            else: v = b; fq = box_faces((0,) * 6, len(verts))[1]
            verts += [T(p) for p in v]; faces += fq; fm += [slot_i(sl)] * len(fq)
        ob = mesh_obj(f'{key}_{part}', verts, faces, slots, fm, sharp=180, recalc=True)
        objs.append(soften(ob, segments=1 if part in ('rail', 'sights', 'frontsight', 'trigger', 'hammer', 'bead') else 2))
    if spec.get('barrel_ext'):
        mz = bx['muzzle']; z1 = mz + (xmax - tip_x) * s + 0.01; rb = max(brad * s * 1.15, 0.019); rf = rb * 1.32
        prof = [(0, mz), (rf, mz), (rf, mz + 0.062), (rb, mz + 0.068), (rb, z1 - 0.10), (rb * 1.5, z1 - 0.095), (rb * 1.5, z1 - 0.045), (rb, z1 - 0.04), (rb, z1), (0, z1)]
        objs.append(lathe(f'{key}_barrel', prof, at=(0, bx['bore_y']), n=28, mats=(G,), sharp=40))
        brad = rb / s
    # the bore, a dark disc just ahead of the muzzle face
    objs.append(disc(f'{key}_bore', bx['bore_y'], bx['muzzle'] - 0.0006, brad * s * 0.55, -1, 'rubber', n=20))
    if bx['scope']:
        def top(z0, z1):
            ys = [Vt[f, 1].max() for i, f in enumerate(F) if Cc[i] not in drop and Vt[f, 2].min() < z1 and Vt[f, 2].max() > z0]
            return float(max(ys)) if ys else bx['bore_y'] + 0.03
        objs += scope(key, bx['scope'], top)
    info = dict(scale=s, src=spec['src'], bore_radius=brad * s, dropped=sorted(drop))
    return key, objs, info
