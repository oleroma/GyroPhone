# Phone: gyro math

What happens to a gyro sample between the sensor ([Gyro sensors](phone-gyro-sensors.md)) and the PC: which axis is which, where each axis goes, how fast, and when it stops. The same math runs on the PC in PC gyro mode ([Virtual devices](pc-virtual-devices.md)).

## Gyro is ordinary mappings

There is no hidden "gyro to mouse" switch. Each gyro axis is an input, `InputSource.Gyro(axis, id)`, mapped like any other ([Mapping system](phone-mapping.md)):

- `id = ""` is the workspace's own gyro, always in effect.
- `id = <touch button>` is that button's: while the button is held, its gyro mappings take that axis over from the workspace (the last pressed of several wins). Physical buttons do the same with `id = "phys:<held id>"` ([Controllers](phone-controllers.md)).

A gyro axis can go to **mouse move X/Y**, **scroll** or **scroll sideways** (`OutputTarget.MouseAxis`, with a scale, −1 inverts) or a **game pad axis** (`OutputTarget.PadAxis`: a stick axis or a trigger). The Defaults map nothing, so a fresh workspace's gyro does nothing until it is mapped. The presets in the mapping editor fill in common setups: **Gyro mouse** maps yaw to Move X and pitch to Move Y; **Steering wheel** maps sideways tilt (the phone turned like a wheel, [Gyro sensors](phone-gyro-sensors.md)) to the left stick's X. The three preset workspaces of a new install come with it filled in ([Workspaces](phone-workspaces.md)).

`MappingEngine.gyroRoutes()` works out where each axis goes right now: the workspace's gyro mappings of the layers in use, with each axis a held button maps taken over by that button.

## Axes

`core/.../engine/GyroMouse.kt` turns the Android sensor frame (x right, y up, z out of the screen in portrait) into yaw, pitch and roll for the way the screen is turned (`displayRotation`, 0/90/180/270). Shown names follow the screen (`MotionAxis` in `core/.../model/Profile.kt`): **yaw** turns around the axis through the screen, **pitch** around the screen's horizontal axis, **roll** around its vertical axis. The stored names are older and kept for compatibility: stored `YAW` is shown as roll and stored `ROLL` as yaw. Code and saved files use the stored names.

**Gyro space** (workspace gyro setting, `GyroSpace`):

- **Local** (default): the phone's own axes. Turning a tilted phone left and right leaks into roll.
- **World**: against gravity. Yaw turns around the vertical, pitch tilts around the horizontal axis to the right, roll turns around the horizontal way the phone faces. Uses `GravityPose` (the accelerometer runs).
- **Player**: yaw blends the phone's yaw and roll by how the phone is tilted (forgiving when leaning back or lying down); pitch and roll stay the phone's.

**Each gyro card has its own space** (`GyroSettings.yawSpace` / `pitchSpace` / `rollSpace`, a held button's `ButtonGyro` the same): the card's ⚙ has a **Space** row, Local / World / Player for yaw and Local / World for pitch and roll (Player changes only yaw). Unset, the card follows its parent, the workspace's Gyro space above (which follows the Defaults'); a level's own Gyro space hides a parent's per-card ones, as the shared Sensitivity hides per-axis ones. A layer can set a card's space; a held button's own lies over it while held. The card's title names it: **Local pitch**, **World yaw**, **Player yaw** (Player on pitch and roll reads Local, as it changes nothing there). The gyro mouse builds each axis from its own space (`GyroMouse.inSpaces`), so yaw can be Player while pitch stays Local. The gyro displays and the PC overlays keep the workspace's Gyro space. `GravityPose` follows gravity whenever any card of the workspace may need it (`Workspace.gyroSpaced`), so a layer or held button switching to World finds it ready.

An interactive page showing the three spaces with a phone and a game camera is [docs/visuals/gyro-spaces.html](../visuals/gyro-spaces.html) (open it in a browser).

**Yaw axis** (workspace gyro setting, `YawAxis` + `gripPitch`, workspace and Defaults only): the phone's base orientation, which screen axis is yaw, a turn of the phone's frame applied in `GyroMouse.motion`, so everything that reads the phone's axes follows it: the gyro mouse (in every gyro space), the PC controller's gyro and accelerometer, the gyro displays and their Level in Local space, and the PC overlays (the phone sends them the turned frame, so the companion needs no change).

- **Screen Z** (default): held flat, screen up. Yaw through the screen, pitch across it, roll along it.
- **Screen Y**: held upright, screen facing you (the frame tilted back 90°). Yaw about the screen's vertical axis, roll through the screen, pitch across it.
- **Screen X**: held on its side, right edge up. Yaw about the screen's horizontal axis, pitch through the screen, roll about its vertical axis.
- **Flipped** (a chip beside them): the same axis the other way up (screen down, upside down, left edge up), turned 180° more, so turns are not mirrored.
- **Custom N°** (only after a granular Set orientation): the frame tilted back by exactly N° about the screen's across axis (0° = Screen Z, 90° = Screen Y). Only the tilt back is kept; leaning sideways is left out.

**Set orientation**, the button under the chips, reads gravity and picks the screen axis, either way up, pointing most nearly up (`YawAxis.nearest`), saves it as the workspace's own, and starts every 3D gyro display on the pad again from it: level, facing ahead, its saved Level grip cleared. Every PC overlay shown levels too. The **Set orientation** mapping (Other targets) does the same in play; its chip's **Granular** switch (off by default) saves the exact tilt back instead (`YawAxis.CUSTOM`, `YawAxis.pitchOf`). Tilt and shake still read the screen's own axes; lying flat, Set orientation also makes sideways tilt's way straight ([Gyro sensors](phone-gyro-sensors.md)).

**World and Player space displays**: the gyro displays on the pad (and the parallax edge) ignore the base orientation and are fed about the screen's own axes, level from gravity with no saved grip, so they show the phone's real pose against gravity, as the gyro mouse measures turns there. Displays that turn with the Camera or as a Trackball take the gyro mouse's own turns in that space. The PC overlays get the same: in World and Player space the phone sends their turn, up and north about the screen's own axes with OVERLAY flag 128 (`FLAG_REAL_WORLD`); the PC then ignores their saved Level grip, and for overlays turning with the Camera or as a Trackball the phone puts the live gyro's turn, measured in that space, into the camera degrees.

## Sensitivity

Gyro **sensitivity** is mouse counts per degree the phone turns. Default 16. Every level can set it ([Workspaces](phone-workspaces.md)): one shared value plus each axis's own.

- An axis's own value is either **exact** or, with the **Scale parent** switch on, a **factor** of the parent's (a slider from 0 to 1, step 0.05; 0 turns it off). A factor multiplies the nearest exact value above it and replaces any factor above (factors never stack).
- A level that sets its own shared sensitivity hides the axis values above it.
- A held button's own sensitivities (per layer) apply while it is the last pressed of the held buttons that have their own, whatever it maps (precision aim). The chain for one axis: the button's axis value → the button's shared value → the workspace's axis value (layer, then Base) → the workspace's shared value → the Defaults.

**Gyro sensitivity chips** (`OutputTarget.GyroSens`, [Mapping system](phone-mapping.md)) sit over the whole chain while their input is active: the engine works out the one in effect on every input change (`MappingEngine.refreshSens`) and lays it over the gyro settings in effect (`gyroSettings`), so the phone and the PC in PC gyro mode both use it from the next sample, with no smoothing. An aim button with Position Y → Gyro sensitivity 4–6 RWS tightens or loosens the aim as the finger slides up or down it.

**Real world sensitivity.** When the workspace knows the game's full turn (`gameCountsPer360`: picked from a preset such as Counter-Strike 2 or Apex Legends with the in-game sensitivity, typed, or measured), sensitivity sliders show and take **RWS**: degrees the game camera turns per degree the phone turns, `RWS = sensitivity × 360 / counts per full turn` (`core/.../model/GameSens.kt`). It is exact only with raw input and no in-game mouse acceleration. **Measure** streams steady mouse counts (`core/.../engine/CountStream.kt`) while the gyro is paused, so the person can count one full turn in the game and nudge the camera to line it up.

**Scales of the other targets.** Scrolling: 15° of turn per notch at the default sensitivity, scaled by sensitivity / 16. Game pad axes: 90 °/s deflects an axis fully at the default sensitivity, scaled the same way, stick Y inverted so pitching up pushes up, triggers taking the absolute value; each axis is clamped to its range.

## Modes and ratchets

Each workspace's gyro (and each axis, if set) has one **mode** (`GyroMode`):

| Mode | Stored | What it does |
|---|---|---|
| Ratchet enables (default) | `OFF` | Gyro does nothing; a held ratchet engages it. |
| Ratchet disables | `ALWAYS` | Gyro is on; a held ratchet pauses it (re-centre the phone). |
| Gyro off | `SENSOR_OFF` | The sensor is not read at all (battery). Ratchets and toggle do nothing. |

(`HOLD_OR_TOGGLE` only comes from old files and reads as Ratchet enables.)

- **Ratchet** (`OutputTarget.GyroPause`) acts on every axis; **Ratchet yaw / pitch / roll** (`GyroPauseAxis`) on one, as that axis's mode says.
- Each ratchet active on an axis flips it, so two at once cancel out (`MappingEngine.ratchetedAxes`). Several triggers of one button each count (a press ratchets, a swipe on it ratchets back).
- **Toggle gyro** (`GyroToggle`): each press swaps Ratchet enables and Ratchet disables until the workspace (or its gyro settings) changes.
- `MappingEngine.gyroAxes` is the result, a bit per axis (`gyroBit`), read on every sample.

## The pipeline of one sample

`GyroMouse.route` takes the bias-corrected sample, turns it into yaw / pitch / roll in the gyro space, and calls `core/.../engine/GyroMath.kt` `step` with the resolved config (`GyroMath.Config`: per axis its sensitivity, glide and acceleration, and the routes). `step`, in this order:

1. **Inertia** (`coast`). An axis that is on tracks its recent speed (15 ms EMA). When a ratchet turns it off and its inertia is on and it was turning at least 5 °/s, it glides on from that speed: **Smooth** fades exponentially with the glide time as time constant; **Linear** slows evenly and stops after 2 × the glide time, so both travel the same distance. The glide time is clamped to 100..1000 ms (default 325); a glide stops below 0.5 °/s. A gliding axis moves only the mouse and the wheel, never a pad axis. Inertia, glide time and curve are per workspace with per-axis overrides; default off.
2. **Acceleration** (`accelerate`). Each axis that is on takes its curve's gain at the turning speed: its own speed ("This axis") or the whole phone's (all three axes together, the default, so a diagonal flick speeds up both ways alike). A curve (`GyroCurve`) is up to 6 points of (speed °/s, gain); straight lines between, flat outside; **smooth** makes it a monotone cubic with flat ends. No points = gain 1, also over a parent's curve. Presets Light and Strong. An axis that is off keeps its last gain. The curve multiplies the resulting sensitivity.
3. **Routing**: each route adds `value × gain × sensitivity × scale` to its target.
4. **Filters** on the cursor (`counts`), both app-wide and both off by default (raw gyro), thresholds in °/s at the default sensitivity: **jitter cutoff** (soft tightening) scales motion below the threshold down in proportion to its speed; **smoothing** averages motion below the threshold over an 8-sample window, fully smoothed below half the threshold, blended up to the threshold, direct above. Only the smoothed share enters the window, so fast motion is never replayed after the phone stops. Set in App › Gyro.
5. **Remainders**: sub-count fractions of the mouse and both wheels are carried to the next sample, so slow aiming is never lost.

The result (`GyroMath.Routed`: mouse dx/dy, wheel and horizontal wheel in 1/120 notch, six pad axes) goes into the engine (`addMouseMotion(fromGyro = true)`, `setGyroAxes`).

**Same on both sides.** `GyroMath.kt` is the reference; `companion/src/GyroPhone.Protocol/GyroMath.cs` is an exact port. Every step is Float math in the same order with the same rounding, and `core/src/test/resources/protocol/v1/gyro-math-v1.json` checks both. Change one side, change the other and the vectors.

## Tilt and shake

Not gyro, but set beside it ("Tilt & shake" in the workspace settings): `InputSource.Tilt` (X sideways, Y forward/back, −1..1, with a half for button use) and `InputSource.Shake` (a short press). Range, deadzone (per axis if set) and shake strength are workspace settings. See [Gyro sensors](phone-gyro-sensors.md).

## Key files

`core/.../engine/GyroMouse.kt`, `core/.../engine/GyroMath.kt`, `core/.../engine/GravityPose.kt`, `core/.../engine/MappingEngine.kt` (`gyroRoutes`, `gyroAxes`, `ratchetedAxes`, `gyroSettings`), `core/.../model/Profile.kt` (`GyroSettings`, `GyroMode`, `GyroSpace`, `MotionAxis`, `ButtonGyro`, `GyroCurve`, `GyroCurves`), `core/.../model/GameSens.kt`, `core/.../engine/CountStream.kt`, `app/.../ui/GameSensSettings.kt`, `app/.../ui/AccelCurveSetting.kt`, `app/.../ui/ScaledSens.kt`, `companion/src/GyroPhone.Protocol/GyroMath.cs`.
