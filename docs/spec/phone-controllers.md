# Phone: controllers and remapping

Physical devices attached to the phone (a clip-on or Bluetooth game pad, a keyboard, a mouse, and the phone's own volume rocker), what they do, and how a controller plugged into the PC can ratchet the gyro.

## Reading devices

`input/ControllerInput.kt` feeds key and joystick events into the engine. Android delivers them only to the foreground activity, so `MainActivity` forwards them. Each event carries its device; the attached pads are listed in App › Controllers on phone. Input reaches the engine while hardware input is allowed: always, unless **Send input only while the pad is open** and its **Hardware devices too** are both on; then only while the pad is open in play (phone-connection.md).

**Read USB controllers directly** (Settings › Performance, on by default; `input/UsbPads.kt`, `core/.../input/HidGamepad.kt`): a game pad on the phone's USB port is taken from Android (the USB host API claims its HID interface, which detaches the kernel's driver) and its input reports are read by GP on a thread of its own (`gp-usb-pad`, urgent priority), straight into the engine. Android's input reader and dispatcher and the app's screen thread are not in the path, so nothing drawn can delay a press (input latency comes first), and the presses reach the PC with GP in the background too.

- Only devices Android has listed as a game pad or joystick are taken (their vendor:product ids are remembered, as a taken pad leaves Android's list); a boot keyboard or mouse interface stays Android's (the 8BitDo Ultimate Mobile's mouse too).
- The report descriptor is read from the pad and turned into the keys and axes Android gives the same pad (Linux hid-input then Generic.kl): buttons 1-15 of a game pad are A B C X Y Z L1 R1 L2 R2 Select Start Mode ThumbL ThumbR (of a joystick BUTTON_1..16), X Y Z Rx Ry Rz the sticks, accelerator / brake the right / left trigger, the hat HAT_X / HAT_Y, the D-pad usages the D-pad keys; sticks -1..1 and triggers 0..1 over the logical range, with Linux's flat (1/16 of the range) as deadzone. So mappings, device presets and drawings work the same both ways.
- A pad is taken only when that reading gives the same buttons, D-pad keys, sticks, hat and triggers as Android listed for it (remembered per vendor:product with the id, prefs `usb_pad_layouts`). Pads that Linux or Android remap (a driver of their own or a vendor key layout: Sony's DualShock 4 and DualSense, Nintendo's, ...) differ and stay Android's, so their buttons and sticks stay right. Such a pad is remembered (prefs `usb_pad_mismatched`, with what Android listed) and is not asked about or opened again while Android lists it the same way.
- Held while hardware input is allowed and GP runs: always, or with **Send input only while the pad is open** and **Hardware devices too** on, only while the pad is open. Let go otherwise (and when the switch is turned off); while held, other apps get nothing from the pad.
- Android asks once per plug-in whether GP may use it. Refused, unreadable or failing reads: the pad stays (or goes back to being) Android's, read as below, until it is plugged in again.
- "Press a key" and learning get its presses on the main thread; in play they never touch it. Ratchet timing and the PC's phone overlay get them as from any pad, after the engine.

A pad's axes are read by `PhysicalSticks`: sticks `ls` / `rs`, triggers `lt` / `rt`. Pads that put the right stick on RX / RY (Generic.kl) are detected; Z / RZ are then the triggers. The engine reads such a pad as a standard one (its RX / RY as the right stick Z / RZ, its Z / RZ as the triggers LTRIGGER / RTRIGGER), so the standard mapping, the drawing, learning an axis and Press a key all work on it. The face layout (Xbox, PlayStation, Nintendo) and the drawing come from `core/.../model/KnownLayouts.kt` and `ControllerDiagrams.kt`; unknown pads get the Xbox drawing.

## Device presets

`core/.../model/DevicePresets.kt`. A **device preset** says what each physical device does:

- **controller**: any game pad's buttons, sticks (with their directions, each with its outer zone after a separator, and click) and triggers (Press and Full pull);
- **keyboard**: keys (the standard preset sends each key, media and volume keys too, as the same key on the PC);
- **mouse**: buttons, wheel and movement. On the pad, while the workspace maps the mouse, a mouse attached to the phone is captured (no phone cursor; raw moves and buttons). A captured mouse still counts as attached (Android lists it as a relative mouse then), so the capture stays put; many game pads also list a mouse of their own (the 8BitDo Ultimate Mobile's second USB interface), which is captured the same way and sends nothing;
- **phone**: the volume rocker ([Volume rocker](phone-volume-black-screen.md));
- **timings**: a key's own long-press and double-tap times (`KeyTiming`; none = the workspace's);
- **sticks** / **triggers**: tuning (below);
- **held**: buttons with their own settings while held (`HeldButton`).

Mappings use the same model as touch ([Mapping system](phone-mapping.md)): every key and button has When (On touch, Tap, Long press, Double tap, Release) and How. On physical keys Tap means released before the long-press time. Presets live beside the workspaces; a workspace **pins one** and uses it while active: tap a preset's tab in the device screens (or **None**) and it is the current workspace's at once, with nothing to save (edit mode › Devices picks it too); pinning none leaves the devices doing nothing. A fresh install's Controller workspace pins a preset that maps the pad to the PC's Xbox pad one to one.

**Device screens** (the pad's Controller capsule, which shows the current workspace's preset name after its icons; `ui/DevicePresetsScreen.kt`): presets as tabs, **None** first (tap one to use it in the current workspace; the screen shows the one in use; long-press to rename, Reset all, Duplicate (the current workspace then uses the copy), Delete; **+** adds one), the device drawn in the middle (the controller as the companion's overlay draws it: the phone with a handle on each side, the stick above the D-pad on the left, the face buttons above the stick on the right, View and Menu in the handles' inner top corners, each trigger and its bumper the same size above its handle, the back buttons under the handles and Home under the phone, `PhysicalLayouts.controllerHandles`; in portrait no phone, just the two handles side by side as large as fit, `controllerHandlesNarrow`; keyboard, mouse, phone), devices to switch between along the bottom. A layer chip row edits a layer's mappings.

- **How a control looks** (`ui/DeviceControls.kt`): every control the same way. Inside it is its name; A B X Y and the D-pad have none (their place says which), View, Menu and Home show an icon. A tall narrow control (the mouse wheel) turns its name sideways; text shrinks to fit. A control that does something other than itself is purple, with a ↪ and how many things it does (`DevicePresets.remaps`: every layer and gesture; A to A, a key to the same key, the rocker to the PC's volume don't count). One that doesn't is grey with no number. Nothing is written beside the controls.
- **Tap** a control: its mapping editor opens, the same as for touch controls.
- While "Press a key", **⌨ Press a button** or learning listens, a controller's releases still reach the engine and its sticks are let go there when listening starts, so nothing held from before stays held on the PC.
- **⌨ Press a button** (beside the device switch at the bottom): press a key, button, stick, trigger or mouse button on a device attached to the phone and that control's mapping editor opens, on its device's view; a press no view shows says so and waits for the next.
- **Hold** a control (hidden action): the phone buzzes, the control lifts with a white ring, and a small popup lists only what it does (layer · gesture, then what it sends). Let go in place and the popup stays; a tap elsewhere closes it. **Drag it onto another control** (the popup closes once it moves) and let go: the two swap everything they do (`DevicePresets.swap`: mappings in every layer, gyro while held, own gesture timings; the press with the press, Tap with Tap; a trigger's Press and Full pull stay when swapped with a button). A second buzz, and a bar offers **Undo** for 5 s. While dragging, the drop target gets a pink ring; controls it can't swap with fade (sticks swap only with sticks; the mouse's movement with nothing). Stick and trigger tuning stay with the hardware.
- The bottom bar switches devices (an icon and a name each) and names these gestures.

## Stick and trigger tuning

`StickTuning` per stick, in the preset: the stick is one round vector; nothing below **inner deadzone** 0.10, full from **outer** 0.95; between them shaped by the **curve** (Linear, Soft = squared, Aggressive = square root) and scaled by **sensitivity** 1.0. A direction's **outer zone** (its chips after the separator on its card, [Mapping](phone-mapping.md)) fires past the **outer ring** 0.85, set in the stick's ⚙ or by tapping the separator. Directions are pies around each axis, **135°** wide by default (30–180°; over 90° overlap so a diagonal presses both). The stick's ⚙ shows a **picture** above its sliders (`ui/StickPie.kt`), like the touchpad's pies: the four direction pies, the inner deadzone (a hole in the middle), the outer deadzone (a grey band at the rim) and the Outer ring (dashed), redrawn while a slider is dragged. Opened from one direction's card, that pie is bright and the others faint. A dot follows the real stick while the ⚙ is open (the controller readers copy the sticks into `runtime.StickWatch` only meanwhile), or the finger on the circle, and the text beside it says what that way presses (directions, how far past the deadzone, and Outer).

`TriggerTuning`: **Press** from 0.5 of the pull, **Full pull** from 0.95.

## Held buttons and the Gyro tab

A physical button's mapping editor has a **Gyro** tab: gyro axes mapped there (`InputSource.Gyro` with id `phys:<device>:<control>`) take over that axis while the button is held, like a touch button's own gyro. Its ⚙ sets its gyro sensitivity, acceleration and touchpad speed while held, per layer (`HeldButton`, `ButtonGyro`). The last pressed of the held buttons wins.

## PC controller for gyro ratchet

Connection page › **PC controller for gyro ratchet** (Wi-Fi only, default off): a controller plugged into the **PC** ratchets the phone's gyro with no round trip to the phone.

- When the PC's WELCOME says `PC_GYRO` and the switch is on, the phone stops routing the gyro itself and sends each STATE as **STATE_GYRO** (`core/.../net/PcGyroFeed.kt`): the last 8 raw samples, per-axis running totals (so a longer gap fills in one step), and the gyro config those samples are meant for (sensitivities, glide, routes, curves, tightening, smoothing).
- **PAD_GYRO** tells the PC which controller buttons ratchet (all axes or one) or toggle the gyro, with a toggle epoch. They come from the pinned device preset: every controller button mapped to Ratchet, Ratchet yaw/pitch/roll or Toggle gyro (`MappingEngine.pcGyroButtons`), resent when that changes. **PAD_GYRO_SET** follows it with the own gyro settings of each such button that has them while held.
- The companion reads the PC's controller through XInput (`XInputPcPad`, 1 kHz, skipping its own virtual pad's slot) only while the switch is on. The rest of that controller goes to the game as it is.
- The PC runs the same gyro math (`GyroMath.cs`) and ratchets at each sample's own time (`PadHeldHistory`), so the result matches the phone exactly ([Gyro math](phone-gyro-math.md), [Virtual devices](pc-virtual-devices.md)).
- PC controller presses are never sent back to the phone.

Not available from a PC controller: gyro inertia (no glide on its ratchet) and the phone's own ratchet displays.

## Key files

`input/ControllerInput.kt`, `input/UsbPads.kt`, `core/.../input/HidGamepad.kt`, `ui/DevicePresetsScreen.kt`, `ui/ControlMappingScreen.kt`, `core/.../model/DevicePresets.kt`, `core/.../model/KnownLayouts.kt`, `core/.../model/ControllerDiagrams.kt`, `core/.../net/PcGyroFeed.kt`, `core/.../engine/MappingEngine.kt` (`pcGyroButtons`), `companion/src/GyroPhone.Protocol/PcGyro.cs`, `PcGyroTail.cs`, `PadHeldHistory.cs`, `GyroGate.cs`.
