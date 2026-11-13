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
| Gyro axis | `Gyro(axis, id)` | Yaw, pitch or roll in °/s. Each card's ⚙ has a **Space** (Local, World, Player for yaw), following the workspace's Gyro space unless set; the title names it ("Local pitch", "World yaw", "Player yaw"). See [Gyro math](phone-gyro-math.md). |
| Tilt | `Tilt(X/Y, half, id)` | −1..1, full at the tilt range. Sideways is the phone's turn about the axis through its screen, like a steering wheel, at any hold, even lying flat on a lap ([Gyro sensors](phone-gyro-sensors.md)). |
| Shake | `Shake(id)` | A short press. |
| Where the finger is | `Finger(id, axis)` | On a touch button (`id`) or the workspace touchpad (`""`, its first finger): **Position X** (0 at the left edge to 1 at the right), **Position Y** (0 at the bottom to 1 at the top); on round buttons also **Position distance** (centre 0 to rim 1) and **Position angle** (a dial: 0 at the top, clockwise, 1 once around, jumping back to 0 there). The first finger on it counts; slid off the button it stays at the nearest edge. Active from touch-down to lift. Its ⚙ (`FingerAxisSettings`, a button's `TouchControl.fingers`, the touchpad's `Workspace.padFingers`): **Live** (follows the finger) or **Locked** (where the finger landed, until the lift), and **Deadzones**, one slider with two handles: 0 up to the first, full from the second, even between. The engine measures it from the pad area and the button's placement (`MappingEngine.setPadArea`, `fingerTrack`). |

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
| Previous workspace / Next workspace | `PreviousWorkspace` / `NextWorkspace` | The pad shows the workspace before / after this one (wrap-around: Settings → Workspace switching). |
| Fullscreen | `Fullscreen` | The pad goes fullscreen (its top row hides), or back. |
| Set orientation | `SetOrientation` (saved as `level_displays`) | The way the phone is held now becomes the workspace's base orientation (Yaw axis, saved as its own): the screen axis, either way up, pointing most nearly up, or with the chip's **Granular** switch (off by default) the exact tilt back, leaning sideways left out. Every 3D gyro display on the pad starts again from it, level and facing ahead (its saved Level grip cleared), and every PC overlay shown levels. Lying flat, sideways tilt (a steering wheel) takes the way the phone points as straight. Nothing held is let go. The PC hears it as a count in OVERLAY (byte 139). |
| Clear displays | `ClearDisplays` | Every laser pointer display on the pad and every PC laser pointer overlay loses its paint and trail, as its Clear button does. The PC hears it as a count in OVERLAY (byte 148; the first count seen clears nothing). |
| Nothing | `NoOutput` | Does nothing. In an override layer it stops the input falling through; at a level it clears what the input inherits. |
| Gyro sensitivity | `GyroSens(axis)` | In the Gyro group: every axis, or yaw, pitch or roll. While its input is active it sets that gyro sensitivity from the input's value through the chip's **Range** (below), over every other sensitivity (the workspace's, a held button's); of several active, the last activated wins (an axis's own chip counts only if activated after an every-axis one). It goes back when the input lets go. A new chip's range is the sensitivity it changes ±20 %. See [Gyro math](phone-gyro-math.md). |
| Touch sensitivity | `TouchSens(axis)` (saved as `touch_sens`) | In the Touch group (the library's **Touch** tab): All, X or Y. Like Gyro sensitivity, but for the touchpad's Move sensitivity (default 1.5): while its input is active it sets it from the input's value through the chip's Range, and it goes back on lift. |
| Tap time | `TapTime` (saved as `tap_time`) | In the Touch group (the library's **Touch** tab). Like Gyro sensitivity, but for the Tap time (ms, Range 100..1000, a new chip 150–600 ms): while its input is active the touchpad, and the workspace's touch buttons and keys without their own Tap time, judge their taps by it; it goes back on lift. It is read as each tap is judged, so it changes the taps to come, not one already under way. On the pad its line reads "50 · Tap 375 ms". |
| Gyro glide | `GyroGlide(axis)` (saved as `gyro_glide`) | In the Gyro group: All, or one axis. Like Gyro sensitivity, but for Inertia's glide (ms, 100..1000, a new chip 150–600 ms): how long the view glides on after a ratchet turns the gyro off. It changes only the glide; Inertia itself stays on or off as set (Settings › Gyro). |
| Tilt range | `TiltRange(axis)` (saved as `tilt_range`) | In the Gyro group: both, X (sideways) or Y (forward and back). Like Gyro sensitivity, but for the Tilt range (degrees, 5..90, a new chip 15–45°): how far you tilt for a full Tilt. |

The editor's target picker (`ui/BindingEditor.kt`) groups these and has a search box. The library's tabs have no Touchpad tab: Touch sensitivity and Tap time have their own **Touch** tab, and Switch apps and Zoom are under **Presets**. **⌨ Press a key** (the mapping editor's library, before its tabs; `ui/KeyCapture.kt`) maps what you press next on the selected action: a key, a mouse button or wheel notch, a controller button, or a stick or trigger pushed past half way (a stick's way gives that axis, or its inverted one), on a keyboard, mouse or controller **attached to the phone or to the PC**. Each becomes the output of the same name (a key the same key, Mouse 4 Mouse back, a controller's A the game pad's A, its D-pad hat the D-pad; `core/.../model/PressCapture.kt` `PressTargets`). Everything pressed together joins, in the order pressed, and is set once all of it is let go, so holding Ctrl and pressing C maps Ctrl + C as two chips (`PressCombo`). A press the action can't take (a key on an analog action) adds nothing and says so; **Use** takes what is shown if a release never comes. While it listens a mouse's clicks are presses, not taps (tap Cancel with a finger). The PC reads its keyboard and mouse (raw input) and its first Xbox-style (XInput) controller, not the companion's own virtual pad, only while this screen is open, and **only reads**: nothing is held back from Windows or the game, so a key pressed there also does what it does in the game (CAPTURE / CAPTURED, see [Protocol](protocol.md)). A DualSense or DualShock on the PC shows only through Steam's Xbox mode.

**Key names follow the PC.** A key output is a key position (a HID usage), and the PC turns it into a character with its own keyboard layout. So the PC companion sends what each character key types on its current layout (KEY_LABELS, see protocol.md), and the phone names keys by that (`HidUsage.label`): on an Italian layout the key above Enter shows as "Ù" and the one left of 1 as "\". The last PC's names are kept while none is connected; before any PC has sent them, keys show their US names. Changing the PC's layout renames keys; mappings keep their key positions. The companion's Debug window names keys the same way.

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

**Gesture timing.** One **Tap time** (default 300 ms, 100..1000) times everything: a tap is a lift before it, a long press starts once held past it, and a double tap's second touch must come within it of the tap's lift. It is a workspace setting (its "Taps" tab), with a touch button's own and a physical key's own (in its device preset, `KeyTiming`) over it; a **Tap time** chip (a slider, see Set value and Move sliders) or its kept slider override lies over the workspace's, never over a button's or key's own (`MappingEngine.applyTapTimes`); older separate double-tap windows are no longer used (`doubleTap` = `longPress`). On touch never waits. A tap waits out the Tap time only when that input's Double tap does something different (compared by output and How); mapped alike the tap stays instant. Long press never delays a tap. A double tap is never also a tap or long press, and a long press is never a tap.

**Double tap with Held and another chip.** When one input's Double tap has a Held chip and a chip of another How, the second touch is told apart first: a quick lift (before the Tap time, without leaving the tap slop) fires the other chips, and staying down past the Tap time or moving fires the Held ones until the lift (`Shaper.Split`, worked out in `MappingEngine.updateShapers`). Otherwise a double tap fires at the second touch-down.

**Several chips.** A When can hold any number of chips with any outputs, the same output too when its How differs (Double tap Left Repeat ×2 and Left Held). Only an exact duplicate (same output, How and When) is refused: one dropped where it is already goes to the next free When, and its settings open. In the editor the chips of one When are `Bindings.Entry`s (`entries`, `setEntries`, `removeEntry`). On touch surfaces a tap must also stay still (within 10 dp) and lift quickly; physical keys use `core/.../engine/ButtonGestures.kt`, where a tap is a press shorter than the long-press time.

**How** (`MapOptions.how`):

- **Held**: pressed for as long as the activation lasts (only for triggers that last: On touch, Long press, Double tap).
- **Once**: one short press as it starts, however long it lasts.
- **Toggle**: each activation turns it on, the next off.
- **Repeat ×N** (2..5, default 2; "Times" in the chip's settings): N short presses as it starts, each followed by as long a gap (a double click is Left Repeat ×2). Its chip shows the count.

**Also ends on** (Toggle): **Tap** (touching this button or surface again), **Let go** (a Let go mapping firing), **Timer** (up to 60 s), **Next input** (once the next other key, mouse button or pad button has been pressed and let go: a sticky Shift), **Same keystroke** (this key or button pressed by any other mapping).

**Range** (axis chips: game pad axes and triggers, Gyro sensitivity; `Mapping.range`, `AxisRange`): a slider with two handles setting what the input's 0 and full send, the output moving evenly between them while the input is active (a finger on the button, a held swipe direction, any other input off its rest; inactive it sends nothing, so a trigger at rest stays at 0). Dragging one handle past the other reverses it. A pad axis or trigger is in % (0–100 %); Gyro sensitivity in RWS when the workspace knows its game, else counts per degree, the slider up to the Slider cap ([Settings](phone-settings.md)). The chip shows its range ("RT 10–85 %", "Sens 4.00–6.00"). An input's own deadzones (finger ⚙, tilt deadzone and range, a controller's stick tuning) come first, the range after.

**Advanced**: **Start delay**, **End delay**, **Hold time** (each up to 2000 ms; Hold time, the shortest the output lasts, starts at 80 ms and shows **Reset to 80 ms** once changed, so a quick tap stays down long enough for apps that check keys every few tens of ms, such as OBS hotkeys, which need a combo seen on two checks about 25-31 ms apart; set it lower or higher per mapping), and **Cycle**: the mappings of one input and set with Cycle on take turns, one per activation, in list order.

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
- **Move X / Move Y**: the fingers' travel (cursor, scroll, a stick from the touch-down point, switching apps). Sensitivity (default 1.5), acceleration (default 1), scroll speed, natural scrolling (default on) and inertia (default on, 325 ms, Smooth or Linear) shape mouse and wheel output here. Scrolling is 24 dp per notch at scroll speed 1, in 1/120-notch units. Each Move can be **Free** or move only inside its **Pie** (default 120°), with a cursor deadzone, catch-up, sample length, heading lock and pie margin; its sensitivity offers a **Scale parent** switch (a 0 to 1 share of the inherited value).
- **Swipe** (one card in the editor): one card with a line per direction: **Any** (shown as an icon of eight arrows around a dot, `AnyWayIcon`), ↑, →, ↓ and ←, each with its own row of chips (`Bindings.swipeSlots`, `holdSlot`; `linesOf` in the editor). Only lines that map something show. While a target is dragged, one **New** line shows under them (`newLineOf`: the first direction without a line, ↑ → ↓ ← then Any); a target dropped there is added and its settings open, to pick its **Direction** and the rest. Dropping it right on a line adds it there, as on any card. Tapping the card picks its ↑ line; tapping a line's name picks that line, and library taps add there. **Dragging a chip to another line changes its direction** (its chip settings also have a **Direction** choice). Each line has its own zone dividers: the number of zones is each line's own, and their lengths are shared by default; a line's divider (or the ⚙'s arrow picker, where a • marks an arrow with its own settings) can give that direction lengths of its own. **Any** always uses the shared lengths, so the dividers of different lines only line up while their lengths match. The whole card is **one action** for inheritance (`Inheritance.cardOf`): a button that maps anything on any Swipe line takes none of the touchpad's swipes, and a cleared Swipe card clears every direction. The card's ⚙ shows each arrow's own settings (pick ↑ → ↓ ←): its held swipe's pie, sample, heading lock and hold distance, then its quick swipe's; Any has none of its own.
- **Quick swipe** (the When of a swipe chip; `SWIPE_*`): a pulse each time the fingers travel the swipe distance (default 40 dp) since turning that way, inside its pie (default 90°). With **Any** (`SWIPE_ANY`) it pulses with each quick swipe, whichever way.
- **Any** (`HOLD_ANY`): held while the finger's straight-line distance from where it touched down passes the shared hold distance, in any direction and in either hold mode (no pies). Its zones are the shared ones, by that distance; an axis chip on it follows how far through its zone the finger is, like a direction's. It latches like a direction.
- **↑ → ↓ ←** (held directions, `HOLD_*`): held as the hold mode says. **From start point** (default): held while the finger is past the hold distance (default 20 dp) from where the motion started, like a floating stick. **Direction change**: held once the finger has travelled the hold distance since turning that way, until another direction takes over or the fingers lift. Pies default 180° (each axis on its own). An **axis chip** (a pad axis or trigger, Gyro sensitivity, Haptic steps) on a held direction gets how far through its zone the finger is: 0 at the zone's start, full at its end and past it (`Touchpad.zoneLevel`, `DirectionTracker.progress`); an empty zone before it works as a deadzone, and an unsplit direction is one zone, from the hold distance for one zone length. Key and button chips there stay plain presses. Each direction can be split into **length zones** in the editor (dividers on its row); a zone's mappings are held while the finger is in it, kept as its Keep says (Only in zone, Only forward, Always). A held direction **latches** (stays held after the lift) when one of its On swipe chips is a Toggle that also ends on a tap; a tap lets it go; it can also end on a timer, Let go or the next input.
- **Pinch** (the spread change, for Zoom), **Pinch in**, **Pinch out**.
- **Position** and **Move** (one card each in the editor, folded like Swipe): Position has an X and a Y line (plus Distance and Angle on a round button), Move an X and a Y line; only lines that map something show, and a dragged target gets a **New** line. A chip's settings call its line **Axis** there. Other chips there (keys, buttons) keep every How; on Position a Toggle has no Tap end, since its next touch already ends it.

### Set value and Move sliders

A value chip (a pad stick or trigger axis, Gyro sensitivity, Touch sensitivity, Tap time, Gyro glide, Tilt range) on a Position or Move line is a slider. Its **How** is only **Held** or **Toggle**, with no Cycle, delays or hold time (they shape presses, not a value):

- **Position** sets the value from where the finger is: a tap sets it at once. The input's deadzones apply first, then the chip's Range.
- **Move** changes the value by finger travel, never by a tap: each chip has its own **Per dp** change (default: 300 dp covers the whole range; a stick on the floating stick keeps 40 dp). Right and up increase it; no acceleration, inertia or deadzone. Held starts at the range's start and goes back on lift; Toggle **nudges** the value it keeps.
- **Held**: the value counts only while the finger is down.
- **Toggle** keeps the value after the lift (`Mapping.setValue`, worked out from the options; `MappingEngine.chipValue`), until one of its **Also ends on**: **Let go**, **Next input**, **Timer**, and **Tap** (Move only, since a tap on Position sets the value; no Same keystroke). A new Toggle starts with nothing ticked for a sensitivity and Let go for a trigger or stick. Touching again doesn't switch it off: it sets or nudges the value. A kept trigger or stick also lets go on a workspace switch or app restart; with an end, a sensitivity goes back at the end.
- **A sensitivity (or Tap time, Gyro glide, Tilt range) with Toggle and no end** becomes the workspace's **slider override** (`Workspace.sliders`, `SliderOverride`, `SliderOverrides.put`): saved, lying over the settings in effect (a held button's own included), while the settings themselves never change. One per setting per workspace: the newest wins and older ones of it are cleared (an every-axis one clears the axes' too). It is listed in the sensitivity's pink "own" list as "Aim slider 4.7" under the workspace, or under the Defaults where the workspace follows them, and **Reset children** clears it ([Settings](phone-settings.md#defaults-tab)). Saving it lets nothing held go (`MappingEngine.updateSettings`).
- **On the pad** (`ui/ValueBars.kt`): a button (or the touchpad) with a Toggle value chip shows a small **sliders icon** in its top-left corner. A Toggle Position chip draws a bar across the button where its value is (upright for X, flat for Y, a ring for distance, a needle for angle), clipped by the button's edge. Its values show one line per chip, "62 · RT 62 %": where the value is in its range (0..100), then what it sends. A gyro sensitivity names RWS when the workspace's game is set ("40 · Yaw 4.50 RWS"), else "40 · Yaw sens 16". Chips at the same place sending the same share a line ("40 · Yaw Pitch 4.50 RWS"). They sit in the middle of the button in place of its label when they fit there, stacked or else all on one row; otherwise in a frame outside the button, drawn behind the buttons. On a wide or square button that frame sits above it, lined up with its right edge (below it near the screen's top edge); on a tall button beside it on the right, lined up with its top (on the left near the screen's right edge); never sticking out past the corner, always on screen. The touchpad's frame stays inside its top-right corner. With Toggle the line always shows; with Held, only while it is driven. Move shows only the frame, no bar.
- **Snap** (a Position's ⚙, by Live/Locked and Deadzones; buttons and the touchpad): the value goes in steps of this share of the travel, 1 to 50 % on the slider (more typed), off by default. The travel is cut into the nearest whole number of equal steps (`FingerAxisSettings.steps`; 30 % gives 3 steps of 33 %), and the value lands on the nearest after the deadzones (`FingerAxisSettings.shape`). Every chip of the input then lands on the same step of its own Range, so they move exactly together (Yaw 4..8 and Pitch 2..4: Yaw 5 is always Pitch 2.5). The bar sits on the step and its ruler has a tick at each step (every 2nd, 5th, 10th… when they would be closer than 6 dp; every 5th shown tick long) instead of the 10 % scale. Each Position axis draws one bar, whatever number of chips it has. Move has no Snap: its chips slide by their own Per dp, with no shared travel.

Direction tracking (pies, samples, heading lock and "turn after", zones) is `core/.../engine/DirectionTracker.kt`. Every direction and every Move has its own settings over the surface's shared ones (`DirSettings`, `MoveSettings`), and a touch button's surface has its own over the workspace touchpad's (`SurfaceSettings`). A finger on a touch button can show a swipe line or Move rulers ([Pad UI](phone-pad-ui.md)).

**Windows touchpad preset** (`Bindings.defaultTouchpadMappings`): Tap = Left, Double tap = Left Repeat ×2 and Left Held (so a tap clicks once the Tap time has passed, a quick double tap double-clicks, and tap then hold or move drags; no special touchpad code), two-finger tap = right click, one-finger Move X/Y = cursor, two-finger Move Y/X = scroll and sideways scroll, two-finger pinch = zoom, three-finger swipe up = Task View, down = show desktop, left/right = switch apps, three-finger tap = middle click, four-finger swipe up = Task View, down = show desktop, left/right = switch virtual desktops, four-finger tap = notification centre. Phones whose system takes three-finger touches for screenshots cancel them; turn that gesture off in the phone's settings.

## Touch controls

`TouchControl` (`core/.../model/Profile.kt`): id, kind (`BUTTON`, `STICK`, `DISPLAY`, and `FULLSCREEN` for the old nav corners), label, centre and size (fraction of the pad's shorter side, 0.06..3), circle or rectangle (with an aspect), a separate portrait placement, and:

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
