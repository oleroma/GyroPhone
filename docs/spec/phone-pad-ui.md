# Phone: pad screen and edit mode

What the person sees and touches: the pad, its top row, the ← / → corners, edit mode and the mapping editor. What touches do is in [Mapping system](phone-mapping.md); settings pages are in [Settings](phone-settings.md).

## The app is the pad

`ui/MainActivity.kt` shows `ui/PadMenu.kt` `PadTab`: the pad (`ui/PadScreen.kt`) fills the screen, and every other page (Controller, Settings, Connection, a workspace's settings) opens in its place. Back steps out one page at a time. There are no other tabs.

**Play mode** draws the active workspace's controls in its placement for the current orientation (landscape and portrait placements are kept apart; a control with no portrait placement gets one derived from landscape, squeezed into the lower part with the top left to the touchpad). Empty space is the workspace touchpad. Each finger on a stick owns it until it lifts; a finger on a button goes to the engine; overlapping controls are allowed (front one wins unless a control behind has "shares touches"; order by size, or Front/Back). Play mode asks Android for unbuffered touch and joystick input (`TouchStream`) and passes the event time on, so the engine sees touches at their real time. A system cancel (an OxygenOS three-finger gesture taking the pointers) is handled phone-side: every finger is let go cleanly.

## Top row (capsules)

`ui/CapsuleRow.kt`, shown in play mode when not in fullscreen:

- **+** adds a workspace at the front (Blank, Windows touchpad or a preset, see [Workspaces](phone-workspaces.md)).
- One capsule per **workspace**: tap to switch; tap the current one to open its edit mode.
- **Controller** (the device screens, [Controllers](phone-controllers.md)), **Settings**, **Connection** (shows the link state).

The row scrolls. Long-press a capsule and drag it to reorder; moving workspaces reorders them. The order is stored as a string of `W`/`D`/`S`/`C`.

## ← / → corners and fullscreen

The two nav corners (`Profile.corners`, shared by all workspaces) are always on the pad:

- tap ← / → for the previous / next workspace (dimmed to 35 % at an end, unless wrap-around is on);
- press both together for **fullscreen** (hides the top row and system bars); both again, or Back, to leave;
- their opacity (default 50 %) never goes below 25 % in play, since they are the way out of fullscreen;
- **Hide the ← / → corners after a few seconds** (default off): they fade 3 s after a reveal; four quick taps (≤300 ms apart) anywhere, or touching a corner, show them again;
- corners can be moved and sized in edit mode; "Same size for all corners" (default on) sizes both together.

## Edit mode

Opened by tapping the current workspace's capsule. It works on a **draft** of the profile, kept across activity recreation (up to 100 000 JSON characters). The pad shows the workspace being edited; controls are drawn at least 50 % opaque.

**Gestures**: tap a control to select it (taps on overlapping controls cycle through them); **long-press (450 ms)** opens its full-screen mapping editor; drag moves the selected control (with none selected, the one under the finger); two-finger pinch resizes (per axis when the fingers spread mostly one way); a two-finger tap selects nothing. At least 48 dp of a control must stay on screen. A tap on empty space selects the touchpad (opens its mapping editor on long press).

**The floating menu** (drag by its ⠿ dots, resize, fold):

- ↶ **Undo** / ↷ **Redo** (100 steps; changes within 400 ms merge into one step);
- ⚙ saves the draft and opens the workspace's settings page;
- 🔒 **Lock layout**: drags and pinches stop moving or sizing what was there when edit mode opened (new controls stay free);
- **Done** saves (switches to the edited workspace); **Cancel** asks "Discard your changes?" if anything changed;
- **Select** (pick several controls, whole groups; a drag moves them all), **Group** / **Ungroup** (`ControlGroups`: grouped controls move and size together);
- **Configure** (the selected control's settings) and **Map** (its mapping editor);
- **+ Button**, **+ Empty button** (every inherited input cleared), **+ Stick**, and one entry per **gyro display** kind; items can also be dragged out of the menu onto the pad;
- **Snap: on/off** (edges on a 2 % grid);
- **Devices: <preset>**: which device preset this workspace pins ("None" leaves attached devices doing nothing);
- **Library**: buttons saved to the button library, to add again (long-press to remove, asks first).

New buttons start at 14 % of the short side, sticks 30 %.

## Mapping editor

`ui/ControlMappingScreen.kt`, full screen, for a touch control, the touchpad (the "Workspace" stand-in), the Defaults' mappings, or a physical device control. Its action **cards** (one per input, with their targets as chips) and a **library of targets** share the screen with a draggable divider. Tap a card, then a target to add it; or long-press and drag a target onto a card, a chip to another card, or back to the library to remove it. Cards reorder by their ⠿ handle. A chip opens its options (`ui/MappingChipDialog.kt`, `ui/ActionSettings.kt`): When, How, Where, Also ends on, delays, Cycle, scale, haptic feel. **Presets** fills in Windows touchpad or Gyro mouse. Layer chips on top pick Base or a layer. Inherited mappings show faded until the card maps anything itself; a faded chip dragged onto the library is removed (the card then overrides them); ✕, ↺, Reset all and Clear all work as in [Workspaces](phone-workspaces.md). **Settings** opens the control's settings (`ui/ControlSettingsScreen.kt`: Button (duplicate, save to library, delete), look (label, shape, size, layer, opacity), layers (follow layers), then the settings its actions share: touch surface, stick, gyro while held). Every change can be undone.

## What the pad shows

App-wide, Settings › App › Touch display (`ui/TouchVisuals.kt`), all off by default except where noted:

- **Show touches**: a dot under each finger that fades after it lifts;
- **Trail behind fingers**: a line of the finger's recent path, its end fading;
- **Show live mappings on buttons**: a button's label briefly shows what it sends (yellow, lingers 700 ms);
- **Swipe line**: a finger on a touchpad button shows a line from its start to its direction's activation lengths;
- **Move rulers**: a finger whose Move X or Y moves shows a sliding ruler per Move.

A layer in use other than Base shows a pill for a second.

## Gyro displays

Touch controls of kind DISPLAY (`core/.../model/GyroDisplay.kt`, drawn by `ui/GyroVisuals.kt` in plain 2D, no 3D library), placed in edit mode, each with its own settings (`ui/GyroVisualsSettings.kt`: what it shows, level angle, colours, ghost trail):

- **3D phone** (the phone turned by the gyro);
- **Ratchet ring** (yaw/pitch/roll arcs, lit while that axis moves the mouse, dim while ratcheted);
- **Speed graph** (scrolling yaw/pitch/roll speed, ratchets shaded);
- **Attitude indicator** (aircraft horizon with heading tape);
- **Bubble disk** (heading disk with a tilt dot);
- **3D shape** (Globe, Icosphere, Cube, Tetrahedron, Dodecahedron, Torus, Gimbal rings, Paper plane; a trackball one can show exact RWS).

Displays that hold their pose to gravity run the accelerometer; ones that hold heading to north run the magnetometer. The **parallax edge** (Settings › App › Gyro display) draws outlines inside the screen edge that slide as the phone turns (Turn) or shift while tilted (Tilt).

## Debug panel

With Settings › App › Debugging › **Debug tools** on (default off: nothing is logged), `ui/DebugOverlay.kt` floats over the pad: link, gyro, workspace and layer, what is held (inputs and outputs) and the newest events. Drag the title to move it, ◢ to resize, ▾ to fold it. **Record** saves the last 30 s (`runtime/PhoneDebug.kt`).

## Black screen

See [Volume rocker and black screen](phone-volume-black-screen.md).

## Key files

`ui/MainActivity.kt`, `ui/PadMenu.kt`, `ui/PadScreen.kt`, `ui/CapsuleRow.kt`, `ui/WorkspaceBar.kt`, `ui/ControlMappingScreen.kt`, `ui/BindingEditor.kt`, `ui/MappingChipDialog.kt`, `ui/ActionSettings.kt`, `ui/ControlSettingsScreen.kt`, `ui/DirectionSettings.kt`, `ui/TouchVisuals.kt`, `ui/GyroVisuals.kt`, `ui/GyroVisualsSettings.kt`, `ui/Shapes3D.kt`, `ui/DebugOverlay.kt`, `core/.../model/Profile.kt` (`TouchControl`, `TouchKind`, `NavCorners`), `core/.../model/ControlGroups.kt`, `core/.../model/GyroDisplay.kt`.
