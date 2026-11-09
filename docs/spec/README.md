# GyroPhone spec

This is the full specification of GyroPhone as `main` builds it today: the Android app (the "phone") and the Windows companion (the "PC"). It says what each part does for the person using it and how it works inside, with the key files to read.

This spec replaces every older description: the old README's feature list and roadmap (the root README is now a short user guide that links here), the design docs and artifacts made in earlier threads, and the notes in `designs/` of the project files. Those are left as they were, but where they disagree with this spec, this spec (and the code) is right. When the code changes, change the page here that describes it in the same branch.

Only behaviour that `main` has is described. Ideas that are not built are in [Not built yet](#not-built-yet) at the end.

## What GyroPhone is

GyroPhone turns an Android phone into a game pad, keyboard and mouse for a Windows PC. The phone's gyro moves the mouse (or a stick). The touchscreen holds virtual buttons, sticks and a laptop-style touchpad. A controller clipped to the phone (or any game pad, keyboard or mouse attached to it) and the phone's volume rocker are mapped too. Everything goes through one mapping engine on the phone, which sends one combined output to the PC.

Two ways to reach the PC:

| Link | PC side | What the PC sees | Latency |
|---|---|---|---|
| Bluetooth HID | nothing to install | one composite HID device: DirectInput game pad, keyboard, mouse | limited by the phone's sniff mode, one report per tick at 30 to 90 Hz |
| Wi-Fi (or USB) to the companion | `GyroPhoneCompanion.exe` | virtual Xbox 360 or DualShock 4 pad (ViGEmBus), injected keyboard and mouse (SendInput) | a few ms; state sent on every change, up to 1000 Hz |

The reference phone is a OnePlus 15 (Android 16, no root). The reference PC is Olex's Windows laptop.

```
 Clip-on controller, keyboard, mouse ─┐
 Volume rocker ───────────────────────┤
 Touch buttons, sticks, touchpad ─────┼─► MappingEngine (active workspace, layers) ─► snapshot ─┬─► ReportPump ─► Bluetooth HID ─► Windows
 Gyro (direct channel), tilt, shake ──┘                                                       └─► WifiPump ─► UDP/USB ─► Companion ─► ViGEm pad + SendInput
```

## Repository layout

| Folder | What it is |
|---|---|
| `core/` | Plain Kotlin, no Android: the data model (profile, workspaces, mappings), the mapping engine, gyro math, HID descriptor and report scheduling, the wire protocol and crypto. Unit tested on the JVM. |
| `app/` | The Android app: sensors, controller input, Bluetooth HID, the Wi-Fi/USB link, storage, the foreground service and the Compose UI. |
| `companion/` | The Windows companion in C# (.NET 8): `GyroPhone.Protocol` (wire format, PC gyro math), `GyroPhone.Host` (engine, pairing, sessions, settings), `GyroPhone.Windows` (ViGEm, SendInput, USB, firewall), `GyroPhone.Ui` (WPF windows), `GyroPhone.Latency` (the latency test's video analyser), `GyroPhone.Tray` (the tray app, overlays, updater, watcher), `GyroPhone.Headless` (console host). |
| `core/src/test/resources/protocol/v1/` | Golden vectors both sides test against: `wire-v1.json`, `key-v1.json`, `qr-v1.json`, `gyro-math-v1.json`. |
| `.agents/` | The build, install and merge scripts and the repo's hooks. |
| `.github/workflows/` | `fallback.yml` (hand-started cloud build) and `cache.yml` (compiles `main` after each push). |

## The pages

On the phone:

1. [Connection](phone-connection.md): Bluetooth HID, the Wi-Fi/USB link, pairing on the phone, report rates, idle stop.
2. [Gyro sensors and poll rate](phone-gyro-sensors.md): the direct channel, the listener fallback, poll rate, calibration, accelerometer and magnetometer.
3. [Gyro math](phone-gyro-math.md): axes and spaces, sensitivity and real world sensitivity, ratchets and modes, acceleration, inertia, filters.
4. [Mapping system](phone-mapping.md): inputs, outputs, Where / When / How, layers, touch surfaces, how the engine evaluates and merges.
5. [Workspaces and inheritance](phone-workspaces.md): the one profile, workspaces, the Defaults, settings and mapping inheritance, storage and migrations.
6. [Pad UI and edit mode](phone-pad-ui.md): the pad screen, capsules, corners, edit mode, the mapping editor, touch visuals, gyro displays.
7. [Settings](phone-settings.md): the Settings page (App and Defaults), the workspace settings page, the Connection page, search and categories.
8. [Controllers and remapping](phone-controllers.md): attached devices, device presets, the Controller screen, stick and trigger tuning, held-button gyro, PC controller ratchet.
9. [Volume rocker and black screen](phone-volume-black-screen.md).

On the PC:

10. [Companion and tray](pc-companion.md): the tray app, main window, engine, safety, settings files, the watcher.
11. [Virtual devices](pc-virtual-devices.md): the ViGEm pad, SendInput keyboard and mouse, PC gyro mode.
12. [Overlays](pc-overlays.md): the phone, 3D shape, attitude and bubble overlay windows.
13. [Install and update](pc-install-update.md): where it lives, self-update, firewall, key swap.
14. [Latency test](pc-latency-test.md): Measure buttons, and timing the gyro from a slow-motion video.

Shared:

15. [Protocol and pairing](protocol.md): wire v1 byte by byte, code pairing, key pairing, QR pairing, sessions, USB framing.
16. [Build and release](build-release.md): versions, hooks, PC and cloud builds, publishing.

## Words used here

- **Workspace**: one complete setup (layout, touchpad, gyro, pinned device preset, layers). The app holds a list of them in one profile.
- **Defaults**: the workspace settings and touchpad/motion mappings every workspace uses unless it sets its own.
- **Layer** (mapping set): a numbered set of mappings above Base (set 0) in one workspace, switched to or laid over by layer actions. Up to 9 sets.
- **Mapping**: one input (source) pressing one output (target) in one set, with optional options (When / How / timings).
- **Touch surface**: anything that reads fingers as touchpad inputs: the workspace touchpad (the pad's empty background) and every touch button.
- **Ratchet**: an input that, while held, pauses the gyro (or engages it, depending on the mode), like lifting a mouse.
- **Device preset**: what physical devices (controller, keyboard, mouse, phone keys) do; a workspace pins one.
- **RWS**: real world sensitivity, degrees the game camera turns per degree the phone turns.
- **Session**: one encrypted Wi-Fi/USB connection between the phone and the companion.

## Not built yet

These came up in design talks and are not in the code. They are listed so nobody mistakes them for features.

- Split touch zones and radial menus (Steam Input style).
- A layers "All" view in the editor.
- Per-game automatic switching. `Profile.games` and the FOREGROUND packet exist in the model and the wire format, but nothing fills or sends them; game profiles were removed in favour of workspaces.
- Rumble on the phone. The phone announces the RUMBLE capability and the PC sends RUMBLE, but the phone ignores it.
- Linux, macOS and Android TV hosts, and an iPhone app.
- A DualShock-style Bluetooth descriptor with gyro passthrough, and a native Windows Precision Touchpad over Bluetooth.
- Bluetooth LE HID as a transport.
- Picking the overlay GPU by the overlay's screen on hybrid-graphics laptops.
- Caching overlay shapes per frame.
- Haptics settings per button (only per workspace and per haptic mapping today).
- Profile import, export and sharing.
