#!/usr/bin/env python3
"""Generates back-button-board.kicad_sch from the netlist below.
Every pin gets a net label at its connection point, so the schematic is a
readable netlist: change a net here, rerun, and the schematic follows."""
import os, sys, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sexp import flat, pins, dump, q, uq

NS = uuid.UUID('6b1f0c2e-2b4e-4d0a-9a51-6d1b0c5e7a11')
def uid(*k): return str(uuid.uuid5(NS, '/'.join(map(str, k))))
ROOT = uid('root')
PROJECT = 'back-button-board'

R0402, C0402, C0603 = 'Resistor_SMD:R_0402_1005Metric', 'Capacitor_SMD:C_0402_1005Metric', 'Capacitor_SMD:C_0603_1608Metric'
USBC_FP = 'Connector_USB:USB_C_Receptacle_HRO_TYPE-C-31-M-12'
TP_FP = 'TestPoint:TestPoint_Pad_D1.0mm'

# ref, lib, symbol, value, footprint, {pin number or name: net}   (None = no connect)
P = []
def part(ref, lib, sym, val, fp, nets, group): P.append(dict(ref=ref, lib=lib, sym=sym, val=val, fp=fp, nets=nets, group=group))
def R(ref, val, a, b, g): part(ref, 'Device', 'R', val, R0402, {'1': a, '2': b}, g)
def C(ref, val, a, b='GND', g='', fp=None): part(ref, 'Device', 'C', val, fp or (C0603 if 'u' in val and val not in ('1u', '0.1u') else C0402), {'1': a, '2': b}, g)
def TP(ref, net, g): part(ref, 'Connector', 'TestPoint', net, TP_FP, {'1': net}, g)

usbc = lambda pre, vbus: {'A4': vbus, 'A9': vbus, 'B4': vbus, 'B9': vbus, 'A5': pre + '_CC1', 'B5': pre + '_CC2',
                          'A6': pre + '_DP', 'B6': pre + '_DP', 'A7': pre + '_DN', 'B7': pre + '_DN',
                          'A8': None, 'B8': None, 'A1': 'GND', 'A12': 'GND', 'B1': 'GND', 'B12': 'GND', 'S1': 'GND'}

# --- PC socket and its power path
g = '1 PC socket'
part('J1', 'Connector', 'USB_C_Receptacle_USB2.0_16P', 'PC socket', USBC_FP, usbc('PC', 'VBUS_PC'), g)
R('R1', '5.1k', 'PC_CC1', 'GND', g)
R('R2', '5.1k', 'PC_CC2', 'GND', g)
part('U1', 'Power_Protection', 'USBLC6-2SC6', 'USBLC6-2SC6', 'Package_TO_SOT_SMD:SOT-23-6',
     {'1': 'PC_DP', '6': 'PC_DP', '3': 'PC_DN', '4': 'PC_DN', '5': 'VBUS_PC', '2': 'GND'}, g)
part('U2', 'Power_Management', 'AP22814AW5', 'AP22814AW5', 'Package_TO_SOT_SMD:SOT-23-5',
     {'5': 'VBUS_PC', '4': 'VBUS_PC', '1': '+5V', '3': None, '2': 'GND'}, g)
C('C1', '10u', 'VBUS_PC', g=g)
R('R3', '100k', 'VBUS_PC', 'PC_VBUS_DET', g)
R('R4', '100k', 'PC_VBUS_DET', 'GND', g)

# --- Phone socket, its power path and role controller
g = '2 Phone socket'
part('J2', 'Connector', 'USB_C_Receptacle_USB2.0_16P', 'Phone socket', USBC_FP, usbc('PH', 'VBUS_PHONE'), g)
part('U3', 'Power_Protection', 'USBLC6-2SC6', 'USBLC6-2SC6', 'Package_TO_SOT_SMD:SOT-23-6',
     {'1': 'PH_DP', '6': 'PH_DP', '3': 'PH_DN', '4': 'PH_DN', '5': 'VBUS_PHONE', '2': 'GND'}, g)
part('U4', 'Power_Management', 'AP22814AW5', 'AP22814AW5', 'Package_TO_SOT_SMD:SOT-23-5',
     {'5': '+5V', '4': 'PHONE_PWR_EN', '1': 'VBUS_PHONE', '3': 'PHONE_PWR_FLG_N', '2': 'GND'}, g)
R('R5', '100k', 'PHONE_PWR_EN', 'GND', g)
part('D1', 'Device', 'D_Schottky', 'B5819W', 'Diode_SMD:D_SOD-123', {'1': '+5V', '2': 'VBUS_PHONE'}, g)
C('C2', '4.7u', 'VBUS_PHONE', g=g)
part('U5', 'Interface_USB', 'FUSB302BMPX', 'FUSB302BMPX', 'Package_DFN_QFN:WQFN-14-1EP_2.5x2.5mm_P0.5mm_EP1.45x1.45mm',
     {'2': 'VBUS_PHONE', '3': '+3V3', '4': '+3V3', '12': None, '13': None, '10': 'PH_CC1', '11': 'PH_CC1',
      '1': 'PH_CC2', '14': 'PH_CC2', '5': 'FUSB_INT_N', '6': 'I2C_SCL', '7': 'I2C_SDA', '8': 'GND', '9': 'GND', '15': 'GND'}, g)
C('C3', '0.1u', '+3V3', g=g)
C('C4', '1u', '+3V3', g=g)
R('R6', '4.7k', '+3V3', 'I2C_SDA', g)
R('R7', '4.7k', '+3V3', 'I2C_SCL', g)
R('R8', '4.7k', '+3V3', 'FUSB_INT_N', g)

# --- 5 V rail to 3.3 V
g = '3 Power'
part('U6', 'Regulator_Linear', 'AP2112K-3.3', 'AP2112K-3.3', 'Package_TO_SOT_SMD:SOT-23-5',
     {'1': '+5V', '3': '+5V', '5': '+3V3', '4': None, '2': 'GND'}, g)
C('C5', '10u', '+5V', g=g)
C('C6', '1u', '+3V3', g=g)
C('C7', '10u', '+3V3', g=g)
part('#FLG1', 'power', 'PWR_FLAG', 'PWR_FLAG', '', {'1': 'GND'}, g)
part('#FLG2', 'power', 'PWR_FLAG', 'PWR_FLAG', '', {'1': 'VBUS_PC'}, g)

# --- Hub (powered only from the PC) and the two switches
g = '4 Hub and switches'
part('U7', 'Interface_USB', 'CH334R', 'CH334R', 'Package_SO:QSOP-16_3.9x4.9mm_P0.635mm',
     {'12': 'VBUS_PC', '13': 'HUB_VDD33', '14': 'GND', '11': 'PC_DP', '10': 'PC_DN',
      '8': 'HUB1_DP', '7': 'HUB1_DN', '6': 'HUB2_DP', '5': 'HUB2_DN', '4': None, '3': None, '2': None, '1': None,
      '9': 'HUB_RST', '15': 'HUB_XO', '16': 'HUB_XI'}, g)
C('C8', '1u', 'VBUS_PC', g=g)
C('C9', '1u', 'HUB_VDD33', g=g)
C('C10', '0.1u', 'HUB_VDD33', g=g)
part('Y2', 'Device', 'Crystal_GND24', '12MHz', 'Crystal:Crystal_SMD_3225-4Pin_3.2x2.5mm',
     {'1': 'HUB_XI', '3': 'HUB_XO', '2': 'GND', '4': 'GND'}, g)
C('C11', '20p', 'HUB_XI', g=g)
C('C12', '20p', 'HUB_XO', g=g)
part('D2', 'Device', 'D_Schottky', 'BAT54', 'Package_TO_SOT_SMD:SOT-23', {'1': 'HUB_RST_N', '2': 'HUB_RST'}, g)
# Switch A: phone <-> direct path (D1, default) or hub port 1 (D2)
part('U8', 'Interface_USB', 'TS3USB30EDGSR', 'TS3USB30E', 'Package_SO:VSSOP-10_3x3mm_P0.5mm',
     {'4': 'PH_DP', '6': 'PH_DN', '2': 'DIR_DP', '8': 'DIR_DN', '3': 'HUB1_DP', '7': 'HUB1_DN',
      '1': 'MUX_SEL', '9': 'GND', '10': '+3V3', '5': 'GND'}, g)
# Switch B: RP2040 <-> direct path (D1, default) or hub port 2 (D2)
part('U9', 'Interface_USB', 'TS3USB30EDGSR', 'TS3USB30E', 'Package_SO:VSSOP-10_3x3mm_P0.5mm',
     {'4': 'MCU_DP', '6': 'MCU_DN', '2': 'DIR_DP', '8': 'DIR_DN', '3': 'HUB2_DP', '7': 'HUB2_DN',
      '1': 'MUX_SEL', '9': 'GND', '10': '+3V3', '5': 'GND'}, g)
C('C13', '0.1u', '+3V3', g=g)
C('C14', '0.1u', '+3V3', g=g)
R('R9', '100k', 'MUX_SEL', 'GND', g)

# --- RP2040 and flash
g = '5 RP2040'
gpio = {0: 'BTN_L1', 1: 'BTN_L2', 2: 'BTN_L3', 3: 'BTN_L4', 4: 'BTN_R1', 5: 'BTN_R2', 6: 'BTN_R3', 7: 'BTN_R4',
        13: 'HUB_RST_N', 14: 'MUX_SEL', 16: 'FUSB_INT_N', 18: 'PHONE_PWR_EN', 19: 'PHONE_PWR_FLG_N',
        20: 'I2C_SDA', 21: 'I2C_SCL', 22: 'PC_VBUS_DET'}
mcu = {'1': '+3V3', '10': '+3V3', '22': '+3V3', '33': '+3V3', '42': '+3V3', '49': '+3V3',
       '43': '+3V3', '44': '+3V3', '48': '+3V3', '45': 'DVDD', '23': 'DVDD', '50': 'DVDD', '57': 'GND', '19': 'GND',
       '20': 'XIN', '21': 'XOUT_R', '24': 'SWCLK', '25': 'SWDIO', '26': 'RUN', '46': 'USB_DN_R', '47': 'USB_DP_R',
       '51': 'QSPI_SD3', '52': 'QSPI_SCLK', '53': 'QSPI_SD0', '54': 'QSPI_SD2', '55': 'QSPI_SD1', '56': 'QSPI_SS',
       '38': 'PC_CC1', '39': 'PC_CC2'}
gpio_pin = {0: 2, 1: 3, 2: 4, 3: 5, 4: 6, 5: 7, 6: 8, 7: 9, 8: 11, 9: 12, 10: 13, 11: 14, 12: 15, 13: 16, 14: 17, 15: 18,
            16: 27, 17: 28, 18: 29, 19: 30, 20: 31, 21: 32, 22: 34, 23: 35, 24: 36, 25: 37, 28: 40, 29: 41}
for n, pin in gpio_pin.items(): mcu[str(pin)] = gpio.get(n)
part('U10', 'MCU_RaspberryPi', 'RP2040', 'RP2040', 'Package_DFN_QFN:QFN-56-1EP_7x7mm_P0.4mm_EP3.2x3.2mm', mcu, g)
R('R10', '27', 'USB_DP_R', 'MCU_DP', g)
R('R11', '27', 'USB_DN_R', 'MCU_DN', g)
part('Y1', 'Device', 'Crystal_GND24', '12MHz', 'Crystal:Crystal_SMD_3225-4Pin_3.2x2.5mm',
     {'1': 'XIN', '3': 'XOUT', '2': 'GND', '4': 'GND'}, g)
R('R12', '1k', 'XOUT_R', 'XOUT', g)
C('C15', '15p', 'XIN', g=g)
C('C16', '15p', 'XOUT', g=g)
for i in range(6): C(f'C{17+i}', '0.1u', '+3V3', g=g)          # IOVDD, one per pin
C('C23', '1u', '+3V3', g=g)                                    # VREG_IN
C('C24', '1u', 'DVDD', g=g)                                    # VREG_VOUT
C('C25', '0.1u', 'DVDD', g=g)
C('C26', '0.1u', 'DVDD', g=g)
R('R13', '10k', '+3V3', 'RUN', g)
part('U11', 'Memory_Flash', 'W25Q32JVSS', 'W25Q16JVSSIQ', 'Package_SO:SOIC-8_5.23x5.23mm_P1.27mm',
     {'1': 'QSPI_SS', '2': 'QSPI_SD1', '3': 'QSPI_SD2', '7': 'QSPI_SD3', '5': 'QSPI_SD0', '6': 'QSPI_SCLK',
      '8': '+3V3', '4': 'GND'}, g)
C('C27', '0.1u', '+3V3', g=g)
R('R14', '10k', '+3V3', 'QSPI_SS', g)
for i, n in enumerate(['QSPI_SS', 'RUN', 'SWCLK', 'SWDIO', '+3V3', 'GND']): TP(f'TP{i+1}', n, g)

# --- Back buttons
g = '6 Buttons'
for i, n in enumerate(['BTN_L1', 'BTN_L2', 'BTN_L3', 'BTN_L4', 'BTN_R1', 'BTN_R2', 'BTN_R3', 'BTN_R4']):
    part(f'SW{i+1}', 'Switch', 'SW_Push', n[4:], 'Button_Switch_SMD:SW_SPST_SKQG_WithoutStem', {'1': n, '2': 'GND'}, g)

# ---------------------------------------------------------------- writer
syms = {}
for p in P:
    key = f"{p['lib']}:{p['sym']}"
    if key not in syms: syms[key] = flat(p['lib'], p['sym'])
    p['pins'] = pins(syms[key])
    known = {x['num'] for x in p['pins']} | {x['name'] for x in p['pins']}
    for k in p['nets']:
        if k not in known: sys.exit(f"{p['ref']}: no pin {k}")
    for x in p['pins']:
        if x['num'] not in p['nets'] and x['name'] not in p['nets'] and x['type'] != 'no_connect':
            sys.exit(f"{p['ref']}: pin {x['num']} {x['name']} not assigned")

def bbox(p):
    xs = [x['x'] for x in p['pins']] or [0]; ys = [x['y'] for x in p['pins']] or [0]
    return min(xs), max(xs), min(ys), max(ys)

out = []
snap = lambda v: round(v / 2.54) * 2.54
groups = []
for p in P:
    if p['group'] not in groups: groups.append(p['group'])
ox, row_y = 30.48, 30.48
for gname in groups:
    members = [p for p in P if p['group'] == gname]
    x, y, rowh = 30.48, row_y + 12.7, 0
    out.append(f'(text {q(gname)} (at 25.4 {row_y:.2f} 0) (effects (font (size 3 3) bold) (justify left bottom)) (uuid {uid("t", gname)}))')
    for p in members:
        x0, x1, y0, y1 = bbox(p)
        L = 24 if any(int(a['a']) == 0 for a in p['pins']) else 6
        Rr = 24 if any(int(a['a']) == 180 for a in p['pins']) else 6
        T = 14 if any(int(a['a']) == 270 for a in p['pins']) else 6
        B = 14 if any(int(a['a']) == 90 for a in p['pins']) else 6
        w = (x1 - x0) + L + Rr; h = (y1 - y0) + T + B + 6
        if x + w > 800: x, y, rowh = 30.48, y + rowh, 0
        p['at'] = (snap(x + L - x0), snap(y + T + y1))
        x += w; rowh = max(rowh, h)
    row_y = y + rowh + 4

def label_for(net, px, py, a):
    la = (a + 180) % 360
    just = {0: 'left', 180: 'right', 90: 'left', 270: 'right'}[int(la)]
    return f'(label {q(net)} (at {px:.2f} {py:.2f} {int(la)}) (fields_autoplaced) (effects (font (size 1.27 1.27)) (justify {just} bottom)) (uuid {uid("l", net, round(px,2), round(py,2))}))'

body = []
for p in P:
    X, Y = p['at']; key = f"{p['lib']}:{p['sym']}"
    power = p['lib'] == 'power'
    hide = ' hide' if power else ''
    side = not any(int(a['a']) in (0, 180) for a in p['pins'])
    props = [('Reference', p['ref'], Y - 2.54 - (bbox(p)[3])), ('Value', p['val'], Y + 2.54 - bbox(p)[2]),
             ('Footprint', p['fp'], None), ('Datasheet', '', None)]
    if side: props[0] = ('Reference', p['ref'], Y - 1.27); props[1] = ('Value', p['val'], Y + 1.27)
    s = [f'(symbol (lib_id {q(key)}) (at {X:.2f} {Y:.2f} 0) (unit 1) (in_bom {"no" if power else "yes"}) (on_board {"no" if power else "yes"}) (dnp no) (uuid {uid("s", p["ref"])})']
    for name, val, py in props:
        if py is None:
            s.append(f'  (property {q(name)} {q(val)} (at {X:.2f} {Y:.2f} 0) (effects (font (size 1.27 1.27)) hide))')
        else:
            px = X + 1.27 if side else X
            j = ' (justify left)' if side else ''
            s.append(f'  (property {q(name)} {q(val)} (at {px:.2f} {py:.2f} 0) (effects (font (size 1.27 1.27)){j}{hide if name=="Reference" else ""}))')
    for x in p['pins']:
        s.append(f'  (pin {q(x["num"])} (uuid {uid("p", p["ref"], x["num"])}))')
    s.append(f'  (instances (project {q(PROJECT)} (path {q("/" + ROOT)} (reference {q(p["ref"])}) (unit 1))))')
    s.append(')')
    body.append('\n'.join(s))
    if power: continue
    done = set()
    for x in p['pins']:
        net = p['nets'].get(x['num'], p['nets'].get(x['name']))
        px, py = X + x['x'], Y - x['y']
        k = (round(px, 2), round(py, 2))
        if k in done: continue
        done.add(k)
        if net is None:
            body.append(f'(no_connect (at {px:.2f} {py:.2f}) (uuid {uid("nc", p["ref"], x["num"])}))')
        else:
            body.append(label_for(net, px, py, x['a']))
for p in P:
    if p['lib'] == 'power':
        X, Y = p['at']
        body.append(label_for(p['nets']['1'], X, Y, 90))

doc = [f'(kicad_sch (version 20230121) (generator eeschema)', f'  (uuid {ROOT})', '  (paper "A1")',
       '  (title_block (title "Lean back-button board") (rev "0.1") (comment 1 "Generated by gen_sch.py: edit the script, not this file"))',
       '  (lib_symbols']
for k, v in syms.items(): doc.append(dump(v, 2))
doc.append('  )')
doc += out + body
doc.append(f'  (sheet_instances (path "/" (page "1")))')
doc.append(')')
open(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), 'back-button-board.kicad_sch'), 'w', encoding='utf-8').write('\n'.join(doc) + '\n')
print(len(P), 'parts')
