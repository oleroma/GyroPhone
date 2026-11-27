# GP handles: our own Switch-style rails for the OnePlus 15 held landscape. Not a copy of any product.
# Two handles as tall as the phone's short side, joined by a thin plate behind the phone.
from lib import *
from eightbitdo import stick, face_button, decal, mirror_x, smoothstep, patch

PH_L, PH_W, PH_T = 16.14, 7.67, 0.81
XI = PH_L / 2        # inner wall = the phone's end
HW, HH = 3.5, 8.3    # handle width and height
ZF = 0.55            # front face, a little proud of the screen

def handle_outline():
    r = 1.45
    pts = [(-XI, HH / 2)]
    for cx, cy, a0 in ((-XI - HW + r, HH / 2 - r, 90), (-XI - HW + r, -HH / 2 + r, 180)):
        pts += [(cx + r * math.cos(math.radians(a0 + 90 * i / 12)), cy + r * math.sin(math.radians(a0 + 90 * i / 12))) for i in range(13)]
    pts.append((-XI, -HH / 2))
    P = resample(np.array(pts), 0.12)
    inner = P[:, 0] > -XI - 0.02
    P[inner, 0] = -XI
    return P, inner

def depth(Q):
    """A soft hump for the fingers, deepest low on the outer half."""
    u = np.clip(-Q[:, 0] - XI, 0, None)
    return -(0.95 + 1.35 * smoothstep(u / 1.9) * (0.55 + 0.45 * smoothstep((1.5 - Q[:, 1]) / 4.0)))

def handle(side):
    objs = []; add = objs.append
    P, inner = handle_outline(); n = len(P)
    idx = np.where(inner)[0]; k = np.arange(n)
    arcd = np.min(np.minimum(np.abs(k[:, None] - idx[None]), n - np.abs(k[:, None] - idx[None])), axis=1) * 0.12
    w = smoothstep(arcd / 0.7)
    cx = -XI - HW / 2 - 0.05
    up, lo = (cx, 1.75), (cx, -1.55)
    offs = [(0, 0.8), (0.8, 0), (0, -0.8), (-0.8, 0)]
    stick_at = up if side < 0 else lo
    keys_at = lo if side < 0 else up
    holes = [dict(outline=[(stick_at[0] + x, stick_at[1] + y) for x, y in circle_pts(0.93, 36)], depth=0.5)]
    holes += [dict(outline=[(keys_at[0] + dx + x, keys_at[1] + dy + y) for x, y in circle_pts(0.44, 20)], depth=0.1) for dx, dy in offs]
    body, front, back = shell('gp_handle', P, lambda Q: np.full(len(Q), ZF), depth, w, -0.25, Rf=0.55, Rb=1.7, holes=holes, grid=0.24)
    add(body)
    # shoulder: a bumper along the top edge and a trigger behind it
    add(rprism('gp_bumper', rrect(2.5, 0.5, 0.22, 4), -0.95, 0.12, ('button', 'button', 'button'), bt=0.1, seg=3, at=(cx - 0.1, HH / 2 + 0.02)))
    loops = []
    for j in range(7):
        a = math.radians(80) * j / 6
        sec = rounded_rect(2.3 * (1 - 0.25 * (j / 6) ** 2), 0.5, 0.2, 3)
        loops.append([(cx - 0.1 + x, 3.2 + (0.95 + y) * math.cos(a), -1.0 - (0.95 + y) * 1.25 * math.sin(a)) for x, y in sec])
    add(loft('gp_trigger', loops, [0] * 6, (0, 0), ['button']))
    # grip pad on the back, following the hump
    blob = chaikin([(0.9, 1.6), (-0.7, 1.7), (-1.05, 0.0), (-0.8, -1.9), (0.7, -2.0), (1.0, -0.2)], 3)
    add(patch('gp_backgrip', resample(np.array(blob) + np.array((cx - 0.15, -0.9)), 0.1), back, 0.05, 0.15, 'backpad'))
    # rubber strip that presses on the phone's end
    r = rprism('gp_clamp', rrect(6.6, 0.7, 0.12, 3), -0.02, 0.03, ('backpad', 'backpad', 'backpad'), bt=0.025, seg=2)
    xform(r, tr(-XI, 0.0, 0.0) @ rot('Y', 90) @ rot('Z', 90)); add(r)
    if side > 0:
        for o in objs: mirror_x(o)
    sfx = '_l' if side < 0 else '_r'
    for o in objs: o.name = o.name.split('.')[0] + sfx
    m = -side  # controls are placed mirrored on the right handle
    def P2(p): return (m * p[0] * -1 if side > 0 else p[0], p[1])
    X = lambda p: ((-p[0], p[1]) if side > 0 else p)
    zl = lambda p: float(front([p])[0])
    objs += stick('gp_stick' + sfx, X(stick_at), zl(stick_at))
    if side < 0:
        for (dx, dy), nm in zip(offs, ('up', 'right', 'down', 'left')):
            c = (keys_at[0] + dx, keys_at[1] + dy)
            add(lathe('gp_' + nm, [(0, ZF - 0.08), (0.4, ZF - 0.08), (0.4, ZF + 0.08)] + arc(0.4, ZF + 0.08, 0.32, ZF + 0.16, 3) + [(0, ZF + 0.17)], at=c, n=28, mats=('button',)))
            a = math.atan2(dy, dx); cs, sn = math.cos(a), math.sin(a)
            tri = [(0.17, 0), (-0.1, 0.15), (-0.1, -0.15)]
            add(decal('gp_' + nm + '_mark', [(c[0] + x * cs - y * sn, c[1] + x * sn + y * cs) for x, y in tri], (0, 0), ZF + 0.172))
    else:
        for (dx, dy), (letter, col) in zip(offs, (('X', 'blue'), ('A', 'green'), ('B', 'red'), ('Y', 'yellow'))):
            c = (-(keys_at[0] - dx), keys_at[1] + dy)
            b, t = face_button('gp_' + letter, c, ZF, letter, col)
            xform(b, tr(c[0], c[1], ZF) @ Matrix.Diagonal((0.89, 0.89, 0.8, 1)) @ tr(-c[0], -c[1], -ZF))
            xform(t, tr(c[0], c[1], ZF) @ Matrix.Diagonal((0.8, 0.8, 0.8, 1)) @ tr(-c[0], -c[1], -ZF))
            objs += [b, t]
    # small keys along the inner edge: minus / plus on top, capture / home below
    sx = side * (XI + 0.62)
    add(rprism('gp_pm' + sfx, stadium(0.62, 0.3, 5), ZF - 0.05, ZF + 0.05, ('button', 'button', 'button'), bt=0.03, seg=2, at=(sx, 3.45)))
    add(decal('gp_pm_mark' + sfx, rrect(0.26, 0.05, 0.02, 2), (sx, 3.45), ZF + 0.05, 'plastic'))
    if side > 0: add(decal('gp_plus_mark', rrect(0.05, 0.2, 0.02, 2), (sx, 3.45), ZF + 0.05, 'plastic'))
    if side < 0:
        add(rprism('gp_capture', rrect(0.5, 0.5, 0.12, 3), ZF - 0.05, ZF + 0.05, ('button', 'button', 'button'), bt=0.03, seg=2, at=(sx, -3.4)))
        add(decal('gp_capture_mark', circ(0.12, 16), (sx, -3.4), ZF + 0.05, 'plastic'))
    else:
        add(rprism('gp_home', circ(0.3, 24), ZF - 0.05, ZF + 0.05, ('button', 'button', 'button'), bt=0.03, seg=2, at=(sx, -3.4)))
        add(decal('gp_home_mark', circ(0.13, 16), (sx, -3.4), ZF + 0.05, 'plastic'))
    return objs

def gp_handles():
    objs = handle(-1) + handle(1)
    zb = -PH_T / 2
    objs.append(rprism('gp_backplate', rrect(PH_L + 0.5, 5.6, 0.5, 5), zb - 0.5, zb - 0.06, ('plastic', 'plastic', 'plastic'), bt=0.04, bb=0.2, seg=3))
    objs.append(rprism('gp_backplate_pad', rrect(PH_L - 5.2, 5.0, 0.3, 4), zb - 0.08, zb, ('backpad', 'backpad', 'backpad'), bt=0.02, seg=2, at=(1.2, 0)))
    for y in (PH_W / 2 + 0.07, -PH_W / 2 - 0.07):   # slim rails above and below the phone
        objs.append(rprism('gp_rail', rrect(PH_L + 0.1, 0.14, 0.05, 2), zb - 0.3, -0.1, ('plastic', 'plastic', 'plastic'), bt=0.04, seg=2, at=(0, y)))
    return 'GPHandles', objs
