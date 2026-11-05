# GyroPhone pad driver (optional)

A small Windows driver that lets the GyroPhone companion show games a **PlayStation DualSense**.
ViGEmBus, which the companion uses for Xbox 360 and DualShock 4, can't do that. Nothing else
needs this driver: without it the companion works exactly as before.

**Trial:** sticks, triggers, every button and the D-pad. Gyro, touchpad, rumble, lightbar and
adaptive triggers come later.

## Install

1. Run `install.cmd` from the unpacked package (`build\GyroPhonePad-driver` after a PC build, or
   `GyroPhonePad-driver.zip` from a release, unpacked anywhere).
   Windows asks for administrator rights once.
2. Restart the GyroPhone companion. "DualSense" now shows up as a gamepad output (Home, Gamepad
   page and the tray menu's "Gamepad output").
3. Start the game *after* the DualSense is plugged in (the phone connected with that output).
   Many games only look for controllers when they start.

Run `install.cmd` again to update the driver.

## What install.cmd does

- Makes a signing certificate **for this PC only** ("GyroPhone pad driver (this PC only)"), kept in
  the computer's certificate store. Its private key can't be exported, and only administrators
  can use it. It's made once and then reused.
- Trusts that certificate on this PC (Trusted Root and Trusted Publishers), so Windows installs the
  driver without asking. No other PC trusts it, and no key is shared between PCs.
- Signs the driver with it (a copy in `%ProgramData%\GyroPhone\driver`) and installs it on a
  "GyroPhone virtual pad" device (System devices in Device Manager).

Test signing stays **off**. Windows requires Microsoft's signature only for kernel drivers. This one
runs in user mode (UMDF 2) on Windows' own Virtual HID Framework, so a signature from a certificate
the PC trusts is enough to install it.

## Uninstall

Run `uninstall.cmd`. It removes the device, the driver from Windows' driver store, the certificate
(from all three stores) and the signed copy. The PC is back to how it was before `install.cmd`.

## Risks

- **Anti-cheat** might dislike a self-signed driver or a DualSense that isn't from Sony. The worst
  realistic case is a game refusing to run while the pad is plugged. Try single-player games first.
- **Your PC trusts the certificate.** Anything signed with its key would be trusted too, so the key
  can't be exported and only administrators can use it. Uninstall removes it.
- **Windows may tighten its rules** for user-mode drivers one day. The driver would then stop
  loading, and the DualSense output just wouldn't plug (Xbox 360 and DualShock 4 keep working).
- **Steam Input** may take the DualSense over and remap it. Turn Steam Input off for the game if
  buttons come out doubled.
- **Bugs** in the driver can't crash Windows: it runs in user mode, so the worst case is the pad
  disappearing.

## How it works

- `src/driver.c`: the UMDF 2 driver. It exposes a device interface for the companion. A pad
  belongs to the handle that plugged it and is removed when that handle closes, so a crashed
  companion never leaves a stuck pad.
- `src/dualsense.h`: what a wired DualSense shows Windows: Sony's IDs (054C:0CE6), its HID report
  descriptor, and the answers to the feature reports games read at start (calibration, pairing
  info, firmware info).
- `src/gyropad_ioctl.h`: the control codes, shared with
  `companion/src/GyroPhone.Windows/DualSensePadSink.cs`.
- `GyroPhonePad.inx`: the INF. VHF sits below the driver as a lower filter in user mode
  (`VhfMode=1`), as in Microsoft's UMDF VHF sample.
- Built by `.agents/hooks/build` with the Windows Driver Kit from NuGet (`packages.config`) and Visual
  Studio Build Tools with C++. The build makes an unsigned catalog. The installing PC signs it.
