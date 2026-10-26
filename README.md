<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./media/logo_light.svg">
    <source media="(prefers-color-scheme: light)" srcset="./media/logo_dark.svg">
    <!-- The fallback image for older browsers -->
    <img alt="gyrophone" src="./media/logo_light.svg" width="200">
  </picture>
  <br>
</h1>

# GyroPhone

GyroPhone turns an Android phone into a Bluetooth or Wi-Fi gamepad, keyboard and mouse for a PC, in the spirit of the [Alpakka](https://github.com/inputlabs/alpakka_firmware) controller. Gyro moves the mouse. A clip-on controller such as the GameSir X5 supplies the physical buttons, and you can place virtual buttons anywhere on the touchscreen.

The reference device is the **OnePlus 15** (Android 16, no root). Windows is the first host; other hosts come later.

## How it works

```
GameSir X5 / any pad / keyboard ─┐
Touchscreen controls ────────────┼─► Mapping engine (profile) ─► Report pump ─► Bluetooth HID ─► Windows
Gyro (sensor direct channel) ────┘                                                  gamepad + keyboard + mouse
```

* **Bluetooth HID device.** The phone registers itself through `BluetoothHidDevice` as one composite device with a gamepad, a 6-key keyboard and a 16-bit mouse. Windows pairs with it like any Bluetooth controller, and nothing needs to be installed on the PC.
* **Gyro.** The gyro is read through `SensorDirectChannel` (shared memory written by the sensor HAL, with no Binder or Looper hop) at the highest rate the phone offers. If the phone doesn't support that, it falls back to `SENSOR_DELAY_FASTEST` on a high-priority thread. Gyro-to-mouse is raw by default: every sample goes straight to the cursor, with sub-count remainders kept so slow aiming isn't lost. Optional Alpakka/JoyShockMapper-style jitter cutoff (tightening) and tiered smoothing can be turned up per workspace in the pad Menu. Drift is handled by the phone's own gyro bias correction plus an optional rest calibration (Calibrate in the pad Menu: lay the phone still for 2 seconds), which subtracts a fixed offset and does no filtering. Each profile sets how the phone is held (auto from screen rotation, landscape either way, portrait or flat) and which motion (yaw, pitch or roll) drives mouse X and Y, with per-axis invert.
* **Gyro modes and ratchet.** Each workspace's gyro is *Always on*, *Always off* or *Hold / toggle button*. Any touch or controller button mapped to *Ratchet* flips it while held: always-on gyro pauses (re-centre the phone), always-off gyro engages. With *Touchpad is a ratchet* in the pad Menu, a finger on the touchpad does the same. The *G* corner, next to the → workspace corner, switches the current workspace between always on and always off (it is lit while gyro is on), and ratchets flip with it. Built-in workspaces set gyro axes for portrait and landscape alike.
* **Report rate.** Reports go out at a fixed rate (30 to 90 Hz, 60 Hz by default), one report per tick, and mouse motion is merged between reports. Android gives no signal when Bluetooth has actually sent a report, so a rate above what the link can carry builds a queue, which feels like growing lag. On the OnePlus 15 the phone's Bluetooth stack holds the HID link in sniff mode (it can't be turned off without root), which carries roughly one packet per sniff interval (about 11 ms by Android's HID default). That is why higher rates lag more. The Connection page (Connection button on the pad) shows reports sent per second.
* **Mapping.** Any input on the phone side (a controller button or axis, a keyboard key, a touch button or touch stick) can map to any output: a gamepad button, axis or trigger, a keyboard key, a mouse button, the wheel, or gyro hold/toggle. One input can drive several outputs, and several inputs can drive one output.
* **Touchpad.** The pad screen's empty background works like a laptop touchpad: one finger moves the cursor, taps click, two fingers scroll smoothly on both axes (high-resolution wheel, 1/120 of a notch) and keep gliding after a flick, two fingers pinch to zoom (Ctrl + wheel), and three- or four-finger swipes and taps send Windows shortcuts (Task View, show desktop, Alt+Tab scrubbing, switch desktops, notification centre). Every gesture can be changed per profile. Over Bluetooth, smooth scrolling needs the PC to have paired with the current descriptor: after updating from a build without it, remove GyroPhone in Windows Bluetooth settings and pair again once. Until then scrolling falls back to whole notches. Phones with three-finger screenshot gestures (OnePlus and others) take three-finger touches away from apps: turn those gestures off in the phone's settings to use three- and four-finger gestures.
* **Fullscreen pad.** In *Edit layout*, *+ Fullscreen* adds two small corner markers to the profile. Pressing both at the same time hides the tabs and the Edit button so the pad fills the screen; pressing both again, or Back, brings them back. Move or resize the markers like any control, or remove them from their settings. The *Opacity* slider in Edit layout sets how see-through all of the profile's controls are; the fullscreen corners have their own opacity, and by default resizing one resizes both (see a corner's settings).
* **Profiles.** Profiles are JSON files. Three are built in: *Virtual gamepad* (a prebuilt touch layout), *Controller passthrough* (X5 → PC gamepad, R3 toggles gyro) and *Keyboard + mouse (WASD)*. When a known controller is attached (GameSir, Xbox, PlayStation, Nintendo, 8BitDo), the matching profile is selected automatically. Any other standard pad still works through Android's normalised button layout.

### XInput vs DirectInput on Windows

Over plain Bluetooth HID, Windows sees GyroPhone as a **DirectInput** (generic HID) gamepad. Windows only loads its XInput driver for devices that report a Microsoft vendor/product ID in the Bluetooth Device ID record, and Android does not let apps set that record. There are two ways to get XInput:

1. **Steam Input** (works today): Steam recognises the generic gamepad and presents it to games as an Xbox controller.
2. **Windows companion over Wi-Fi**: the tray app below creates a virtual Xbox 360 (XInput) or DualShock 4 controller. Per-game profile switching from the same app comes later.

## Windows companion (Wi-Fi)

The companion is a small tray app, `GyroPhoneCompanion.exe`, for Windows 10 and 11 (x64). The phone sends its full state over UDP on your local network (port 47800), as soon as anything changes and up to 1000 times a second. The PC turns it into a virtual game pad plus real keyboard and mouse input. On 5 GHz Wi-Fi this is usually a few milliseconds from phone to game, well under the Bluetooth link's sniff-mode limit.

```
Phone (mapping engine) ─► UDP, AES-GCM sealed ─► GyroPhoneCompanion ─► ViGEmBus pad (Xbox 360 or DualShock 4)
                                                                    └─► SendInput keyboard and mouse
```

**On the PC**

1. Run `build\build.cmd`, then `build\update.cmd` (see *Building*). `build\update.cmd` installs the newest `GyroPhoneCompanion.exe` in `%LOCALAPPDATA%\Programs\GyroPhone`. It is unsigned, so SmartScreen may warn the first time: choose *More info*, then *Run anyway*.
2. For the game pad, install the ViGEmBus driver from [github.com/nefarius/ViGEmBus/releases](https://github.com/nefarius/ViGEmBus/releases). Without it the companion still sends keyboard and mouse, the tray icon turns amber, and the menu offers *Install gamepad driver* and *Retry driver*. ViGEmBus is no longer developed upstream but still installs and works on Windows 10 and 11.
3. Start the companion. The first time, Windows asks whether to allow it through the firewall: allow it on **Private** networks. If you missed that prompt, use *Allow through firewall…* in the tray menu. The app never opens the firewall for Public networks, so your home network must be set to Private (Settings › Network & internet › your network). The tray warns you when it is Public.
4. On the first run nothing is paired yet, so the pairing window opens by itself. Later use *Pair new phone…* in the tray menu. It stays open for two minutes.

**On the phone**

1. In the **Connect** tab choose **Wi-Fi** and tap **Scan QR code**, then point the camera at the QR code in the PC's pairing window. That is all: the PC adds the phone's key, the phone trusts the PC's key, and it connects by itself from then on. The QR code works once and is replaced every five minutes. To pair with a code instead, follow the next two steps.
2. PCs running the companion appear under *Found on this network*. If none shows up after a few seconds, check that both are on the same network and that the router has no client or AP isolation, or type the PC's address (shown in the pairing window) under *Enter PC address*.
3. Tap the PC. Both screens show the same six-digit code. Check it matches, tap **Confirm** on the phone and **Allow** on the PC. After that the phone reconnects to this PC by itself.
4. Keep GyroPhone on screen while playing. Android only grants the low-latency Wi-Fi mode to the app in front with the screen on, and the Connection page shows whether it is active along with the rate, loss and latency the PC reports.

**Tray menu.** The status line shows the phone, packets per second, loss and 99th percentile latency. *Output* switches between Xbox 360, DualShock 4 (seen by games as a DirectInput pad) and Off. *Pause input* releases everything and ignores the phone until you untick it. There are also *Paired phones* (to forget one), *Status…*, *Start with Windows*, *Restart as administrator* and *Open log folder*. The icon is grey while waiting, green when connected, amber when the game pad driver is missing or the pad is off, and red on an error such as port 47800 already being in use.

**Things to know**

* **Games running as administrator.** Windows blocks injected keyboard and mouse input to elevated windows (UIPI). When such a window is in front the tray says so; choose *Restart as administrator* to fix it. The virtual game pad is not affected.
* **Anti-cheat.** Some anti-cheat systems ignore or flag injected keyboard and mouse input. Use the game pad output in those games.
* **Pointer acceleration.** Gyro mouse motion arrives as relative mouse movement, so Windows' *Enhance pointer precision* changes how the desktop cursor moves. Games that read raw input are not affected. Turn the option off for consistent aiming on the desktop.
* **Updates.** `build\update.cmd` installs each new companion build at one fixed path, `%LOCALAPPDATA%\Programs\GyroPhone\GyroPhoneCompanion.exe`, and points the Start menu entry there. A running companion notices the new file and restarts into it as soon as no phone is connected. Because the path never changes, the firewall rule and *Start with Windows* keep working, and pairing and settings live in `%APPDATA%\GyroPhone`, so they are kept too. Builds made without `build\update.cmd` never restart themselves.
* **Safety.** If the phone goes quiet for 250 ms, every held key and button is released and the pad returns to neutral. The same happens when the PC locks or sleeps. If the companion is killed while a key is held, it releases it the next time it starts.
* **Pairing and keys.** Pairing uses an ECDH key exchange checked by the six-digit code, and the PC must explicitly allow each phone. QR code pairing instead swaps the two static P-256 keys used by key pairing: the QR code carries the PC's public key and a one-time token, and the phone sends its public key back authenticated with that token, so only a phone that saw the screen gets in. The resulting key is stored with Windows DPAPI for your user account in `%APPDATA%\GyroPhone\settings.json`. Every packet after pairing is authenticated and encrypted.
* **Diagnostics.** `GyroPhoneHeadless.exe` (built from `companion/src/GyroPhone.Headless`) runs the same engine in a console. `--latency-csv latency.csv` logs one-way latency per packet, `--pair` opens pairing and `--no-vigem` runs without the game pad.

## Building

Builds run through the scripts in `.agents/` (see `AGENTS.md`). On the PC, check the commit out in the `build\src` worktree and run `bash build/src/.agents/agent-build pc`: it runs the quick tests, builds `gyrophone-0.1.<commit count>.apk` and `GyroPhoneCompanion.exe`, puts the newest ones in `build\` (ignored by git), keeps the last 4 builds in `build\builds\0.1.<commit count>-<commit>\`, and installs the APK on a connected phone. Then `.agents/hooks/install` installs the companion in `%LOCALAPPDATA%\Programs\GyroPhone` (a running companion switches to it once no phone is connected) and, with the phone on adb, swaps the phone and PC key files so they connect over Wi-Fi without a code. The build itself is `.agents/hooks/build`. You need JDK 17, the Android SDK (API 36) and the .NET 8 SDK, found through `JAVA_HOME`, `ANDROID_HOME` and the `PATH`.

When the PC is offline, the `fallback.yml` workflow, started by hand on a branch, runs the quick tests and saves the APK and the companion on the run. A push to `main` runs the full checks only.

The tests:

```
./gradlew :core:test
```

The companion needs the .NET 8 SDK. The protocol and engine tests also run on Linux and macOS, while the tray app builds only on Windows:

```
dotnet test companion/GyroPhone.Companion.Linux.slnf
dotnet publish companion/src/GyroPhone.Tray -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

## Using it

1. Open GyroPhone and allow the Bluetooth permissions.
2. Tap **Make discoverable**. On Windows, open Settings › Bluetooth & devices › Add device and pick the phone.
3. Open the **Pad** tab to play. **Edit layout** turns on the pad editor:
   * Drag a control to move it.
   * Tap a control to open its settings panel. There you set its label, its shape (circle or square) and its size, and you map it to any gamepad button, stick, trigger, keyboard key, mouse button, wheel or gyro action. Sticks also have presets: left stick, right stick, WASD, arrow keys or D-pad.
   * Resize the selected control with the handle on its corner, or pinch with two fingers.
   * **+ Button** and **+ Stick** add new controls, and **Snap** aligns controls to a grid. **Done** saves the layout and its mappings, and **Cancel** throws the changes away.
4. Open the **Controller** tab to remap an attached controller. The app recognises it (GameSir X5, Xbox, PlayStation, Nintendo, 8BitDo, Razer Kishi, Backbone and others) and draws it. An unknown pad gets a generic layout with only the buttons it reports. Every button starts on its standard action. Tap a button to map it to any other gamepad, keyboard or mouse output, and **Reset to default** or **Reset all to standard** undoes your changes. Pressed buttons and stick movement light up live. With no controller attached, you can preview each layout.
5. Edits in the Pad and Controller tabs go to the active profile. A built-in profile is saved as a custom copy. **Profiles** still has **Add from controller** and **Add from touch** for mapping a source directly.

## Project layout

| Module | What it contains |
|---|---|
| `core/` | Plain Kotlin with no Android dependencies, unit-tested on the JVM: HID descriptor and report encoding, mapping engine, gyro-to-mouse, direct-channel parser, profile model and built-in profiles. |
| `app/` | Android app: `BluetoothHidDevice` manager, report pump thread, gyro sources, controller input, foreground service, and the Compose UI. |
| `companion/` | Windows companion in C# (.NET 8): wire protocol and crypto, the host engine (pairing, sessions, injection), ViGEm and SendInput sinks, the tray app and a headless console host. The wire format is pinned by golden vectors in `core/src/test/resources/protocol/v1/`, which both the Kotlin and C# tests check. |

## Roadmap

**M1: Skeleton (this PR)**
- [x] Composite HID descriptor (DInput gamepad, keyboard, mouse) with tests
- [x] Bluetooth HID registration, pairing and reconnect to the last host
- [x] Gyro through the direct channel with a listener fallback, gyro → mouse
- [x] Mapping engine: any input → any output, with gyro hold and toggle
- [x] Virtual touch gamepad, a prebuilt layout and a drag-to-place editor
- [x] Profiles with JSON storage and auto-selection when a controller is attached
- [x] Pad editor: tap a control to open its settings and mappings, circle or square shapes, resize by handle or pinch
- [x] Controller tab: the attached controller drawn with every remappable button, standard actions by default

**M2: Tuning on the OnePlus 15**
- [ ] Measure the real gyro rate and the end-to-end latency (input → Windows)
- [x] Selectable report rate with live send stats, plus gyro orientation and yaw/pitch/roll → mouse axis routing
- [ ] Tune Bluetooth QoS, and check that sniff mode is off while active
- [ ] Gyro calibration (bias on rest), player-space yaw (gravity-aware) and ratchet
- [ ] Stick deadzones and curves, and haptic feedback on touch buttons
- [ ] Keep sending input while the screen is off or another app is in front

**M3: Windows companion**
- [x] Tray app that creates a virtual Xbox 360 controller (XInput) from GyroPhone input, or a DualShock 4
- [x] Wi-Fi transport (UDP on the LAN) with discovery, code-checked pairing and encrypted sessions
- [x] Keyboard and mouse injection on the PC, with release on signal loss, lock and crash
- [ ] Per-game auto-switching from the foreground `.exe` (the `games` field in profiles)
- [ ] Optional USB transport, and configuration from the PC

**M4: More hosts and polish**
- [ ] Linux, macOS, Android TV and Steam Deck hosts
- [ ] DualShock-style descriptor with gyro passthrough for games with native gyro
- [ ] Profile import/export and sharing, plus layouts for more known controllers
