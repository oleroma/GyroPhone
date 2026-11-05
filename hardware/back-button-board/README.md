# Lean back-button board

A small board behind the phone with 8 back buttons (4 left, 4 right), an RP2040, a USB hub and a
USB-C role controller. The design notes are in the "Lean back-button board: design v1" doc in the
GyroPhone project.

- **Unplugged:** the phone powers the board, and the RP2040 connects straight to the phone. The app
  reads it as a plain USB device.
- **Wired:** the PC powers the board and charges the phone. The hub puts the phone (port 1) and the
  RP2040 (port 2) on the PC, and the companion reads the buttons.

Made with KiCad 7. Newer KiCad versions open it fine, but once it's saved in a newer version, KiCad 7
can't open it again.

## Files

| File | What it is |
| --- | --- |
| `gen_sch.py` | The netlist, written as code. It generates `back-button-board.kicad_sch` |
| `check.py` | Checks that every pin in the schematic sits on the net `gen_sch.py` intends |
| `gen_pcb.py` | Builds the board file from the schematic, or moves the buttons and sockets on it |
| `board-settings.json` | Board size, button positions and socket edges |
| `sexp.py` | Small helper for reading and writing KiCad files |

The schematic labels every pin with its net name and draws no wires. That keeps it generated: edit
`gen_sch.py`, never the `.kicad_sch` file.

## Changing the button positions

Edit `board-settings.json`:

- `first_from_top_corner_mm`: where the first button on each side sits, measured from the top
  left corner (the right side mirrors it).
- `step_mm`: how far each next button moves toward the centre (`x`) and down (`y`).
- `rotation_deg`: the rotation of all buttons, so their pads can point wherever routing is easiest.

Then run `gen_pcb.py` with KiCad's Python (the one that can `import pcbnew`). Without `--new`, it
moves only the buttons and sockets and keeps everything else, so after a move only the button traces
need routing again. `--new` rebuilds the board from scratch and throws away any routing.

```
python3 gen_sch.py && python3 check.py     # after changing the netlist
python3 gen_pcb.py                          # after changing board-settings.json
python3 gen_pcb.py --new                    # start the board over
```

On Windows, run the scripts from the KiCad command prompt so `kicad-cli` and `pcbnew` are found.

## Status

- Schematic: complete first draft. KiCad 7's command line has no ERC, so it's checked with
  `check.py` and still needs a run of ERC in the KiCad window.
- Board: 4 layers, 150 × 70 mm placeholder outline. The buttons and sockets come from the
  settings. Everything else is piled in the middle for hand placement. Nothing is routed yet.

## Parts that differ from the design doc

These are equivalents that have symbols and footprints in KiCad's own libraries:

- **Hub:** CH334R (QSOP-16) instead of CH334P, from the same family with the same pins for our use.
  It's powered only from the PC socket, so it draws nothing when unplugged.
- **USB switches:** TS3USB30E instead of TS3USB221. Both are 1:2 high-speed USB switches.
- **Power switches:** AP22814A (3 A, active-high enable). Both switches block reverse current when
  off: PC socket to rail, and rail to phone socket.
- **Hub reset:** through a BAT54 diode from GPIO13, as the CH334 datasheet recommends. This keeps
  its charging-port pin from being forced on at power-up.

## RP2040 pins

| GPIO | Net |
| --- | --- |
| 0–3 | BTN_L1–L4 |
| 4–7 | BTN_R1–R4 |
| 13 | HUB_RST_N (through D2) |
| 14 | MUX_SEL: low = phone straight to the RP2040, high = both through the hub |
| 16 | FUSB_INT_N |
| 18 | PHONE_PWR_EN |
| 19 | PHONE_PWR_FLG_N |
| 20, 21 | I2C SDA, SCL to the FUSB302 |
| 22 | PC_VBUS_DET (PC power, halved) |
| 26, 27 | PC_CC1, PC_CC2 (ADC: the current the PC offers) |
