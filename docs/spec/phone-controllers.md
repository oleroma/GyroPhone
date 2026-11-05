# Phone: controllers and remapping

Physical devices attached to the phone (a clip-on or Bluetooth game pad, a keyboard, a mouse, and the phone's own volume rocker), what they do, and how a controller plugged into the PC can ratchet the gyro.

## Reading devices

`input/ControllerInput.kt` feeds key and joystick events into the engine. Android delivers them only to the foreground activity, so `MainActivity` forwards them. Each event carries its device; the attached pads are listed in Settings › App › Controllers on phone. Input reaches the engine only while input is allowed (the pad is open in play, unless **Send input only while the pad is open** is off).

A pad's axes are read by `PhysicalSticks`: sticks `ls` / `rs`, triggers `lt` / `rt`. Pads that put the right stick on RX / RY (Generic.kl) are detected; Z / RZ are then the triggers. The face layout (Xbox, PlayStation, Nintendo) and the drawing come from `core/.../model/KnownLayouts.kt` and `ControllerDiagrams.kt`; unknown pads get the Xbox drawing.

## Device presets

`core/.../model/DevicePresets.kt`. A **device preset** says what each physical device does:

- **controller**: any game pad's buttons, sticks (with their directions, Outer ring and click) and triggers (Press and Full pull);
- **keyboard**: keys;
- **mouse**: buttons, wheel and movement;
- **phone**: the volume rocker ([Volume rocker](phone-volume-black-screen.md));
- **timings**: a key's own long-press and double-tap times (`KeyTiming`; none = the workspace's);
- **sticks** / **triggers**: tuning (below);
- **held**: buttons with their own settings while held (`HeldButton`).

Mappings use the same model as touch ([Mapping system](phone-mapping.md)): every key and button has When (On touch, Tap, Long press, Double tap, Release) and How. On physical keys Tap means released before the long-press time. Presets live beside the workspaces; a workspace **pins one** (edit mode › Devices) and uses it while active; pinning none leaves the devices doing nothing. A fresh install's Controller workspace pins a preset that maps the pad to the PC's Xbox pad one to one.

**Device screens** (the pad's Controller capsule, `ui/DevicePresetsScreen.kt`): presets as tabs (tap to pick, long-press to rename, Reset all, Duplicate, Delete; **+** adds one), the device drawn in the middle (controller from the front and the back, keyboard, mouse, phone), devices to switch between along the bottom. Each control shows what it is mapped to; a tap opens its mapping editor, the same as for touch controls. A layer chip row edits a layer's mappings.

## Stick and trigger tuning

`StickTuning` per stick, in the preset: the stick is one round vector; nothing below **inner deadzone** 0.10, full from **outer** 0.95; between them shaped by the **curve** (Linear, Soft = squared, Aggressive = square root) and scaled by **sensitivity** 1.0. The **Outer** inputs fire past **outer ring** 0.85. Directions are pies around each axis, **135°** wide by default (30–180°; over 90° overlap so a diagonal presses both).

`TriggerTuning`: **Press** from 0.5 of the pull, **Full pull** from 0.95.

## Held buttons and the Motion tab

A physical button's mapping editor has a **Motion** tab: gyro axes mapped there (`InputSource.Gyro` with id `phys:<device>:<control>`) take over that axis while the button is held, like a touch button's own gyro. Its ⚙ sets its gyro sensitivity, acceleration and touchpad speed while held, per layer (`HeldButton`, `ButtonGyro`). The last pressed of the held buttons wins.

## PC controller for gyro ratchet

Connection page › **PC controller for gyro ratchet** (Wi-Fi only, default off): a controller plugged into the **PC** ratchets the phone's gyro with no round trip to the phone.

- When the PC's WELCOME says `PC_GYRO` and the switch is on, the phone stops routing the gyro itself and sends each STATE as **STATE_GYRO** (`core/.../net/PcGyroFeed.kt`): the last 8 raw samples, per-axis running totals (so a longer gap fills in one step), and the gyro config those samples are meant for (sensitivities, glide, routes, curves, tightening, smoothing).
- **PAD_GYRO** tells the PC which controller buttons ratchet (all axes or one) or toggle the gyro, with a toggle epoch. They come from the pinned device preset: every controller button mapped to Ratchet, Ratchet yaw/pitch/roll or Toggle gyro (`MappingEngine.pcGyroButtons`), resent when that changes. **PAD_GYRO_SET** follows it with the own gyro settings of each such button that has them while held.
- The companion reads the PC's controller through XInput (`XInputPcPad`, 1 kHz, skipping its own virtual pad's slot) only while the switch is on. The rest of that controller goes to the game as it is.
- The PC runs the same gyro math (`GyroMath.cs`) and ratchets at each sample's own time (`PadHeldHistory`), so the result matches the phone exactly ([Gyro math](phone-gyro-math.md), [Virtual devices](pc-virtual-devices.md)).
- PC controller presses are never sent back to the phone.

Not available from a PC controller: gyro inertia (no glide on its ratchet) and the phone's own ratchet displays.

## Key files

`input/ControllerInput.kt`, `ui/DevicePresetsScreen.kt`, `ui/ControlMappingScreen.kt`, `core/.../model/DevicePresets.kt`, `core/.../model/KnownLayouts.kt`, `core/.../model/ControllerDiagrams.kt`, `core/.../net/PcGyroFeed.kt`, `core/.../engine/MappingEngine.kt` (`pcGyroButtons`), `companion/src/GyroPhone.Protocol/PcGyro.cs`, `PcGyroTail.cs`, `PadHeldHistory.cs`, `GyroGate.cs`.
