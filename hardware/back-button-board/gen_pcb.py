#!/usr/bin/env python3
"""Board file from the schematic, with the 8 back buttons placed from board-settings.json.

  python3 gen_pcb.py --new   builds back-button-board.kicad_pcb from scratch (overwrites it)
  python3 gen_pcb.py         moves only the buttons and sockets on the existing board, so
                             other placement and routing stays; re-route the button traces after

Run with KiCad's Python (the one that can `import pcbnew`). Coordinates: the board's
top-left corner sits at (100, 100) mm; x grows right, y grows down, the board seen from the back
(the side the buttons are on)."""
import json, os, re, subprocess, sys, tempfile
import pcbnew

HERE = os.path.dirname(os.path.abspath(__file__))
NAME = 'back-button-board'
SCH, PCB = (os.path.join(HERE, NAME + e) for e in ('.kicad_sch', '.kicad_pcb'))
NET = os.path.join(tempfile.mkdtemp(), NAME + '.net')
FPDIR = next((os.environ[k] for k in ('KICAD7_FOOTPRINT_DIR', 'KICAD8_FOOTPRINT_DIR', 'KICAD9_FOOTPRINT_DIR') if k in os.environ),
             '/usr/share/kicad/footprints')
S = json.load(open(os.path.join(HERE, 'board-settings.json')))
X0, Y0 = 100.0, 100.0
W, H = S['outline_mm']['width'], S['outline_mm']['height']
mm = pcbnew.FromMM

def button_positions():
    """SW1-4 = left side (BTN_L1..L4), SW5-8 = right side (BTN_R1..R4), mirrored.
    Each next button is one step in (toward the centre) and one step down."""
    b = S['buttons']; fx, fy = b['first_from_top_corner_mm']['x'], b['first_from_top_corner_mm']['y']
    sx, sy = b['step_mm']['x'], b['step_mm']['y']
    out = {}
    for i in range(b['per_side']):
        out[f'SW{i+1}'] = (X0 + fx + i * sx, Y0 + fy + i * sy)
        out[f'SW{b["per_side"]+i+1}'] = (X0 + W - fx - i * sx, Y0 + fy + i * sy)
    return out

def socket_pose(edge, offset):
    # HRO TYPE-C-31-M-12: the plug opening faces -y at rotation 0; its body sits ~4 mm behind the edge.
    if edge == 'top': return X0 + W / 2 + offset, Y0 + 4, 0
    if edge == 'bottom': return X0 + W / 2 + offset, Y0 + H - 4, 180
    if edge == 'left': return X0 + 4, Y0 + H / 2 + offset, 90
    if edge == 'right': return X0 + W - 4, Y0 + H / 2 + offset, -90
    sys.exit(f'unknown edge {edge}')

def place(fp, x, y, rot=None):
    fp.SetPosition(pcbnew.VECTOR2I(mm(x), mm(y)))
    if rot is not None: fp.SetOrientationDegrees(rot)

def fixed_positions():
    pos = button_positions()
    for ref, key in (('J1', 'pc'), ('J2', 'phone')):
        s = S['sockets'][key]; pos[ref] = socket_pose(s['edge'], s['offset_mm'])
    return pos

def read_netlist():
    subprocess.run(['kicad-cli', 'sch', 'export', 'netlist', '-o', NET, SCH], check=True, capture_output=True)
    t = open(NET, encoding='utf-8').read()
    comps = re.findall(r'\(comp \(ref "([^"]+)"\)\s*\(value "([^"]*)"\)\s*\(footprint "([^"]*)"\)', t)
    pads = {}
    for block in re.findall(r'\(net \(code "\d+"\) \(name "([^"]+)"\)(.*?)\)\)\s*(?=\(net |\)\s*\)\s*$)', t, re.S):
        name, body = block
        for ref, pin in re.findall(r'\(node \(ref "([^"]+)"\) \(pin "([^"]+)"\)', body):
            pads[(ref, pin)] = name.lstrip('/')
    return comps, pads

def new_board():
    comps, pads = read_netlist()
    board = pcbnew.BOARD()
    board.SetCopperLayerCount(4)
    nets = {}
    for n in sorted(set(pads.values())):
        ni = pcbnew.NETINFO_ITEM(board, n); board.Add(ni); nets[n] = ni
    # outline
    for (ax, ay), (bx, by) in [((0, 0), (W, 0)), ((W, 0), (W, H)), ((W, H), (0, H)), ((0, H), (0, 0))]:
        seg = pcbnew.PCB_SHAPE(board); seg.SetShape(pcbnew.SHAPE_T_SEGMENT); seg.SetLayer(pcbnew.Edge_Cuts)
        seg.SetStart(pcbnew.VECTOR2I(mm(X0 + ax), mm(Y0 + ay))); seg.SetEnd(pcbnew.VECTOR2I(mm(X0 + bx), mm(Y0 + by)))
        seg.SetWidth(mm(0.1)); board.Add(seg)
    fixed = fixed_positions()
    a = S['electronics_area_mm']; gx, gy = X0 + a['x'], Y0 + a['y']; cx, cy, rowh = gx, gy, 0
    for ref, val, fpid in comps:
        if not fpid: continue
        lib, name = fpid.split(':')
        fp = pcbnew.FootprintLoad(os.path.join(FPDIR, lib + '.pretty'), name)
        if fp is None: sys.exit(f'{ref}: footprint {fpid} not found')
        fp.SetReference(ref); fp.SetValue(val)
        fp.SetFPID(pcbnew.LIB_ID(lib, name))
        for pad in fp.Pads():
            n = pads.get((ref, pad.GetNumber()))
            if n: pad.SetNet(nets[n])
        board.Add(fp)
        if ref in fixed:
            x, y, *r = fixed[ref]; place(fp, x, y, (r[0] if r else S['buttons']['rotation_deg']))
        else:   # rough grid inside the electronics area; real placement happens by hand
            bb = fp.GetBoundingBox(False, False); w, h = pcbnew.ToMM(bb.GetWidth()) + 1, pcbnew.ToMM(bb.GetHeight()) + 1
            if cx + w > gx + a['width']: cx, cy, rowh = gx, cy + rowh, 0
            place(fp, cx + w / 2, cy + h / 2); cx += w; rowh = max(rowh, h)
    board.Save(PCB)
    print(f'new board: {len(comps)} parts, {len(nets)} nets, {W} x {H} mm')

def move_only():
    board = pcbnew.LoadBoard(PCB)
    fixed = fixed_positions()
    for ref, (x, y, *r) in fixed.items():
        fp = board.FindFootprintByReference(ref)
        place(fp, x, y, (r[0] if r else S['buttons']['rotation_deg']))
    board.Save(PCB)
    print('moved', ', '.join(fixed))

if __name__ == '__main__':
    if '--new' in sys.argv or not os.path.exists(PCB): new_board()
    else: move_only()
    for ref, (x, y) in button_positions().items():
        print(f'  {ref}: {x - X0:.1f}, {y - Y0:.1f} mm from the top-left corner')
