# GyroPhone

GyroPhone turns an Android phone into a Bluetooth gamepad, keyboard and mouse for a PC, in the spirit of the [Alpakka](https://github.com/inputlabs/alpakka_firmware) controller. Gyro moves the mouse. A clip-on controller such as the GameSir X5 supplies the physical buttons, and you can place virtual buttons anywhere on the touchscreen.

The reference device is the **OnePlus 15** (Android 16, no root). Windows is the first host; other hosts come later.

## How it works

```
GameSir X5 / any pad / keyboard ─┐
Touchscreen controls ────────────┼─► Mapping engine (profile) ─► Report pump ─► Bluetooth HID ─► Windows
Gyro (sensor direct channel) ────┘                                                  gamepad + keyboard + mouse
```

* **Bluetooth HID device.** The phone registers itself through `BluetoothHidDevice` as one composite device with a gamepad, a 6-key keyboard and a 16-bit mouse. Windows pairs with it like any Bluetooth controller, and nothing needs to be installed on the PC.
* **Gyro.** The gyro is read through `SensorDirectChannel` (shared memory written by the sensor HAL, with no Binder or Looper hop) at the highest rate the phone offers. If the phone doesn't support that, it falls back to `SENSOR_DELAY_FASTEST` on a high-priority thread. Gyro-to-mouse uses Alpakka/JoyShockMapper-style tightening and tiered smoothing, and keeps sub-count remainders so slow aiming isn't lost. Each profile sets how the phone is held (auto from screen rotation, landscape either way, portrait or flat) and which motion (yaw, pitch or roll) drives mouse X and Y, with per-axis invert.
* **Report rate.** Reports go out at a fixed rate (30 to 90 Hz, 60 Hz by default), one report per tick, and mouse motion is merged between reports. Android gives no signal when Bluetooth has actually sent a report, so a rate above what the link can carry builds a queue, which feels like growing lag. On the OnePlus 15 the phone's Bluetooth stack holds the HID link in sniff mode (it can't be turned off without root), which carries roughly one packet per sniff interval (about 11 ms by Android's HID default). That is why higher rates lag more. The Connect tab shows reports sent per second.
* **Mapping.** Any input on the phone side (a controller button or axis, a keyboard key, a touch button or touch stick) can map to any output: a gamepad button, axis or trigger, a keyboard key, a mouse button, the wheel, or gyro hold/toggle. One input can drive several outputs, and several inputs can drive one output.
* **Profiles.** Profiles are JSON files. Three are built in: *Virtual gamepad* (a prebuilt touch layout), *Controller passthrough* (X5 → PC gamepad, R3 toggles gyro) and *Keyboard + mouse (WASD)*. When a known controller is attached (GameSir, Xbox, PlayStation, Nintendo, 8BitDo), the matching profile is selected automatically. Any other standard pad still works through Android's normalised button layout.

### XInput vs DirectInput on Windows

Over plain Bluetooth HID, Windows sees GyroPhone as a **DirectInput** (generic HID) gamepad. Windows only loads its XInput driver for devices that report a Microsoft vendor/product ID in the Bluetooth Device ID record, and Android does not let apps set that record. There are two ways to get XInput:

1. **Steam Input** (works today): Steam recognises the generic gamepad and presents it to games as an Xbox controller.
2. **Windows companion** (planned): a small tray app that reads GyroPhone over HID, USB or Wi-Fi and creates a virtual Xbox 360 controller. The same app also switches profiles per game.

## Building

The CI on every PR builds the APK and uploads it as an artifact named `gyrophone-debug-apk`.

Locally you need JDK 17 and the Android SDK (API 36):

```
./gradlew :core:test :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Using it

1. Open GyroPhone and allow the Bluetooth permissions.
2. Tap **Make discoverable**. On Windows, open Settings › Bluetooth & devices › Add device and pick the phone.
3. Open the **Pad** tab to play. **Edit layout** lets you drag controls, resize them and add buttons, then save a custom profile.
4. In **Profiles**, tap **Add from controller**, press a button on the X5 and pick the output. **Add from touch** maps a touch control the same way.

## Project layout

| Module | What it contains |
|---|---|
| `core/` | Plain Kotlin with no Android dependencies, unit-tested on the JVM: HID descriptor and report encoding, mapping engine, gyro-to-mouse, direct-channel parser, profile model and built-in profiles. |
| `app/` | Android app: `BluetoothHidDevice` manager, report pump thread, gyro sources, controller input, foreground service, and the Compose UI. |

## Roadmap

**M1: Skeleton (this PR)**
- [x] Composite HID descriptor (DInput gamepad, keyboard, mouse) with tests
- [x] Bluetooth HID registration, pairing and reconnect to the last host
- [x] Gyro through the direct channel with a listener fallback, gyro → mouse
- [x] Mapping engine: any input → any output, with gyro hold and toggle
- [x] Virtual touch gamepad, a prebuilt layout and a drag-to-place editor
- [x] Profiles with JSON storage and auto-selection when a controller is attached

**M2: Tuning on the OnePlus 15**
- [ ] Measure the real gyro rate and the end-to-end latency (input → Windows)
- [x] Selectable report rate with live send stats, plus gyro orientation and yaw/pitch/roll → mouse axis routing
- [ ] Tune Bluetooth QoS, and check that sniff mode is off while active
- [ ] Gyro calibration (bias on rest), player-space yaw (gravity-aware) and ratchet
- [ ] Stick deadzones and curves, and haptic feedback on touch buttons
- [ ] Keep sending input while the screen is off or another app is in front

**M3: Windows companion**
- [ ] Tray app that creates a virtual Xbox 360 controller (XInput) from GyroPhone input
- [ ] Per-game auto-switching from the foreground `.exe` (the `games` field in profiles)
- [ ] Optional USB or Wi-Fi transport for lower latency, and configuration from the PC

**M4: More hosts and polish**
- [ ] Linux, macOS, Android TV and Steam Deck hosts
- [ ] DualShock-style descriptor with gyro passthrough for games with native gyro
- [ ] Profile import/export and sharing, plus layouts for more known controllers
