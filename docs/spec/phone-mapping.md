# Phone: mapping system

Everything the phone sends comes from mappings: an **input** (`InputSource`) pressing an **output** (`OutputTarget`) in one **mapping set**, with options that say when it fires and how long its output lasts. The model is in `core/.../model/Mapping.kt` and `Triggers.kt`; the engine that runs it is `core/.../engine/MappingEngine.kt`.

```kotlin
data class Mapping(val source: InputSource, val target: OutputTarget, val set: Int = 0, val options: MapOptions? = null)
```

One input can press several outputs, and several inputs can press one output. There is no turbo.

## Inputs

| Input | Source type | Notes |
|---|---|---|
| Touch button press | `Touch(id)` | Held from touch-down to lift. Its gestures are the one-finger inputs of the button's own touch surface. |
| Touch surface inputs | `Surface(fingers 1..4, input, id, zone, on)` | `id = ""` is the workspace touchpad, else a touch button. See [Touch surfaces](#touch-surfaces). |
| Touch stick | `TouchStick(id, X/Y, half)`, `TouchStickDiagonal(id, diagonal)` | An axis (−1..1) or one direction (a half). Diagonals only with 8 zones. |
| Where a finger is | `TouchArea(id, kind, on)` | **Inside**: any finger inside the control's area, wherever it touched down. **Outside**: a finger that touched down on the control is now outside it. **On touch** (sticks): from touch-down on the stick until lift. |
| Physical key or button | `Key(keyCode)`, `KeyGesture(keyCode, gesture)` | Any key of an attached pad, keyboard, mouse (its buttons and wheel notches as `MOUSE_` keys) or the phone's volume rocker. |
| Physical axis | `Axis(axis, half, stage)` | A stick or trigger axis, a half of it, or a digital stage: a stick past its outer ring (`OUTER`), a trigger past its press point (`PRESS`) or fully pulled (`FULL_PULL`). Mouse movement comes as the `MOUSE_X/Y` axes. |
| Gyro axis | `Gyro(axis, id)` | Yaw, pitch or roll in °/s. See [Gyro math](phone-gyro-math.md). |
| Tilt | `Tilt(X/Y, half, id)` | −1..1, full at the tilt range. |
| Shake | `Shake(id)` | A short press. |

`id` on gyro, tilt and shake is `""` for the workspace's (always) or a touch button's (only while it is held, taking that input over).

## Outputs

| Output | Target type | Notes |
|---|---|---|
| Game pad button | `Pad(button)` | A, B, X, Y, LB, RB, View, Menu, LS, RS, Guide, M1..M4, Share; the D-pad goes to the hat. |
| Game pad axis | `PadAxis(axis, scale)` | LX, LY, RX, RY, LT, RT. A digital input gives ±scale; −1 inverts. |
| Key | `Key(usage, mode)` | HID usage. `mode` Hold / Toggle / Next key only is from before options; old ones are turned into Toggle mappings on load (`ToggleUpgrade`). Media keys and the volume keys are HID usages too. |
| Mouse button | `Mouse(button)` | Left, right, middle, back, forward. |
| Mouse motion | `MouseAxis(motion, scale)` | Move X, Move Y, Scroll, Scroll sideways. Follows a motion input (touch travel, gyro, mouse); scrolling is natural (the page follows the finger), −1 reverses. |
| Wheel step | `Wheel(step)` | One notch up (+1) or down (−1) per press. |
| Ratchet | `GyroPause`, `GyroPauseAxis(axis)` | See [Gyro math](phone-gyro-math.md). `GyroHold` from old files works as a ratchet. |
| Toggle gyro | `GyroToggle` | Swaps the gyro's ratchet mode until the workspace changes. |
| Switch apps | `SwitchApps` | From a surface's Move X: Alt held while the fingers stay down, Tab or Shift+Tab per 80 dp of travel. |
| Zoom | `Zoom` | From a Pinch: Ctrl + wheel, spreading apart zooms in. |
| Haptic | `Haptic(held, feel, edge)` | The phone buzzes (only while a PC is connected): a tick on press, release or both, or continuously while held (an axis buzzes stronger the further it is pushed). `feel` and `edge` null = the workspace's. |
| Haptic steps | `HapticStep(percent, feel)` | A tick each time the input's value crosses a step (2..50 %, default 10). |
| Layer | `Layer(set, override, whileHeld)` | See [Layers](#layers). |
| Layer back | `LayerBack` | Back to the sets in use before the last layer change; twice swaps back again. |
| Let go | `LetGo` | Ends the toggles set to end on it. |
| Black screen | `BlackScreen` | The pad goes black. See [Volume rocker and black screen](phone-volume-black-screen.md). |
| Nothing | `NoOutput` | Does nothing. In an override layer it stops the input falling through; at a level it clears what the input inherits. |

The editor's target picker (`ui/BindingEditor.kt`) groups these, has a search box, and can capture a key: "Press a key on a keyboard".

## When, How and Where

Each mapping chip opens its own settings (`ui/MappingChipDialog.kt`): **Where**, **When**, **How**, "Also ends on" and Advanced.

**Where** (touch buttons and sticks): **Touch** (the press itself), **Inside**, **Outside**. Inside and Outside run the same gesture rules as a touch, with entering as the touch-down and leaving as the lift.

**When** (`Trigger`; part of the mapping's source, so each trigger of one input is its own source):

| When | Fires | Swipe direction name |
|---|---|---|
| On touch (`HOLD`) | from touch-down to the lift | On swipe: from reaching the hold distance to the lift |
| Tap | at a lift before the Tap time | Quick swipe: each quick swipe |
| Long press | once held past the Tap time, to the lift | Long hold: once held for the Tap time |
| Double tap | from a second touch-down within the Tap time of a tap's lift, until it lifts (but see below) | Double swipe: a second quick swipe soon after |
| Release | at every lift | Release |

Inputs that offer triggers (`Triggers.supports`): physical keys, touch buttons, the touchpad's finger counts, swipe directions, Inside, Outside and a stick's On touch. A later length zone offers only On touch, Long press and Release. Everything else is On touch only. `Press` (a pulse at each touch-down) is no longer offered; old ones load as On touch + Once.

**Gesture timing.** One **Tap time** (default 300 ms, 100..1000) times everything: a tap is a lift before it, a long press starts once held past it, and a double tap's second touch must come within it of the tap's lift. It is a workspace setting ("Gesture timings"), with a touch button's own and a physical key's own (in its device preset, `KeyTiming`) over it; older separate double-tap windows are no longer used (`doubleTap` = `longPress`). On touch never waits. A tap waits out the Tap time only when that input's Double tap does something different (compared by output and How); mapped alike the tap stays instant. Long press never delays a tap. A double tap is never also a tap or long press, and a long press is never a tap.

**Double tap with Held and another chip.** When one input's Double tap has a Held chip and a chip of another How, the second touch is told apart first: a quick lift (before the Tap time, without leaving the tap slop) fires the other chips, and staying down past the Tap time or moving fires the Held ones until the lift (`Shaper.Split`, worked out in `MappingEngine.updateShapers`). Otherwise a double tap fires at the second touch-down.

**Several chips.** A When can hold any number of chips with any outputs, the same output too when its How differs (Double tap Left Repeat ×2 and Left Held). Only an exact duplicate (same output, How and When) is refused: one dropped where it is already goes to the next free When, and its settings open. In the editor the chips of one When are `Bindings.Entry`s (`entries`, `setEntries`, `removeEntry`). On touch surfaces a tap must also stay still (within 10 dp) and lift quickly; physical keys use `core/.../engine/ButtonGestures.kt`, where a tap is a press shorter than the long-press time.

**How** (`MapOptions.how`):

- **Held**: pressed for as long as the activation lasts (only for triggers that last: On touch, Long press, Double tap).
- **Once**: one short press as it starts, however long it lasts.
- **Toggle**: each activation turns it on, the next off.
- **Repeat ×N** (2..5, default 2; "Times" in the chip's settings): N short presses as it starts, each followed by as long a gap (a double click is Left Repeat ×2). Its chip shows the count.

**Also ends on** (Toggle): **Tap** (touching this button or surface again), **Let go** (a Let go mapping firing), **Timer** (up to 60 s), **Next input** (once the next other key, mouse button or pad button has been pressed and let go: a sticky Shift), **Same keystroke** (this key or button pressed by any other mapping).

**Advanced**: **Start delay**, **End delay**, **Hold at least** (each up to 2000 ms), and **Cycle**: the mappings of one input and set with Cycle on take turns, one per activation, in list order.

A mapping with options runs through its own `core/.../engine/Shaper.kt`, which works out the activations from the source's level and the clock, then applies How, delays and ends. Every press lasts at least the minimum pulse, so a snapshot always shows it.

## Layers

A workspace has Base (set 0) and up to 9 named layers (`MappingSet`). Every mapping names its set. `OutputTarget.Layer`:

- **Switch** (not override): every input does what that set maps, and nothing if it maps nothing; clears the overrides.
- **Override**: the set is laid over the current one; inputs it maps do that, every other input keeps the current set's. Pressed again (or any switch) it goes.
- **While held**: only while the source is held; on release things go back. The holding input also presses what the new set maps it to (not that set's layer actions, which wait, so nothing flips back and forth).

`layered()` in `Mapping.kt` computes the mappings in use: the switched-to set with each override laid over, the topmost override that maps an input winning. Inputs held across a change keep pressing what they pressed until released, unless the touch control has **Follow layers** on (then it lets go and takes the new set's mapping at once). Motion inputs (full stick axes, touch motion) follow the new set at once. A layer can carry its own workspace settings ([Workspaces](phone-workspaces.md)); they apply while it is in use. The pad shows the layer in use as a pill.

## Touch surfaces

The workspace touchpad (the pad's empty background) and **every touch button** are touch surfaces: fingers on them drive `Surface` inputs per finger count (1 to 4), computed by `core/.../engine/Touchpad.kt` from raw pointer events and compiled per surface by `core/.../engine/SurfaceMap.kt`. A touch button's one-finger On touch is its press (`Touch(id)`). A button takes the touchpad's mapping of every surface input it does not map itself (see [Workspaces](phone-workspaces.md)), so a finger on a button can aim like the touchpad.

Surface inputs (`SurfaceInput`):

- **Tap, Double tap**, **Hold**, **Long press**, **Release**, **Press**. Tap-drag is gone: old Tap-drag mappings load as Tap + Double tap Repeat ×2 + Double tap Held of the same output (`Triggers.tapDrags`), and the editor no longer offers it.
- **Move X / Move Y**: the fingers' travel (cursor, scroll, a stick from the touch-down point, switching apps). Sensitivity (default 1.5), acceleration (default 1), scroll speed, natural scrolling (default on) and inertia (default on, 325 ms, Smooth or Linear) shape mouse and wheel output here. Scrolling is 24 dp per notch at scroll speed 1, in 1/120-notch units. Each Move can be **Free** or move only inside its **Pie** (default 120°), with a cursor deadzone, catch-up, sample length, heading lock and pie margin; a **× Parent** factor is offered for its sensitivity.
- **Quick swipe ← → ↑ ↓**: a pulse each time the fingers travel the swipe distance (default 40 dp) since turning that way, inside its pie (default 90°).
- **Swipe ← → ↑ ↓** (held directions): held as the hold mode says. **From start point** (default): held while the finger is past the hold distance (default 20 dp) from where the motion started, like a floating stick. **Direction change**: held once the finger has travelled the hold distance since turning that way, until another direction takes over or the fingers lift. Pies default 180° (each axis on its own). Each direction can be split into **length zones** in the editor (dividers on its row); a zone's mappings are held while the finger is in it, kept as its Keep says (Only in zone, Only forward, Always). A held direction **latches** (stays held after the lift) when one of its On swipe chips is a Toggle that also ends on a tap; a tap lets it go; it can also end on a timer, Let go or the next input.
- **Pinch** (the spread change, for Zoom), **Pinch in**, **Pinch out**.

Direction tracking (pies, samples, heading lock and "turn after", zones) is `core/.../engine/DirectionTracker.kt`. Every direction and every Move has its own settings over the surface's shared ones (`DirSettings`, `MoveSettings`), and a touch button's surface has its own over the workspace touchpad's (`SurfaceSettings`). A finger on a touch button can show a swipe line or Move rulers ([Pad UI](phone-pad-ui.md)).

**Windows touchpad preset** (`Bindings.defaultTouchpadMappings`): Tap = Left, Double tap = Left Repeat ×2 and Left Held (so a tap clicks once the Tap time has passed, a quick double tap double-clicks, and tap then hold or move drags; no special touchpad code), two-finger tap = right click, one-finger Move X/Y = cursor, two-finger Move Y/X = scroll and sideways scroll, two-finger pinch = zoom, three-finger swipe up = Task View, down = show desktop, left/right = switch apps, three-finger tap = middle click, four-finger swipe up = Task View, down = show desktop, left/right = switch virtual desktops, four-finger tap = notification centre. Phones whose system takes three-finger touches for screenshots cancel them; turn that gesture off in the phone's settings.

## Touch controls

`TouchControl` (`core/.../model/Profile.kt`): id, kind (`BUTTON`, `STICK`, `DISPLAY`, and `FULLSCREEN` for the nav corners), label, centre and size (fraction of the pad's shorter side, 0.06..3), circle or rectangle (with an aspect), a separate portrait placement, and:

- **Sticks**: deadzone (share of the radius that counts as centred) and **zones**: "4, diagonals press two" (each half on its own), 4 zones, or 8 zones (a diagonal fires its own mapping, or both neighbours when it has none). Stick presets in the editor: Left stick, Right stick, WASD, Arrow keys, D-pad, None.
- **Overlap**: where controls overlap, the front one under a finger gets it (`hitOrder`: Front, then Auto by size with smaller in front, then Back). **Shares touches**: a finger landing here while another control is in front works this one too.
- **Zone interrupt** (Inside mappings): a finger sliding in stops what it was doing (cursor, stick, another button) while inside; **resume** restarts it when the finger leaves.
- Own Tap time, own gyro sensitivities while held (per layer), own touchpad speed while held, and an edit-mode group.

## How the engine runs it

`MappingEngine` is thread safe: touch input comes from the UI thread, gyro and accelerometer from the sensor threads, and the link's pump calls `snapshot()` on its own thread.

- **Source groups.** The workspace's touch mappings run over the touch state; each attached device runs over its pad's mappings (a device no pad of the workspace matches does nothing); the keyboard, the mouse and the phone's own keys each over theirs. Their outputs merge into one: buttons, keys and mouse buttons OR together; a stick axis takes the group with the largest deflection; a trigger the largest value.
- **Event targets** (wheel steps, toggle gyro, layer actions) fire once per engagement however many held sources map to them, so one input written to several sources (a trigger's axis and its L2 key) can't double-fire. An analog trigger axis mapped to a pad trigger stops that side's digital L2/R2 key from also driving it.
- **No lost edges.** A key or touch pressed and released between two snapshots still shows in one (`keyForOutput`, the touchpad's pulse queues, the minimum pulse of 40 ms or one report interval + 12 ms, whichever is longer).
- **Snapshot.** `snapshot()` returns the `HidState` (pad, keyboard with every held key in press order, mouse buttons, mouse and wheel motion drained since the last snapshot) and runs the haptic sink and the camera sink outside the lock. `onChange` wakes the sender after every input change.
- **Releases.** Changing workspace, profile or link, leaving the pad, or the host disconnecting releases everything and goes back to Base (`reset()`).

The debug trace (`core/.../debug/DebugTrace.kt`), when on, logs each input edge with what it maps to in the set in use, each output change, and layer and workspace changes.

## Key files

`core/.../model/Mapping.kt`, `Triggers.kt`, `Bindings.kt`, `Inheritance.kt`, `ToggleUpgrade.kt`, `SurfaceField.kt`, `Profile.kt` (`TouchControl`, `TouchpadSettings`, `SurfaceSettings`, `DirSettings`, `MoveSettings`, `HoldZone`), `core/.../engine/MappingEngine.kt`, `Touchpad.kt`, `SurfaceMap.kt`, `DirectionTracker.kt`, `ButtonGestures.kt`, `Shaper.kt`, `SwipeGuide.kt`, `app/.../ui/ControlMappingScreen.kt`, `MappingChipDialog.kt`, `BindingEditor.kt`, `DirectionSettings.kt`, `ActionSettings.kt`.
