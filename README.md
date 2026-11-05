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

GyroPhone turns an Android phone into a game pad, keyboard, mouse and gyro aim for a Windows PC. The phone's gyro aims like a mouse. The touchscreen is a laptop-style touchpad plus any buttons and sticks you place on it. A controller, keyboard or mouse attached to the phone can be remapped too. Everything is a mapping you can change: any input can press any output.

It connects two ways:

- **Bluetooth**: the phone is a Bluetooth game pad, keyboard and mouse. Nothing is installed on the PC. Windows sees a DirectInput pad, and input is limited to 30–90 reports a second.
- **Wi-Fi or USB, with the companion**: a small Windows tray app (`GyroPhoneCompanion.exe`) receives up to 1000 updates a second, encrypted. It gives games a virtual **Xbox 360** or **DualShock 4** pad and real keyboard and mouse input, and can draw the phone's motion as overlay windows over the game or for OBS. This is the low-latency way to play.

The reference phone is a OnePlus 15 (Android 16, no root). Android 15 or newer is needed.

The full description of every feature and how it works inside is in the **[spec](docs/spec/README.md)**.

## Setting up

### Phone

Install the APK (`gyrophone-0.1.<build>.apk`). Builds are published as pre-releases on this repo, so [Obtainium](https://github.com/ImranR98/Obtainium) can install and update it (add the repo with pre-releases on). Open GyroPhone and allow the nearby-devices (Bluetooth) and notification permissions. The app opens on the pad.

### Bluetooth (no PC install)

1. On the pad, tap **Connection**, choose **Bluetooth**, and tap **Make discoverable**.
2. On Windows: Settings › Bluetooth & devices › Add device, and pick **GyroPhone**.
3. The phone reconnects to that PC by itself from then on. Set **Report rate** on the same page (60 Hz by default; higher rates queue up and lag on phones that force Bluetooth sniff mode).

### Wi-Fi and USB (companion)

On the PC:

1. Get `GyroPhoneCompanion.exe` from the same pre-release (Windows 10/11 x64), put it in `%LOCALAPPDATA%\Programs\GyroPhone\`, and run it. It is unsigned, so SmartScreen may ask: *More info* › *Run anyway*.
2. For the game pad, install the **ViGEmBus** driver ([github.com/nefarius/ViGEmBus/releases](https://github.com/nefarius/ViGEmBus/releases)). Without it keyboard and mouse still work, and Home in the companion shows how to fix it.
3. Allow it through the firewall on **Private** networks. The companion never opens Public networks, so your home Wi-Fi must be set to Private (Home warns when it isn't). The Connection page has fix buttons for the firewall and USB rules.
4. On the first run the **Pair new phone** window opens by itself. Later, use *Pair new phone…* in the tray menu.

On the phone:

1. Tap **Connection**, choose **Wi-Fi**, tap **Scan QR code**, and point the camera at the QR code in the PC's pairing window. That's all: both sides trust each other's key, and the phone connects by itself whenever GyroPhone is open (**Connect automatically**, on by default).
2. Or pair with a code: tap the PC under *Found on this network* (or type its address under *Enter PC address*). Check the six-digit code matches on both screens, then tap **Confirm** on the phone and **Allow** on the PC.
3. Keep GyroPhone on screen while playing: Android only grants the low-latency Wi-Fi mode to the app in front. **Link quality** shows the rate, loss and latency.

**USB.** With USB tethering on, the link moves to the cable by itself. With the phone's USB mode set to *No data transfer*, the companion can also use the cable directly (Android asks to open GyroPhone for the accessory; say yes).

**Starting automatically.** In the companion's App page: **Start with Windows**, or **Start when my phone appears** (a tiny watcher starts the companion when the phone looks for this PC).

## The pad

The app is the pad. Along the top is the **capsule row**:

- **+** adds a workspace (Controller, WASD, Keyboard or a blank one, with the touchpad only);
- one capsule per **workspace**: tap to switch, **tap the current one to edit it**;
- **Controller**: what attached devices do;
- **Settings** and **Connection**.

Long-press a capsule and drag it to reorder. The **← / →** corners switch to the previous / next workspace. Press **both together for fullscreen**; press them again, or Back, to leave.

Empty space is the **touchpad**. With the Windows touchpad preset (in the three starting workspaces): one finger moves the cursor, tap clicks, tap-and-drag drags, two fingers scroll, two-finger tap right-clicks, pinch zooms, and three- and four-finger swipes and taps do Task View, show desktop, switch apps, switch desktops and the notification centre. If your phone takes three-finger touches for screenshots, turn that gesture off in the phone's settings.

### Workspaces

A **workspace** is one complete setup: its buttons, sticks and touchpad, their mappings, its gyro and touch settings, its layers, and which **device preset** it uses for attached devices. Workspaces share nothing, so you can duplicate one and change it freely. There are no game profiles and no automatic switching: switch with the capsules or corners.

Settings and mappings pass down: **Defaults** (Settings › Defaults) → **workspace** → **layer** → **button**. A level only stores what it changes. Values a level sets itself are highlighted, and **↺** makes one follow its parent again.

### Edit mode

Tap the current workspace's capsule. A floating menu appears (drag it by its ⠿ dots, resize or fold it):

- **Tap** a control to select it (taps on overlapping controls cycle through them). **Drag** to move it, **pinch** to resize it. **Long-press** a control to open its mapping editor; long-press empty space for the touchpad's.
- **+ Button**, **+ Empty button** (one that inherits nothing), **+ Stick**, and the **gyro displays** (3D phone, Ratchet ring, Speed graph, Attitude indicator, Bubble disk, 3D shape). You can also drag items out of the menu onto the pad.
- **Configure** opens the selected control's settings; **Map** opens its mapping editor.
- **Select** picks several controls, **Group** / **Ungroup** keep controls together, **Snap** aligns edges to a grid, and 🔒 **Lock layout** stops moving and resizing.
- **Devices** picks the workspace's device preset. **Library** adds a button you saved earlier ("Save to library" in a button's settings).
- **↶ / ↷** undo and redo (100 steps). **⚙** opens the workspace's settings. **Done** saves; **Cancel** asks before throwing changes away.

Landscape and portrait have separate placements, so edit in the orientation you play in.

## The mapping editor

Every button, stick, the touchpad, the Defaults, and every key of an attached device have the same full-screen mapping editor.

**Layout.** The top half lists the control's **actions** as cards: one per input (for a button: its press, then its touch gestures). Each card holds the outputs it presses as **chips**. The bottom half is the **library** of outputs in tabs: Recent, Presets, Keyboard, Mouse, Gamepad, Gyro, Phone, Touchpad, Layers, Other. A search box finds any output ("W", "LT", "wheel"), and **⌨ Press a key** captures a key from an attached keyboard. Drag the divider to give either half more room.

**Mapping something:**

1. Tap a card to select it, then tap an output in the library to add it there.
2. Or long-press an output and drag it onto a card.
3. Drag a chip to another card to move it, or back onto the library to remove it.
4. Reorder cards by their ⠿ handle.

**Finger chips** (on buttons and the touchpad) pick which finger count you are mapping: **1–4 fingers**, each with its own taps, holds, swipes, Move X / Move Y and pinch. **Motion** maps the gyro, tilt and shake (on a button: what they do while it is held).

**Chip settings.** Tap a chip to open its settings:

- **Where** (touch controls): **Touch** (the press), **Inside** (any finger inside the control, wherever it touched down), **Outside** (a finger that touched down on it and slid off).
- **When**: **On touch** (held from touch-down to lift), **Tap**, **Long press**, **Double tap**, **Release**. On swipe directions these read On swipe, Quick swipe, Long hold, Double swipe. Each When is a separate input, so one button can do different things on tap, long press and double tap. A tap only waits for a possible double tap when the double tap does something else.
- **How**: **Held** (for as long as it lasts), **Once** (one short press), **Toggle** (on, then off next time). A Toggle can **also end on** a tap, Let go, a timer, the next input (a sticky Shift), or the same keystroke.
- **Advanced**: start delay, end delay, hold at least, and **Cycle** (the outputs take turns, one per press).
- Analog outputs (mouse motion, sticks) have a **scale**; −1 inverts.

**Inherited mappings** show faded. **✕** clears one (the input then does nothing here), **↺** follows the parent again, **↺ Reset all** follows everything, and **✕ Clear all** clears everything. **↶ Undo** undoes any change.

**Layers.** Chips at the top pick **Base** or a layer (**+ Layer**, up to 9). A mapping in a layer only works while that layer is in use. Map an input to a **Layer** output (Layers tab) to **switch** to it, **override** Base with it (inputs the layer doesn't map keep their Base mapping), or use it only **while held**. **Layer back** returns to the previous layer. A layer can also have its own settings, for example a lower gyro sensitivity.

**Presets** fill in common setups: **Windows touchpad** for a touch surface, **Gyro mouse** for the gyro (yaw → Move X, pitch → Move Y), and Left stick, Right stick, WASD, Arrow keys and D-pad for sticks.

**⚙ Settings** opens the control's own settings: label, shape and size, layer behaviour, its own touch surface settings, a stick's deadzone and zones, and its own gyro sensitivity while held (for precision aim). For a button it also has **Duplicate**, **Save to library** and **Delete**.

## Gyro aiming

The gyro is mapped like everything else: in the Motion tab, map **yaw / pitch / roll** to mouse Move X / Y, scroll, or a stick axis (or use the Gyro mouse preset). The three starting workspaces already have it.

**Ratchets.** Map a button to **Ratchet** (or Ratchet yaw / pitch / roll) in the Gyro tab. In the workspace's Gyro settings, **Gyro to mouse** sets what it does:

- **Ratchet enables** (default): the gyro does nothing until a ratchet is held.
- **Ratchet disables**: the gyro is always on, and holding a ratchet pauses it so you can re-centre the phone.
- **Gyro off**: the sensor isn't read at all.

**Toggle gyro** swaps the first two until you switch workspace.

**Tuning** (workspace settings › Gyro): **Sensitivity** in mouse counts per degree (16 by default; per axis, exact or "× Parent"); **Game**: pick the game and enter your in-game sensitivity, and the slider shows real-world sensitivity (degrees the camera turns per degree the phone turns; **Measure** helps if your game isn't listed); **Acceleration** curves; **Inertia** (keep gliding after a ratchet); **Gyro space** Local, World or Player. **Calibrate** removes drift: lay the phone still for 2 seconds. Jitter cutoff and smoothing are in Settings › App › Gyro, off by default (raw gyro).

**Gyro poll rate** (Connection page, 1/2/4 kHz, default 4 kHz) is how often the app reads the sensor.

## Controllers and other devices

Attach a game pad, keyboard or mouse to the phone (USB or Bluetooth). The **Controller** capsule shows **device presets** as tabs (**+** adds one, long-press to rename, duplicate or delete). Each preset draws the device: the controller from the front and the back, the keyboard, the mouse, and the phone's volume rocker. Tap any control to open its mapping editor. Every key has the same When / How choices as touch buttons, and a **Motion** tab for what the gyro does while it is held. Stick deadzones, curve and direction pies, and trigger press and full-pull points are in each stick's and trigger's settings. A workspace uses one preset; pick it in edit mode with **Devices**.

**Volume rocker.** While the pad is open, the phone's volume keys turn the **PC's** volume unless you map them to something else (Controller › Phone).

**A controller plugged into the PC** can ratchet the phone's gyro with no extra delay: turn on **Use PC controller for gyro ratchet** (Connection page, Wi-Fi). The ratchet buttons come from the workspace's device preset, and the rest of the controller goes to the game as usual.

## Settings

- **Settings › App**: fullscreen on start, keep the screen on (on by default), **black screen when idle** (the pad goes black after a while without a touch, to save battery while you use a controller; tap to wake, and that tap presses nothing), screen rotation, **send input only while the pad is open** (on by default), corners, touch display (show touches, trails, live mappings), gyro filters, debug tools, and the gyro rate.
- **Settings › Defaults**: the settings and touchpad/motion mappings every workspace uses unless it sets its own.
- **Workspace settings** (⚙ in edit mode): rename, duplicate, delete, copy mappings from another workspace; gyro, tilt and shake, calibration, control opacity, haptics, gesture timings (long press 400 ms and double tap 300 ms by default), and the touchpad. Chips at the top switch between Base and each layer's own settings.
- **Connection**: the link, pairing, report rate, gyro poll rate, PC controller ratchet, and **Stop when idle** (the connection ends after the app has been off screen for 10 minutes by default).

Every settings page has a search box. Categories fold, and you can reorder them by long-pressing their names.

## The companion

**Tray menu**: status, **Open GyroPhone**, **Pause input**, **Gamepad output** (Xbox 360 / DualShock 4 / Off), **Overlays**, **Pair new phone…**, **Exit**.

**Main window**: **Home** (problems and their fixes, phone, link, latency and gamepad cards), **Phones** (paired phones, Forget), **Gamepad**, **Overlays**, **Connection** (network, firewall, USB checks), **App** (startup, tray click, logs, Debug window, version).

**Overlays.** Add any number of overlay windows: **Phone** (the phone in 3D with your layout, touches and presses, optionally with controller handles), **3D shape**, **Attitude indicator**, **Bubble disk**. Each has its own size, look, quality, frame rate and turning options (gravity, compass, ease back to straight, Level). Turn on **Edit** to drag and resize them; otherwise the mouse goes through them. For streaming, set the background to **Green** and use an OBS chroma key, with OBS Window Capture. Shortcuts: **Ctrl+Alt+R** Level all, **Ctrl+Alt+O** hide all, **Ctrl+Alt+E** Edit (changeable).

**Updates.** A new build placed next to the running exe as `GyroPhoneCompanion.exe.new` is picked up within seconds: the companion shuts down cleanly, replaces itself and starts again, and the phone reconnects. Settings and pairing in `%APPDATA%\GyroPhone` are kept.

### Things to know

- **Games running as administrator** don't accept injected keyboard and mouse input from a normal app. Home says so: use **Restart as administrator**. The virtual pad isn't affected.
- **Anti-cheat** may ignore injected keyboard and mouse input. Use the game pad output in those games.
- **Safety.** If the phone goes quiet for 250 ms, or the PC locks or sleeps, everything held is released. If the companion is killed while a key is held, it releases it on its next start.
- **Enhance pointer precision** in Windows changes how gyro moves the desktop cursor. Games that read raw input aren't affected.
- **Bluetooth smooth scrolling** needs the PC to have paired with a current build. After updating from an old one, remove GyroPhone in Windows Bluetooth settings and pair again once.

## Debugging

- **Phone**: Settings › App › Debugging › **Debug tools** shows a panel on the pad with the link, gyro, workspace and layer, what is held, and the newest events. **Record** saves the last 30 s. With the switch off nothing is logged.
- **PC**: App page › **Debug window…** shows the link as the PC sees it, what the game is given, and the PC's events. **Save last 30 s** writes a recording. When the phone records over Wi-Fi or USB, the PC saves its side at the same moment.
- **adb**: `adb shell dumpsys activity service com.oleroma.gyrophone/.service.GamepadService [on|off|record|events N]`. `tools/debug-collect.sh` merges a phone and PC recording into one time-sorted file in `build/debug/`.

## Building

Builds run through the scripts in `.agents/` (see `AGENTS.md` and [Build and release](docs/spec/build-release.md)). On the PC, `bash build/src/.agents/agent-build pc` runs the quick tests, builds only the parts that changed (`gyrophone-0.1.<n>.apk` and/or `GyroPhoneCompanion.exe`), runs the full checks, installs the APK over adb, installs the companion and swaps the key files. You need JDK 17, the Android SDK (API 36) and the .NET 8 SDK on the `PATH` / `JAVA_HOME` / `ANDROID_HOME`. When the PC is offline, the `fallback.yml` workflow does the same on GitHub.

Tests:

```
./gradlew :core:test
dotnet test companion/GyroPhone.Companion.Linux.slnf
```

The companion's protocol and engine tests run on Linux and macOS too. The tray app builds only on Windows:

```
dotnet publish companion/src/GyroPhone.Tray -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

## Project layout

| Folder | What it contains |
|---|---|
| `core/` | Plain Kotlin, unit-tested on the JVM: the model (workspaces, mappings, inheritance, device presets), the mapping engine, touch surfaces, gyro math, the HID descriptor, and the wire protocol and crypto. |
| `app/` | The Android app: Bluetooth HID, the Wi-Fi/USB link, gyro and other sensors, controller input, the foreground service, and the Compose UI. |
| `companion/` | The Windows companion in C# (.NET 8): protocol, host engine, ViGEm and SendInput, overlays, tray and main window. Shared test vectors in `core/src/test/resources/protocol/v1/` keep the Kotlin and C# sides in step. |
| `docs/spec/` | The [full spec](docs/spec/README.md). |

What isn't built yet is listed at the end of the [spec overview](docs/spec/README.md).
