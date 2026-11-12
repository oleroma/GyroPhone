# Phone: settings

Where every setting lives and its default. How workspace settings inherit is in [Workspaces](phone-workspaces.md).

## How settings pages work

**Sliders** (`ui/CompactSlider.kt`): dragging moves the handle from where it is (it does not jump to the finger). **A tap on a slider without dragging opens a box to type the exact value**, which may go past the slider's ends (a gyro sensitivity up to the highest the app takes, above the Slider cap). A slider with two handles (deadzones, ranges) types the handle nearest the tap; its handles may pass each other.

Every settings page (`ui/SettingsLayout.kt`) has a low **search** field on top (rounded, in the cards' colours) and right under it its **categories as tabs**, each named by what it controls (Gyro, Touchpad, Tilt & shake, Buttons, Taps, Haptics; a category with several parts has headed groups inside) (chips, scrolling sideways when they don't fit); both scroll away with the settings, so only the page's title row (and the fixed actions beside or under it) stays put. Tapping a tab shows that one category, always open. **Long press a tab and drag it sideways** to move it. The order and the last picked tab are remembered per kind of page. Where a page has tabs of its own (a workspace's Base and layers, in the accent colour after a "Layers" label so they don't read as category tabs), those sit between the search field and the category tabs. The search filters every setting by its words (every word must match): while it holds text the tabs hide and every category with a match shows as a card, open, under its name; a long press on a card's name drags it up or down there. Values a level sets itself are highlighted with **↺** to follow the parent again.

## App and Defaults (the pad's App and Defaults capsules)

`ui/AppSettings.kt`: two pages, each from its own capsule on the pad's top row: **App** (a gear) and **Defaults** (sliders).

### App page (app-wide, not inherited)

| Category | Setting | Default |
|---|---|---|
| Screen | Start in fullscreen | off |
| | Keep the screen on while the pad is open | on |
| | Black screen when idle; Black screen after 30 s / 1 / 2 / 5 min | off; 1 min |
| | Screen rotation: Auto, Landscape, Portrait, Forced portrait, Forced landscape (top left / top right) | Auto |
| | Send input only while the pad is open; under it Hardware devices too (controllers, keyboards, volume buttons) | on; off |
| Workspace switching | Wrap around (Next workspace on the last one goes to the first) | off |
| Touch display | Show touches, Trail behind fingers (length, fade), Fade out after lift, Show live mappings on buttons, swipe line, Move rulers | see [Pad UI](phone-pad-ui.md) |
| Gyro | Jitter cutoff (0–5 °/s), Smoothing below (0–10 °/s); **Slider cap**: the top of every gyro sensitivity slider and Gyro sensitivity range, in RWS for workspaces that know their game (1–50) and in counts per degree for the others (4–250) | both off; 10 RWS, 64 counts/° |
| Gyro display | Parallax edge (Turn / Tilt); **Fade when phone rests**: the gyro displays on the pad (the kinds with a Level) fade while the phone lies within Flat within (10°) of the tilt each was last leveled at, turning slower than Still below (0.5 °/s, live speed shown), with no finger, key or controller stick in use, for Quiet for (3 s); they fade to Fade to (30 %) over Fade over (0.5 s) and come back at once. Same rules as the PC's overlays ([PC overlays](pc-overlays.md)); never in edit mode (`RestDetector`, `TouchVisuals.restFade`, `GyroDisplay.levelUp`) | parallax off; fade on |
| Controllers | The attached game pads | — |
| Vibration | **Vibrate with game rumble**: the game's rumble on the PC's virtual controller (Xbox 360 or DualShock 4 output) vibrates the phone, as strong as the stronger motor, while a PC is connected; a haptic mapping's hold or tick plays over it. The two short buzzes and two beeps (notification volume, so silent mode mutes the beeps) when a Performance test record starts or ends on the PC play either way (`PhoneHaptics.rumble`, `buzz`) | on |
| Debugging | Debug tools | off |
| Backup | **Export everything**, **Import everything…** (asks first; see [Workspaces](phone-workspaces.md)) | — |
| About | Version, gyro source and measured rate | — |

### Defaults page

The Defaults' workspace settings (the same categories as a workspace's, below), each highlighted with ↺ where it differs from the built-in value, plus **Mappings**: the touchpad and motion mappings every workspace starts from (none until mapped; Presets has Windows touchpad and Gyro mouse).

**Children that set their own value.** Under each setting that some child sets itself, a small pink line lists them: how many, then each one's name and value, for example "4 own: Apex 2.6 · Apex › Sniper 0.8 · Apex › Aim ×0.5 · …". Children are every workspace, its layers ("Workspace › Layer") and its touch buttons ("Workspace › Button", or "Workspace › Layer › Button" for a button's gyro in a layer). A value that is a factor of the parent's shows as ×; one with no short form (an acceleration curve, a haptic feel) shows the name only. The first 3 show; **+N more ▾** (tap the line) lists the rest in place, **▴ less** folds them again. **Reset children** (asks first) makes them all follow this setting again. Settings no child sets have no line. The workspace settings show the same line for the workspace's layers and buttons, and a layer's for the buttons (a button's own value wins over every layer). The ⚙ of a gyro axis, tilt, shake, Move X / Y or swipe direction in the mapping editor shows it too. Physical controller buttons' held gyro (device presets) is not counted. (`core/model/OverridingChildren.kt`, `ui/OverrideSettings.kt` `ChildLine`)

## Workspace settings

Opened by ⚙ in edit mode, the touchpad's Settings in its mapping editor, or the Settings menu (`ui/PadMenu.kt` `WorkspaceSettingsPage`, `ui/WorkspaceSettingsList.kt`). **Rename, Duplicate, Delete** (not the last) and **Copy mappings from…** sit fixed at the top, outside the scrolling list: in the title row in landscape, under the title in portrait (`WorkspaceTopBar`). Chips on top of the list pick **Base** or a layer: a layer's page shows only what a layer may set.

| Category | Contents | Defaults |
|---|---|---|
| Gyro | Gyro to mouse (Ratchet enables / Ratchet disables / Gyro off), Gyro space (Local / World / Player), Yaw axis (Screen Z / Screen Y / Screen X, Flipped, Custom after a granular Set orientation, and a Set orientation button that picks the axis pointing up now and levels the displays from it; workspace and Defaults only), Game (preset game + in-game sensitivity, or counts per 360, Measure), Sensitivity (per axis via ⚙, exact or × Parent), Acceleration curve (Off, Light, Strong, custom; by this axis or the whole phone), Inertia (Smooth / Linear) and Glide, PC pad yaw / pitch / roll (the phone axis each DualShock 4 or DualSense axis follows, or None, and Invert; workspace and Defaults only) | Ratchet enables, Local, Screen Z, no game, 16 counts/°, Off, inertia off, 325 ms, pad yaw = Yaw, pitch = Pitch, roll = Roll |
| | Calibration (Base only): Calibrate, Clear | not calibrated |
| Touchpad | The workspace touch surface, in groups: Cursor & scrolling (speed, acceleration, inertia, deadzone), Move X & Y, Quick swipes, Held swipes, Length zones (lengths and pies, per direction and per Move) | see [Mapping system](phone-mapping.md) |
| Tilt & shake | Tilt range, Tilt deadzone (per axis), Shake strength | 30°, off, 14 m/s² |
| Buttons (Base only) | Control opacity (10–100 %) | 100 % |
| Haptics | Feel (system or custom strength / length), Ticks play (On press / enter, On release / exit, Both) | system, On press |
| Taps | Tap time (100–1000 ms): the longest tap, when a long press starts, and the double-tap gap | 300 ms |

## A touch control's or controller button's settings

The mapping editor's ⚙ Settings (`ui/ControlSettingsScreen.kt`, `ui/ActionSettings.kt`): **Look** (label, shape, layer, size), **Layers** (when the workspace has layers), **Gyro** and **Touchpad** (the gyro's sensitivity and acceleration and the touchpad cursor's speed while it is held), then **Swipes** for a button that swipes (the same groups as the workspace Touchpad, its own over the workspace's) or **Stick** for a stick. A controller button's ⚙ has the same Gyro and Touchpad tabs.

## Connection page

`ui/ConnectScreen.kt`. The top switch picks **Bluetooth** (default) or **Wi-Fi**.

| Category | Contents | Default |
|---|---|---|
| PC | Bluetooth: registration, Make discoverable, paired PCs. Wi-Fi: Scan QR code, found and paired PCs, Enter PC address | — |
| Key pairing (Wi-Fi) | This phone's key, share `phone.pub`, import `host.pub`, trusted PCs, Connect automatically | on |
| Link quality (Wi-Fi) | Rates, loss, latency, jitter, radio, USB | — |
| Report rate | Bluetooth 30/45/60/75/90 Hz; Wi-Fi 250/500/1000 Hz | 60 Hz; 1000 Hz |
| Gyro rate | 1 / 2 / 4 kHz | 4 kHz |
| PC controller (Wi-Fi) | Use PC controller for gyro ratchet ([Controllers](phone-controllers.md)) | off |
| Stop when idle | Off / 5 / 10 / 30 min | 10 min |

## Where it is stored

App-wide settings, the gyro bias, capsule order and UI state are Android shared preferences in `GamepadRuntime`. Workspace and Defaults settings are in the workspace set file. Details in [Workspaces](phone-workspaces.md#storage).

## Key files

`ui/AppSettings.kt`, `ui/SettingsLayout.kt`, `ui/CollapsibleSection.kt`, `ui/OverrideSettings.kt`, `ui/WorkspaceSettingsList.kt`, `ui/PadMenu.kt`, `ui/ConnectScreen.kt`, `ui/GameSensSettings.kt`, `ui/AccelCurveSetting.kt`, `ui/HapticFeelEditor.kt`, `ui/DirectionSettings.kt`, `runtime/GamepadRuntime.kt`.
