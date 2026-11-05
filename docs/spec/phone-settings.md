# Phone: settings

Where every setting lives and its default. How workspace settings inherit is in [Workspaces](phone-workspaces.md).

## How settings pages work

Every settings page (`ui/SettingsLayout.kt`) is a list of **categories** that fold open and closed; a long press on a category's name drags it to a new place. Open state and order are remembered. A **search** field on top filters every setting by its words (every word must match); on the Settings page it searches both tabs at once, each under its name. Values a level sets itself are highlighted with **↺** to follow the parent again.

## Settings (the pad's Settings capsule)

`ui/AppSettings.kt`, two tabs.

### App tab (app-wide, not inherited)

| Category | Setting | Default |
|---|---|---|
| Pad | Start in fullscreen | off |
| | Keep the screen on while the pad is open | on |
| | Black screen when idle; Black screen after 30 s / 1 / 2 / 5 min | off; 1 min |
| | Screen rotation: Auto, Landscape, Portrait, Forced portrait, Forced landscape (top left / top right) | Auto |
| | Send input only while the pad is open | on |
| Corners | Hide the ← / → corners after a few seconds | off |
| | Corner opacity | 50 % |
| | Wrap around | off |
| Touch display | Show touches, Trail behind fingers (length, fade), Fade out after lift, Show live mappings on buttons, swipe line, Move rulers | see [Pad UI](phone-pad-ui.md) |
| Gyro | Jitter cutoff (0–5 °/s), Smoothing below (0–10 °/s) | both off |
| Gyro display | Parallax edge (Turn / Tilt) | off |
| Controllers on phone | The attached game pads | — |
| Debugging | Debug tools | off |
| About | Version, gyro source and measured rate | — |

### Defaults tab

The Defaults' workspace settings (the same categories as a workspace's, below), each highlighted with ↺ where it differs from the built-in value, plus **Mappings**: the touchpad and motion mappings every workspace starts from (none until mapped; Presets has Windows touchpad and Gyro mouse).

## Workspace settings

Opened by ⚙ in edit mode, the touchpad's Settings in its mapping editor, or the Settings menu (`ui/PadMenu.kt` `WorkspaceSettingsPage`, `ui/WorkspaceSettingsList.kt`). Chips on top pick **Base** or a layer: a layer's page shows only what a layer may set.

| Category | Contents | Defaults |
|---|---|---|
| Workspace | Rename, Duplicate, Delete, Copy mappings from… | — |
| Gyro | Gyro to mouse (Ratchet enables / Ratchet disables / Gyro off), Gyro space (Local / World / Player), Game (preset game + in-game sensitivity, or counts per 360, Measure), Sensitivity (per axis via ⚙, exact or × Parent), Acceleration curve (Off, Light, Strong, custom; by this axis or the whole phone), Inertia (Smooth / Linear) and Glide, DualShock 4 yaw / pitch / roll (the phone axis each pad axis follows, or None, and Invert; workspace and Defaults only) | Ratchet enables, Local, no game, 16 counts/°, Off, inertia off, 325 ms, pad yaw = Yaw, pitch = Pitch, roll = Roll |
| Tilt & shake | Tilt range, Tilt deadzone (per axis), Shake strength | 30°, off, 14 m/s² |
| Gyro calibration (Base only) | Calibrate, Clear | not calibrated |
| Controls (Base only) | Control opacity (10–100 %) | 100 % |
| Haptics | Feel (system or custom strength / length), Ticks play (On press / enter, On release / exit, Both) | system, On press |
| Gesture timings | Long press after (100–1500 ms), Double tap within (100–800 ms) | 400 ms, 300 ms |
| Touchpad | The workspace touch surface: speed, acceleration, inertia, swipe lengths and pies, per direction and per Move | see [Mapping system](phone-mapping.md) |

## Connection page

`ui/ConnectScreen.kt`. The top switch picks **Bluetooth** (default) or **Wi-Fi**.

| Category | Contents | Default |
|---|---|---|
| PC connection | Bluetooth: registration, Make discoverable, paired PCs. Wi-Fi: Scan QR code, found and paired PCs, Enter PC address | — |
| Key pairing (Wi-Fi) | This phone's key, share `phone.pub`, import `host.pub`, trusted PCs, Connect automatically | on |
| Link quality (Wi-Fi) | Rates, loss, latency, jitter, radio, USB | — |
| Report rate | Bluetooth 30/45/60/75/90 Hz; Wi-Fi 250/500/1000 Hz | 60 Hz; 1000 Hz |
| Gyro poll rate | 1 / 2 / 4 kHz | 4 kHz |
| PC controller for gyro ratchet (Wi-Fi) | Use PC controller for gyro ratchet ([Controllers](phone-controllers.md)) | off |
| Stop when idle | Off / 5 / 10 / 30 min | 10 min |

## Where it is stored

App-wide settings, the gyro bias, capsule order and UI state are Android shared preferences in `GamepadRuntime`. Workspace and Defaults settings are in the workspace set file. Details in [Workspaces](phone-workspaces.md#storage).

## Key files

`ui/AppSettings.kt`, `ui/SettingsLayout.kt`, `ui/CollapsibleSection.kt`, `ui/OverrideSettings.kt`, `ui/WorkspaceSettingsList.kt`, `ui/PadMenu.kt`, `ui/ConnectScreen.kt`, `ui/GameSensSettings.kt`, `ui/AccelCurveSetting.kt`, `ui/HapticFeelEditor.kt`, `ui/DirectionSettings.kt`, `runtime/GamepadRuntime.kt`.
