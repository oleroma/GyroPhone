# GyroPhone input driver (optional)

A small Windows driver that gives the GyroPhone companion its own **USB mouse and keyboard** (with
media keys). The companion sends keys, clicks, moves and the wheel to it instead of calling
SendInput. Without it the companion works exactly as before, through SendInput.

It is separate from the pad driver (`driver/`, the DualSense): install either, both or neither.

## Why

- **Lower, steadier latency.** SendInput waits while Windows runs every program's input checks
  (games and anti-cheat add theirs). About 1 send in 600 waited 3 to 8 ms with The Finals open. The
  driver hands each report to Windows' own HID stack and returns at once, so the companion never
  waits on those checks. Windows still runs them on its own input thread, as it does for a real
  mouse, so a game may see part of that delay; Diagnostics measures what's left (see below).
- **Real device input.** Moves and keys arrive like a real mouse's and keyboard's: no "injected"
  mark per event. The device is still virtual and self-signed, so a game can tell it's not a
  physical mouse.
- **Elevated windows too.** SendInput can't reach a window running as administrator (UIPI);
  a device can, like your own mouse.

## Install

1. Run `install.cmd` from the unpacked package (`build\GyroPhoneInput-driver` after a PC build, or
   `GyroPhoneInput-driver.zip` from a release, unpacked anywhere). Windows asks for administrator
   rights once.
2. Restart the GyroPhone companion and turn on Performance › Keyboard and mouse › **Use input
   driver** (off by default: SendInput delivers mouse moves more steadily, see below). That card
   says "Through the input driver" when it's in use.

Run `install.cmd` again to update the driver; exit the companion first, so install.cmd can restart the device and the new driver runs at once (otherwise it runs after the next Windows restart).

## What install.cmd does

The same as the pad driver's: it uses this PC's own signing certificate ("GyroPhone pad driver
(this PC only)", made by whichever GyroPhone driver is installed first; private key not
exportable), trusts it on this PC only, signs a copy of the package in
`%ProgramData%\GyroPhone\input-driver` and installs it on a "GyroPhone virtual mouse and keyboard"
device (`Root\GyroPhoneInput`, System devices in Device Manager). Test signing stays off: the
driver runs in user mode (UMDF 2) on Windows' Virtual HID Framework.

The mouse and keyboard themselves (HID-compliant mouse, keyboard and consumer control in Device
Manager, `VID_1209&PID_6770`) exist only while the companion runs: they belong to its handle and go
away when it closes or crashes, and Windows lets go of every key they held.

## Uninstall

Run `uninstall.cmd`. It removes the device, the driver from Windows' driver store, the signed copy
and the certificate, unless the pad driver still uses it.

## Behaviour that differs from SendInput

- Held keys **auto-repeat** like a real keyboard's (Windows' typematic), in text fields too.
- The wheel is high resolution (1/120 notch) once Windows sets the wheel's resolution multiplier,
  which it does when the mouse appears; until then whole notches.
- Input reaches elevated windows (see above). Nothing is sent while the PC is locked: the companion
  pauses input on the lock screen as before.

## Risks

- **Anti-cheat** might dislike a self-signed driver. The worst realistic case is a game refusing
  to run while the device is there; turn **Use input driver** off, or uninstall.
- **Your PC trusts the certificate** (see the pad driver's README).
- **Bugs** in the driver can't crash Windows: it runs in user mode. The worst case is the mouse and
  keyboard disappearing; the companion then goes back to SendInput.

## How it works

- `src/driver.c`: the UMDF 2 driver. One SUBMIT per hand-off carries every report of that hand-off,
  each passed straight to `VhfReadReportSubmit` (no queue or thread of ours).
- `src/gpinput_reports.h`: the HID report descriptor (mouse, keyboard, media keys) and the report
  sizes, shared with `companion/src/GyroPhone.Protocol/Mapping/HidInputReports.cs`.
- `src/gpinput_ioctl.h`: the control codes, shared with
  `companion/src/GyroPhone.Windows/HidInputSink.cs`.
- Built by `.agents/hooks/build` with the same Windows Driver Kit from NuGet as the pad driver.
