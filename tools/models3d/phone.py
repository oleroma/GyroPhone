# OnePlus 15: 161.4 x 76.7 x 8.1 mm. Measured from the official multi-view render (4.229 px/mm).
# Built upright (u right, v up, z toward the viewer), then turned to landscape with the camera end on the left.
from lib import *

L, W, T = 16.14, 7.67, 0.81
R = 1.15           # body corner radius
SCREEN_INSET = 0.2  # frame + black border

def oneplus15():
    objs = []
    add = objs.append
    zf, zb = T / 2, -T / 2
    add(rprism('op15_body', rrect(W, L, R, 12), zb, zf, ('frame', 'frontglass', 'backglass'), bt=0.07, bb=0.07, seg=3))
    # Display: its own mesh with 0..1 UVs over its bounding box.
    so = rounded_rect(W - 2 * SCREEN_INSET, L - 2 * SCREEN_INSET, R - SCREEN_INSET, 10)
    sw, sh = W - 2 * SCREEN_INSET, L - 2 * SCREEN_INSET
    # UVs in landscape: u along the long side from the camera end, v up
    uvs = [((sh / 2 - y) / sh, (x + sw / 2) / sw) for x, y in so]
    add(mesh_obj('op15_screen', [(x, y, zf + 0.002) for x, y in so], [tuple(range(len(so)))], ['screen'], uvs=uvs))
    add(lathe('op15_selfie', [(0, zf + 0.004), (0.11, zf + 0.004), (0.175, zf + 0.004)], at=(0, L / 2 - 0.675), n=24,
              mats=('lens', 'lensglass'), seg_mats=[0, 1]))
    # ---- camera plateau, positions in mm from the top-left corner of the BACK view (x right, y down)
    def bk(x, y): return (W / 2 - x / 10, L / 2 - y / 10)
    px0, px1, py0, py1 = 5.15, 47.7, 5.6, 49.45
    pc = bk((px0 + px1) / 2, (py0 + py1) / 2)
    ph = 0.22
    zp = zb - ph
    add(rprism('op15_plateau', rrect((px1 - px0) / 10, (py1 - py0) / 10, 1.12, 10), zp, zb + 0.01,
               ('plateau', 'plateau', 'plateau'), bb=0.07, seg=3, at=pc))
    # tall black pill with two lenses, on the outer side
    pill_c = bk(17.5, 27.2)
    zg = zp - 0.035
    add(rprism('op15_pill', stadium(1.79, 3.76, 12), zg, zp + 0.01, ('lensring', 'lensglass', 'lensglass'), bb=0.03, seg=2, at=pill_c))
    def lens(name, c, r_out, r_lens, z, ring=True):
        # metal ring, dark barrel, coated glass in the middle
        prof = [(0, z - 0.012), (r_lens * 0.55, z - 0.012), (r_lens, z - 0.006), (r_out * 0.9, z - 0.004), (r_out, z - 0.001), (r_out, z + 0.004)]
        return lathe(name, prof, at=c, n=32, mats=('lens', 'lensglass', 'lensring'), seg_mats=[0, 0, 1, 2, 2])
    add(lens('op15_lens_wide', bk(17.5, 17.0), 0.33, 0.2, zg))
    add(lens('op15_lens_ultra', bk(17.5, 37.0), 0.33, 0.2, zg))
    add(rprism('op15_sensor', stadium(0.64, 0.24, 6), zg - 0.002, zg + 0.002, ('lensring', 'lensring', 'lensring'), at=pill_c))
    # round lens above a round flash window, on the inner side
    for nm, cy in (('tele', 16.3), ('flashwin', 38.0)):
        c = bk(36.6, cy)
        add(rprism('op15_' + nm, circ(0.73, 40), zg, zp + 0.01, ('lensring', 'lensglass', 'lensglass'), bb=0.03, seg=2, at=c))
    add(lens('op15_lens_tele', bk(36.6, 16.3), 0.36, 0.24, zg))
    add(lathe('op15_flash', [(0, zg - 0.006), (0.2, zg - 0.006), (0.27, zg - 0.002), (0.27, zg + 0.004)], at=bk(36.6, 38.0), n=24, mats=('flash',)))
    # ---- side keys. Seen from the front: volume and power on the right side, the Plus key on the left.
    def side_key(name, v0, v1, side):
        b = rprism(name, stadium(0.27, v1 - v0, 5), -0.02, 0.045, ('frame', 'frame', 'frame'), bt=0.025, seg=2)
        xform(b, tr(side * W / 2, (v0 + v1) / 2, 0) @ rot('Y', 90 * side) )
        return b
    add(side_key('op15_volume', L / 2 - 4.92, L / 2 - 2.89, 1))
    add(side_key('op15_power', L / 2 - 6.61, L / 2 - 5.60, 1))
    add(side_key('op15_pluskey', L / 2 - 3.76, L / 2 - 2.89, -1))
    # ---- ports: thin dark decals on the bottom and top ends
    def end_decal(name, shape, u, end):
        d = rprism(name, shape, -0.01, 0.002, ('port', 'port', 'port'))
        xform(d, tr(u, end * L / 2, 0) @ rot('X', -90 * end))
        return d
    add(end_decal('op15_usbc', stadium(0.89, 0.3, 6), 0, -1))
    add(end_decal('op15_speaker1', stadium(0.6, 0.11, 4), 1.71, -1))
    add(end_decal('op15_speaker2', stadium(0.6, 0.11, 4), 2.42, -1))
    add(end_decal('op15_mic1', circ(0.045, 10), 1.03, -1))
    add(end_decal('op15_mic2', circ(0.045, 10), -1.03, -1))
    add(end_decal('op15_simhole', circ(0.04, 10), -2.57, -1))
    add(end_decal('op15_topslot1', stadium(0.36, 0.1, 4), -1.27, 1))
    add(end_decal('op15_topslot2', stadium(0.36, 0.1, 4), -0.81, 1))
    add(end_decal('op15_topmic', circ(0.045, 10), 1.11, 1))
    # ---- landscape: top end to the left
    for o in objs: xform(o, rot('Z', 90))
    return 'OnePlus15', objs
