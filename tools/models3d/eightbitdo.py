# 8BitDo Ultimate Mobile Gaming Controller: 197.2 x 102.7 x 53.5 mm retracted.
# The front silhouette is traced from the maker's orthographic picture (8b-outline.json, in cm, origin at the middle
# of the phone pad). Each grip is one shell between a front and a back height field; the right grip mirrors the left.
import json, os
from lib import *
import cage

HERE = os.path.dirname(os.path.abspath(__file__))
XI = 5.15            # inner edge of each grip when retracted
ZF = 0.795           # front face: 12 mm in front of the pad (top view), so the phone's screen lies 3.9 mm below it
ZPAD = -0.405        # pad surface = the phone's back
PHONE_L = 16.14
DY = 0.05             # the controller sits 0.5 mm up, so the phone (centred on the origin) clears the fences top and bottom

def smoothstep(t):
    t = np.clip(t, 0, 1); return t * t * (3 - 2 * t)

def body_top(ax):
    """Top line of the grip body under the bumper (ax = |x|)."""
    s = np.clip((ax - 5.95) / 2.2, 0, 1)
    return 3.955 - 0.605 * s * s

def grip_outline():
    Q = np.array(json.load(open(os.path.join(HERE, '8b-outline.json'))))
    L = Q[Q[:, 0] <= -XI]
    # start right after the inner edge at the top, run counter-clockwise to the bottom of the inner edge
    i0 = int(np.argmax((np.roll(Q[:, 0], 1) > -XI) & (Q[:, 0] <= -XI) & (Q[:, 1] > 0)))
    R = np.roll(Q, -i0, 0); R = R[:int(np.argmax(R[:, 0] > -XI))]
    bump = R[(R[:, 0] > -8.2) & (R[:, 0] < -5.9) & (R[:, 1] > 3.0)].copy()   # the bumper and trigger, seen from the front
    top = body_top(-R[:, 0])
    m = (R[:, 0] > -8.2) & (R[:, 0] < -5.9) & (R[:, 1] > top)
    R[m, 1] = top[m]
    R = np.vstack([[-XI, 3.955], R, [-XI, -4.23]])
    P = resample(R, 0.12)
    P = smooth_closed(P, 3, 0.25)
    # put the inner edge back exactly on its line
    inner = P[:, 0] > -XI - 0.04
    P[inner, 0] = -XI
    return P, inner, bump

_yy = np.linspace(-6.5, 4.5, 111)
DEPTH_Y = _yy
DEPTH_D = np.interp(_yy, [-6.5, -4.6, -3.0, -1.5, 0.0, 1.3, 4.5], [4.2, 4.3, 4.05, 3.55, 3.3, 3.2, 3.2])
for _ in range(25): DEPTH_D = np.convolve(np.pad(DEPTH_D, 1, mode='edge'), [0.25, 0.5, 0.25], mode='valid')

def depth(Q):
    """Back surface before rounding: about as deep as the bridge at the inner edge, a full handle further out."""
    u = np.clip(-Q[:, 0] - XI, 0, None)
    # depth behind the face along the grip's height, read off the owner's side photo: 4.55 cm at the lobe, a waist of
    # 3.1 cm under the trigger
    dm = np.interp(Q[:, 1], DEPTH_Y, DEPTH_D)
    lw = smoothstep((-2.0 - Q[:, 1]) / 2.5)
    d0 = BRIDGE_B - (BRIDGE_B - BRIDGE_T) * smoothstep((Q[:, 1] + 4.1) / 8.0)        # at the inner edge: as deep as the bridge next to it
    return ZF - (d0 + (dm - d0) * smoothstep((u - LOBE_IN * lw) / (1.1 + 0.9 * lw)))

LOBE_IN = 1.0        # the lobe's back dome starts this far from the inner edge (owner's underside photo)
LOBE_DROP = 1.6      # the handle's face falls back toward the outer side of the lobe (same photo)

def face(Q):
    Q = np.asarray(Q, float)
    lw = smoothstep((-2.0 - Q[:, 1]) / 2.5); x0 = 7.9 - 1.3 * lw
    t = np.clip((-Q[:, 0] - x0) / (9.86 - x0), 0, 1)
    return ZF - LOBE_DROP * (0.6 + 0.4 * lw) * t ** 1.35

def cross(arm, length, r=0.06):
    a, b = arm / 2, length / 2
    pts = [(a, b), (-a, b), (-a, a), (-b, a), (-b, -a), (-a, -a), (-a, -b), (a, -b), (a, -a), (b, -a), (b, a), (a, a)]
    out = []
    n = len(pts)
    for i in range(n):  # cut each corner twice for a small radius
        p0, p1, p2 = np.array(pts[i - 1]), np.array(pts[i]), np.array(pts[(i + 1) % n])
        d0 = (p0 - p1) / np.linalg.norm(p0 - p1); d2 = (p2 - p1) / np.linalg.norm(p2 - p1)
        out += [tuple(p1 + d0 * r), tuple(p1 + (d0 + d2) * r * 0.3), tuple(p1 + d2 * r)]
    return out

def star(r0, r1, n=5):
    return [((r0 if i % 2 == 0 else r1) * math.cos(math.pi / 2 + math.pi * i / n), (r0 if i % 2 == 0 else r1) * math.sin(math.pi / 2 + math.pi * i / n)) for i in range(2 * n)]

def decal(name, outline, at, z, material='well', concave=False):
    return rprism(name, outline, z - 0.004, z + 0.003, (material, material, material), at=at, concave=concave)

STICK_H = 0.3            # the caps stand 3 mm higher than first modelled (owner's three photos)

def stick(name, at, z, h=None):
    """Analog stick: collar ring on the face, dark bowl and base, a chrome stem and a thin knurled cap with a dished top."""
    h = STICK_H if h is None else h
    objs = [lathe(name + '_collar', [(0.93, z - 0.05), (0.95, z + 0.035), (1.0, z + 0.05), (1.1, z + 0.045), (1.18, z + 0.0), (1.18, z - 0.05)],
                  at=at, n=40, mats=('plastic',))]
    objs.append(lathe(name + '_ball', [(0, z + 0.02)] + arc(0, z + 0.02, 0.74, z - 0.4, 4, convex=False), at=at, n=28, mats=('stem',)))
    objs.append(lathe(name + '_stem', [(0, z - 0.1), (0.46, z - 0.1), (0.46, z + 0.43 + h), (0.44, z + 0.46 + h), (0, z + 0.46 + h)], at=at, n=28, mats=('stickmetal',)))
    # the cap is a subdivision part of 26 points: three rings of eight and a centre point at each end; dished top
    zc = z + 0.45 + h
    objs.append(cage.ring_part(name + '_cap', at, [(0.82, zc + 0.03), (0.9, zc + 0.17), (0.76, zc + 0.335)], zc, zc + 0.27,
                               material=['stickcap', 'stickknurl'], row_mats=[1, 1], crease_rows=[(0, 0.9), (2, 0.9)]))
    return objs

def face_button(name, at, z, letter, colour):
    b = lathe(name, [(0, z - 0.08), (0.45, z - 0.08), (0.45, z + 0.1)] + arc(0.45, z + 0.1, 0.36, z + 0.21, 4) + [(0, z + 0.22)], at=at, n=32, mats=('button',))
    t = text_mesh(name + '_label', letter, 0.8, 'letter_' + colour, (at[0], at[1], z + 0.223), bold=0.012)
    return [b, t]

def patch(name, B, surf, lift, edge, material):
    """A raised pad over a height field: outline B (N x 2), `lift` above the surface, rolled over within `edge`."""
    n = len(B)
    xs = np.arange(B[:, 0].min(), B[:, 0].max(), 0.22); ys = np.arange(B[:, 1].min(), B[:, 1].max(), 0.19)
    G = np.array([(x + (0.11 if j % 2 else 0), y) for j, y in enumerate(ys) for x in xs])
    d, _ = dist_to(resample(B, 0.03), G); G = G[inside(B, G) & (d > edge * 0.9)]
    rings = [B - normals2d(B) * edge * f for f in (0.3, 0.65)]
    pts = np.vstack([B] + rings + [G])
    V, T, ov = triangulate(pts, [list(range(n))])
    d, _ = dist_to(resample(B, 0.03), V); d[:n] = 0
    z = surf(V) - lift * np.sqrt(np.clip(1 - (1 - np.clip(d / edge, 0, 1)) ** 2, 0, 1)) + 0.03
    C = V[np.array(T)].mean(1); ok = inside(B, C)
    faces = [t[::-1] for t, o in zip(T, ok) if o]
    return mesh_obj(name, [(x, y, zz) for (x, y), zz in zip(V, z)], faces, [material], recalc=False)

def seam(name, I, width=0.035, lift=0.007):
    """Ribbon along the shell seam. Its depth behind the face grows from 1 cm at the top of the grip to 2.4 cm at the
    lobe, and more toward the inner side of the lobe (owner's side and underside photos)."""
    P, nrm, w, Rf, Rb, zs, zf, zb = (I[k] for k in ('P', 'nrm', 'w', 'Rf', 'Rb', 'zs', 'zf', 'zb'))
    n = len(P); rows = []
    for i in range(n):
        if w[i] < 0.6: rows.append(None); continue
        t = float(np.clip((3.9 - P[i][1]) / 9.9, 0, 1))
        zc = ZF - (1.0 + 1.4 * t + 0.8 * t * float(smoothstep((P[i][0] + 8.0) / 2.3)))
        pts = []
        for dz in (width / 2, -width / 2):
            zt = zc + dz; zfi = float(zf(P[i:i + 1])[0])
            if zt >= zs[i]:
                sn = float(np.clip(1 - (zfi - zt) / (w[i] * (zfi - zs[i])), 0, 1)); th = math.asin(sn); R = Rf[i]; sg = 1
            else:
                q = P[i]
                for _ in range(3):
                    zbi = float(zb(q[None])[0]); sn = float(np.clip(1 - (zt - zbi) / (w[i] * (zs[i] - zbi)), 0, 1)); th = math.asin(sn)
                    q = P[i] - nrm[i] * w[i] * Rb[i] * (1 - math.cos(th))
                R = Rb[i]; sg = -1
            xy = P[i] - nrm[i] * (w[i] * R * (1 - math.cos(th)) - lift * math.cos(th))
            pts.append((xy[0], xy[1], zt + sg * lift * math.sin(th)))
        rows.append(pts)
    verts = []; faces = []
    for i in range(n):
        a, b = rows[i], rows[(i + 1) % n]
        if a is None or b is None: continue
        k = len(verts); verts += [a[0], a[1], b[1], b[0]]; faces.append((k, k + 1, k + 2, k + 3))
    return mesh_obj(name, verts, faces, ['well'], recalc=False)

def mirror_x(ob):
    xform(ob, Matrix.Scale(-1, 4, (1, 0, 0)))
    bm = bmesh.new(); bm.from_mesh(ob.data); bmesh.ops.reverse_faces(bm, faces=bm.faces); bm.to_mesh(ob.data); bm.free()
    return ob


def seam(name, I, width=0.035, lift=0.007):
    """Ribbon along the shell seam. Its depth behind the face grows from 1 cm at the top of the grip to 2.4 cm at the
    lobe, and more toward the inner side of the lobe (owner's side and underside photos)."""
    P, nrm, w, Rf, Rb, zs, zf, zb = (I[k] for k in ('P', 'nrm', 'w', 'Rf', 'Rb', 'zs', 'zf', 'zb'))
    n = len(P); rows = []
    for i in range(n):
        if w[i] < 0.6: rows.append(None); continue
        t = float(np.clip((3.9 - P[i][1]) / 9.9, 0, 1))
        zc = ZF - (1.0 + 1.4 * t + 0.8 * t * float(smoothstep((P[i][0] + 8.0) / 2.3)))
        pts = []
        for dz in (width / 2, -width / 2):
            zt = zc + dz; zfi = float(zf(P[i:i + 1])[0])
            if zt >= zs[i]:
                sn = float(np.clip(1 - (zfi - zt) / (w[i] * (zfi - zs[i])), 0, 1)); th = math.asin(sn); R = Rf[i]; sg = 1
            else:
                q = P[i]
                for _ in range(3):
                    zbi = float(zb(q[None])[0]); sn = float(np.clip(1 - (zt - zbi) / (w[i] * (zs[i] - zbi)), 0, 1)); th = math.asin(sn)
                    q = P[i] - nrm[i] * w[i] * Rb[i] * (1 - math.cos(th))
                R = Rb[i]; sg = -1
            xy = P[i] - nrm[i] * (w[i] * R * (1 - math.cos(th)) - lift * math.cos(th))
            pts.append((xy[0], xy[1], zt + sg * lift * math.sin(th)))
        rows.append(pts)
    verts = []; faces = []
    for i in range(n):
        a, b = rows[i], rows[(i + 1) % n]
        if a is None or b is None: continue
        k = len(verts); verts += [a[0], a[1], b[1], b[0]]; faces.append((k, k + 1, k + 2, k + 3))
    return mesh_obj(name, verts, faces, ['well'], recalc=False)

def mirror_x(ob):
    xform(ob, Matrix.Scale(-1, 4, (1, 0, 0)))
    bm = bmesh.new(); bm.from_mesh(ob.data); bmesh.ops.reverse_faces(bm, faces=bm.faces); bm.to_mesh(ob.data); bm.free()
    return ob

LS = (-7.125, 1.465); DP = (-6.62, -1.69)                       # left stick, D-pad
BTN = {'Y': (7.0, 2.49, 'yellow'), 'X': (6.01, 1.465, 'blue'), 'B': (8.03, 1.465, 'red'), 'A': (7.0, 0.475, 'green')}
RS = (6.59, -1.68)                                               # right stick
PILL = (5.71, 2.92); STAR = (-5.88, -3.63); HOME = (5.88, -3.44)

WELLS = True

def grip(side):
    """One grip with everything attached to it, retracted. side = -1 left, +1 right."""
    objs = []; add = objs.append
    P, inner, bump = grip_outline()
    n = len(P)
    # edge roundness: 0 on the inner wall, 1 after 0.9 cm of outline
    idx = np.where(inner)[0]; k = np.arange(n)
    arcd = np.min(np.minimum(np.abs(k[:, None] - idx[None]), n - np.abs(k[:, None] - idx[None])), axis=1) * 0.12
    w = smoothstep(arcd / 0.9)
    # widest point of the section (where front and back shells meet): 1.85 cm behind the face, 2.6 cm at the lobe
    zs = ZF - (1.85 + 0.75 * smoothstep((-1.5 - P[:, 1]) / 3.5))
    # the shell is built as a left grip; the right one is mirrored afterwards, so its holes are given mirrored here
    if side < 0:
        holes = [dict(outline=[(LS[0] + x, LS[1] + y) for x, y in circle_pts(0.93, 36)], depth=0.5),
                 dict(outline=[(DP[0] + x, DP[1] + y) for x, y in cross(0.76, 2.26, 0.07)], depth=0.16)]
    else:
        holes = [dict(outline=[(-RS[0] + x, RS[1] + y) for x, y in circle_pts(0.93, 36)], depth=0.5)]
        holes += [dict(outline=[(-bx_ + x, by_ + y) for x, y in circle_pts(0.49, 20)], depth=0.1) for bx_, by_, _ in BTN.values()]
    # the face rolls off far on the outer side (1.4 cm), tighter along the top and bottom edges
    out_w = smoothstep((-P[:, 0] - 8.3) / 1.0) * smoothstep((P[:, 1] + 5.2) / 1.5)
    lobe = smoothstep((-4.4 - P[:, 1]) / 1.0)            # the lobe's nose is a full dome (owner's side photo)
    Rf = 0.8 + 0.6 * out_w + 1.2 * lobe - 0.3 * smoothstep((P[:, 1] - 2.5) / 1.0)
    # the pass-4 shell is only the reference the control cage starts from; the body is the cage's subdivision surface.
    # The wells are not cut into it: dark insets on the face stand in for them.
    ref, front, back = shell('8b_grip_ref', P, face, depth, w, zs, Rf=Rf, Rb=2.0, holes=())
    body = cage.grip_body('8b_grip', P, front.info, ref)
    add(body)
    # ---- shoulder: bumper over the top edge, trigger curling down the back. Both follow the bump of the traced silhouette.
    bx = np.linspace(-8.0, -6.02, 15)
    order = np.argsort(bump[:, 0]); by = np.interp(bx, bump[order, 0], bump[order, 1])
    by[0] = by[1] - 0.12; by[-1] = by[-2] - 0.1          # eased ends
    ZLB0, ZLB1 = ZF - 0.86, ZF - 2.38                               # from the top view: 8.6 to 23.8 mm behind the face
    lb = [(x, y) for x, y in zip(bx[::-1], by[::-1])] + [(bx[0], 2.9), (bx[-1], 3.2)]
    # bumper: a 12-point cage (a box two cells long) whose top follows the bump of the traced front silhouette
    xm = (bx[0] + bx[-1]) / 2; ytop = float(by.max()); y0 = 2.25
    bxs = [bx[0], xm, bx[-1]]; tops = [float(by[1]) - 0.05, float(np.interp(xm, bx, by)), float(by[-2]) - 0.05]; bots = [2.9, 3.05, 3.2]
    T = [(bxs[i], (bots, tops)[j][i], (ZLB1, ZLB0)[k]) for k in range(2) for j in range(2) for i in range(3)]
    F = cage.box_faces(2)
    add(cage.subd_part('8b_bumper', T, F, cage.auto_crease(T, F, 0.9), material='button'))
    # trigger: a 12-point cage, a six-point side profile at each end. The top runs back to a thin lip 3.9 cm behind the
    # face, the back drops 2 cm to the grip (owner's side and top photos); narrower toward the rear, like a tombstone
    prof = [(0.0, ZLB1 - 0.03, 0.97), (-0.38, ZF - 3.92, 0.82), (-0.6, ZF - 3.88, 0.82), (-0.8, ZF - 3.72, 0.9),
            (None, ZF - 3.42, 0.95), (None, ZLB1 - 0.03, 0.97)]
    hw = (bx[-1] - bx[0]) / 2
    # its top sits just under the bumper's line and slopes with it, so it never shows over the bumper from the front
    T = [(xm + sx * hw * k_, y0 if y is None else tops[sx + 1] - 0.08 + y, z_) for sx in (-1, 1) for y, z_, k_ in prof]
    F = [(i, (i + 1) % 6, 6 + (i + 1) % 6, 6 + i) for i in range(6)] + [(0, 1, 2, 3), (0, 3, 4, 5), (6, 7, 8, 9), (6, 9, 10, 11)]
    cr = {tuple(sorted((o_ + i, o_ + (i + 1) % 6))): 0.9 for o_ in (0, 6) for i in range(6)}        # the two side rims
    cr.update({(1, 7): 0.9, (2, 8): 0.9, (0, 6): 0.9})                                                # the lip and the front edge
    add(cage.subd_part('8b_trigger', T, F, cr, material='button'))
    # ---- back paddle (P1 / P2): a pad that follows the back surface
    pc = np.array((-6.35, -1.7))
    blob = chaikin([(0.75, 1.0), (-0.35, 1.15), (-0.85, 0.2), (-0.6, -0.95), (0.45, -1.1), (0.9, -0.2)], 3)
    add(patch('8b_paddle', resample(np.array(blob) + pc, 0.1), back, 0.13, 0.22, 'button'))
    # ---- on the inner wall: the rubber strip that presses on the phone's end
    r = rprism('8b_clamp', rrect(6.2, 0.62, 0.12, 3), -0.02, 0.035, ('backpad', 'backpad', 'backpad'), bt=0.03, seg=2)
    xform(r, tr(-XI, 0.0, 0.02) @ rot('Y', 90) @ rot('Z', 90)); add(r)
    # ---- the plate that carries the grip: a full-height stub next to the grip, then a tongue that slides in the bridge
    add(rprism('8b_stub', rrect(1.5, 8.19, 0.06, 2), ZF - 1.6, ZPAD - 0.02, ('plastic', 'plastic', 'plastic'), bt=0.04, seg=2, at=(-XI + 0.73, -0.135)))
    add(rprism('8b_tongue', rrect(4.4, 6.3, 0.1, 2), ZF - 1.8, ZF - 1.4, ('plastic', 'plastic', 'plastic'), bt=0.04, seg=2, at=(-XI + 2.2, 0.0)))
    add(rprism('8b_tongue_rib', rrect(4.4, 3.0, 0.08, 2), ZF - 1.42, ZF - 1.34, ('plastic', 'plastic', 'plastic'), bt=0.03, seg=2, at=(-XI + 2.2, 0.0)))
    # low fences at the top and bottom ends of the stub (the top one shows in the top view, 7.3 mm behind the face)
    add(rprism('8b_fence_top', rrect(0.5, 0.115, 0.04, 2), ZPAD - 0.1, ZF - 0.73, ('plastic', 'plastic', 'plastic'), bt=0.03, seg=2, at=(-XI + 0.25, 3.898)))   # a short jaw (owner's top photo)
    add(rprism('8b_fence_bottom', rrect(0.8, 0.3, 0.04, 2), ZPAD - 0.1, ZF - 0.73, ('plastic', 'plastic', 'plastic'), bt=0.03, seg=2, at=(-XI + 0.4, -4.08)))
    # ---- seam between the front and back shells: a thin dark ribbon riding on the rolled edge
    add(seam('8b_seam', front.info))
    if side > 0:
        # charging port on the underside of the right grip, 2.6 cm in from the outer side and 1.4 cm behind the face
        # (owner's underside photo): seated where a ray from below meets the body, lying in the surface there
        hit = cage.BVHTree.FromObject(body, bpy.context.evaluated_depsgraph_get()).ray_cast(Vector((-7.2, -12, ZF - 1.4)), Vector((0, 1, 0)))
        N = hit[1].normalized(); N = -N if N.y > 0 else N
        X = (Vector((1, 0, 0)) - N * N.x).normalized(); Y = N.cross(X)
        M = Matrix((X, Y, N)).transposed().to_4x4(); M.translation = hit[0] + N * 0.03
        for nm, shape, mt, zz in (('8b_charge_recess', stadium(1.5, 0.72, 6), 'well', 0.0), ('8b_charge_port', stadium(0.9, 0.32, 5), 'port', 0.004)):
            d = rprism(nm, shape, zz - 0.01, zz + 0.003, (mt, mt, mt)); xform(d, M); add(d)
    cage.ride([o for o in objs if o.name.split('.')[0] in ('8b_seam', '8b_paddle')], ref, body)
    bpy.data.objects.remove(ref)
    if WELLS:    # the wells are cut into the subdivided mesh, not modelled in the cage
        cage.cut_wells(body, [rprism(f'8b_wellcut{hi}', h['outline'], ZF - h['depth'], ZF + 0.6, ('well', 'well', 'well'), concave=True)
                              for hi, h in enumerate(holes)])
    if side > 0:
        for o in objs: mirror_x(o)
    sfx = '_l' if side < 0 else '_r'
    for o in objs: o.name = o.name.split('.')[0] + sfx
    # ---- raised letters on the bumper and the trigger, read from above with the front toward the reader
    xm = side * 7.01; ytop = float(by[len(by) // 2])
    def seat(lbl, part, zc):
        """Lay a label on the part's top surface, tilted with it."""
        bvh = cage.BVHTree.FromObject(part, bpy.context.evaluated_depsgraph_get())
        hit = lambda x, z: bvh.ray_cast(Vector((x, 9, z)), Vector((0, -1, 0)))[0]
        h0 = hit(xm, zc); a_ = math.atan2(hit(xm + 0.3, zc).y - hit(xm - 0.3, zc).y, 0.6); b_ = -math.atan2(hit(xm, zc + 0.25).y - hit(xm, zc - 0.25).y, 0.5)
        xform(lbl, tr(h0.x, h0.y + 0.006, h0.z) @ rot('Z', math.degrees(a_)) @ rot('X', math.degrees(b_)) @ rot('X', -90)); add(lbl)
    part = {o.name: o for o in objs}
    seat(text_mesh('8b_bumper_label' + sfx, ('L' if side < 0 else 'R') + 'B', 0.6, 'plastic', (0, 0, 0), bold=0.006), part['8b_bumper' + sfx], (ZLB0 + ZLB1) / 2 - 0.1)
    seat(text_mesh('8b_trigger_label' + sfx, ('L' if side < 0 else 'R') + 'T', 0.6, 'plastic', (0, 0, 0), bold=0.006), part['8b_trigger' + sfx], ZLB1 - 0.75)
    if side > 0:
        # USB-C plug for the phone, on the right grip's inner wall
        pl = rprism('8b_plug', stadium(0.24, 0.83, 5), 0.0, 0.62, ('stickmetal', 'stickmetal', 'stickmetal'), bt=0.04, seg=2)
        xform(pl, tr(XI, 0.0, 0.0) @ rot('Y', -90)); add(pl)
    # ---- controls, in their real places
    def pill(name, at, plus):
        add(rprism(name + '_gap', stadium(0.5, 0.83, 6), ZF - 0.05, ZF + 0.002, ('well', 'well', 'well'), at=at))
        add(rprism(name, stadium(0.43, 0.76, 6), ZF - 0.05, ZF + 0.05, ('plastic', 'plastic', 'plastic'), bt=0.035, seg=2, at=at))
        add(decal(name + '_mark', rrect(0.24, 0.05, 0.02, 2), at, ZF + 0.05))
        if plus: add(decal(name + '_mark2', rrect(0.05, 0.24, 0.02, 2), at, ZF + 0.05))
    def round_key(name, at, r):
        add(rprism(name + '_gap', circ(r + 0.04, 28), ZF - 0.05, ZF + 0.002, ('well', 'well', 'well'), at=at))
        add(rprism(name, circ(r, 28), ZF - 0.05, ZF + 0.05, ('plastic', 'plastic', 'plastic'), bt=0.035, seg=2, at=at))
    if side < 0:
        objs += stick('8b_stick_l', LS, float(front([LS])[0]))
        add(rprism('8b_dpad', cross(0.66, 2.14, 0.06), ZF - 0.15, ZF + 0.2, ('button', 'button', 'button'), bt=0.05, seg=2, at=DP, concave=True))
        add(lathe('8b_dpad_dimple', [(0, ZF + 0.185), (0.2, ZF + 0.192), (0.3, ZF + 0.203), (0.33, ZF + 0.2)], at=DP, n=24, mats=('well',)))
        for a in range(4):
            c, s = math.cos(a * math.pi / 2), math.sin(a * math.pi / 2)
            tri = [(0.92, 0), (0.62, 0.17), (0.62, -0.17)]
            add(decal(f'8b_dpad_arrow{a}', [(x * c - y * s, x * s + y * c) for x, y in tri], DP, ZF + 0.2))
        pill('8b_minus', (-PILL[0], PILL[1]), False)
        round_key('8b_star', STAR, 0.34)
        add(decal('8b_star_mark', star(0.2, 0.085), STAR, ZF + 0.05, concave=True))
        add(decal('8b_star_led', circ(0.035, 8), (STAR[0], STAR[1] + 0.56), ZF))
    else:
        for letter, (x, y, col) in BTN.items(): objs += face_button('8b_' + letter, (x, y), float(front([(-x, y)])[0]), letter, col)
        objs += stick('8b_stick_r', RS, float(front([(-RS[0], RS[1])])[0]))
        pill('8b_plus', PILL, True)
        round_key('8b_home', HOME, 0.36)
        for i in range(-1, 2):
            for j in range(-1, 2):
                if (i + j) % 2 == 0: add(decal(f'8b_home_mark{i}{j}', rrect(0.085, 0.085, 0.01, 1), (HOME[0] + i * 0.1, HOME[1] + j * 0.1), ZF + 0.05))
        add(decal('8b_home_led', circ(0.035, 8), (HOME[0], HOME[1] + 0.56), ZF))
    return objs

BRIDGE_B, BRIDGE_T = [float(v) for v in os.environ.get('BRIDGE_BT', '3.15 2.5').split()]   # depth behind the face, bottom and top edge

def bridge_back(x, y):
    """Depth of the bridge's back behind the face: 3.25 cm along the bottom edge, 2.67 cm along the top, where it is
    waisted in the middle and flares into the grips (owner's top and underside photos)."""
    t = np.clip((y + 4.1) / 8.0, 0, 1)
    return BRIDGE_B - (BRIDGE_B - BRIDGE_T) * smoothstep(t) + 0.15 * t * smoothstep((abs(x) - 4.2) / 0.95)

def bridge():
    return cage.bridge_cage(XI - 0.02, -4.112, 3.918, ZF - 1.22, lambda x, y: ZF - bridge_back(x, y))

def cages():
    """The control cages themselves (retracted), for an app that wants to subdivide on its own."""
    reset(); eightbitdo(stretched=False)
    return '8BitDoCages', [cage.cage_obj(e['name'] + '_cage', e['C'], e['F'], e['crease']) for e in cage.REG]

def eightbitdo(stretched=True):
    """stretched: the grips moved apart to hold the OnePlus 15 landscape; otherwise retracted as in the reference."""
    shift = (PHONE_L / 2 - XI + 0.035) if stretched else 0.0   # the rubber strips, 0.35 mm thick, just touch the phone
    objs = []; cage.REG.clear()
    for side in (-1, 1):
        cage.SIDE[0] = side; g = grip(side)
        for o in g: xform(o, tr(side * shift, 0, 0))
        objs += g
    # the bridge the grips slide out of, and the textured pad the phone lies on
    objs.append(bridge())
    objs.append(rprism('8b_pad', rrect(9.84, 7.38, 0.4, 5), ZF - 1.3, ZPAD, ('plastic', 'backpad', 'plastic'), bt=0.03, seg=2, at=(0, 0.06)))
    # on the bridge's top face: a recessed panel with the USB-C port and a small light hole (owner's top photo)
    zc = ZF - 1.22 - 0.72
    for nm, shape, mt, dx, zz in (('8b_panel', stadium(3.2, 1.0, 6), 'well', 0.0, 0.0), ('8b_usbc', stadium(0.85, 0.3, 5), 'port', 0.0, 0.004),
                                  ('8b_led', circ(0.05, 8), 'port', -0.9, 0.004)):
        d = rprism(nm, shape, zz - 0.01, zz + 0.003, (mt, mt, mt))
        xform(d, tr(dx, 3.918, zc) @ rot('X', -90)); objs.append(d)
    for o in objs: xform(o, tr(0, DY, 0))
    for e in cage.REG:      # the cages, carried to where their parts ended up
        if e['side'] > 0 and e['mirror']: e['C'][:, 0] *= -1
        e['C'][:, 0] += e['side'] * shift; e['C'][:, 1] += DY
        e['name'] += {-1: '_l', 0: '', 1: '_r'}[e['side']] if not e['name'].endswith(('_l', '_r', '_l_cap', '_r_cap')) else ''
    return '8BitDoUltimateMobile', objs
