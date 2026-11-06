# Phone: workspaces and inheritance

How the phone's configuration is organised, how settings and mappings pass down from the Defaults to workspaces, layers and buttons, and how it is stored.

## One profile, many workspaces

There are no game profiles to pick and no automatic switching. The app keeps **one** `Profile` (`core/.../model/Profile.kt`, id `user.workspaces`, `core/.../model/WorkspaceSet.kt`) holding:

- **workspaces**: an ordered list, never empty. The pad shows one at a time.
- **defaults**: the Defaults, a `Workspace` whose settings and touchpad/motion mappings every workspace uses unless it sets its own.
- **devicePresets**: what physical devices do ([Controllers](phone-controllers.md)); each workspace pins one.
- **corners**: the ← / → nav corners shared by every workspace, their opacity (default 50 %) and "same size" (default on).
- **wrapWorkspaces**: → on the last workspace goes to the first (default off).
- **gamePresets**: the person's own game turn presets, beside the built-in ones.

A **workspace** (`core/.../model/Workspace.kt`) is one complete setup: its touch controls and their mappings, the touchpad, gyro, tilt and shake settings, control opacity, haptic feel and edge, gesture timings, its layers (`sets`), and the device preset it pins. The screen and the attached devices work at the same time and feed the same PC outputs. Nothing is shared between workspaces, so a duplicate can change freely (`core/.../model/WorkspaceMappings.kt`).

**Switching**: tap a workspace capsule on the pad, or the ← / → corners. Switching releases everything held, goes back to Base, and the gyro follows the new workspace; a Toggle gyro press stops counting. The last active workspace is remembered.

**A fresh install** (`BuiltinProfiles.default`) has three workspaces, each with the Windows touchpad and Gyro mouse presets filled in where it maps nothing else:

- **Controller**: a touchscreen game pad (sticks, ABXY, D-pad, bumpers, triggers, View, Menu) plus a "Gyro" button mapped to Ratchet; gyro mode Ratchet enables. Its pad mappings became a device preset it pins.
- **WASD**: a WASD stick and keys for keyboard-and-mouse games; gyro mode Ratchet disables.
- **Keyboard**: a full keyboard (K380 layout) under a touchpad strip; gyro Ratchet enables.

**Adding** a workspace ("+" capsule, added at the front) offers **Blank**, **Windows touchpad** and these presets. A blank workspace starts with no mappings and no layers of its own (it follows the Defaults' mappings); Windows touchpad is a blank one whose touchpad works like a Windows one (`BuiltinProfiles.touchpadWorkspace`). **Rename, Duplicate, Delete** (not the last) are fixed at the top of its settings, outside the scrolling list; **Copy mappings from…** (`ui/CopyMappingsDialog.kt`) brings another workspace's mappings in, either with its buttons (replacing this one's) or mappings only (matched to this workspace's buttons by name; buttons with no match are listed first).

## Settings inheritance

Every workspace setting has a **key** (`core/.../model/WorkspaceDefaults.kt`):

- top level: long-press time, double-tap window, control opacity, haptic feel, haptic edge;
- `gyro.<property>`: every `GyroSettings` field (mode, sensitivity, per-axis values, inertia, acceleration, tilt, shake, game turn, gyro space…);
- `touchpad.<field>`: every shared touch surface setting (`SurfaceField`), each direction's own (`touchpad.dir.<D>.<field>`) and each Move X/Y's own (`touchpad.move.<slot>.<field>`).

The chain is **built-in values → Defaults → workspace → layer**:

- `Workspace.ownSettings` names the keys a workspace sets itself; every other key takes the Defaults' value when the set is resolved (`WorkspaceDefaults.apply`). An edit marks the keys it changed as the workspace's own (`markOwn`). **↺** on a setting follows the parent again; **Reset all** follows it for everything (it asks first).
- A level that sets a shared value hides the more specific values above it: a shared touchpad value hides each direction's and Move's own; a shared gyro sensitivity hides each axis's; an exact sensitivity hides a factor above it. Resetting the shared value brings them back.
- A **layer** (`MappingSet.settings`) sets keys of its own over the workspace's while it is in use (`WorkspaceDefaults.layered`, applied by the engine as `eff`). A layer may set every key but the control opacity, the game's turn and the gyro space (`layerKeys`). The Defaults have no layers. The workspace settings page has chips on top to pick Base or a layer.
- A **button** has its own fields over the workspace's: its long-press and double-tap times, its touch surface settings (`SurfaceSettings`, null = the workspace touchpad's), and its gyro and touchpad speed while held, per layer (`ButtonGyro`). A held button's settings win while a finger is on it; of several, the last pressed.

Settings that are not keys are the workspace's alone: its layout, mappings, layers and pinned preset. App-wide options (Settings › App) are outside the chain ([Settings](phone-settings.md)).

## Mapping inheritance

`core/.../model/Inheritance.kt`. Mappings pass down **one action at a time** (one card of the mapping editor, `Inheritance.cardOf`), not in groups. An action is a finger count and a held surface input, whoever's surface it is; its triggers (tap, long press, quick swipe…), length zones and a button's Area and Outside all belong to it. A button's one-finger On touch is its press. Mapping **anything** on an action overrides **all** of what it inherits: none of the parent's mappings of it are left.

- **Defaults → workspace**: the Defaults' touchpad, gyro, tilt and shake mappings fill the Base of each workspace for every such action the workspace maps nothing on in Base itself (`Inheritance.fill`). The Defaults map nothing until the person maps something there (Settings › Defaults › Mappings, with the presets Windows touchpad and Gyro mouse).
- **Workspace touchpad → buttons**: each touch button takes the touchpad's mapping (in the layers in use) of every surface input it maps itself in none of them (`toButtons`). So a button's own mapping beats the workspace's in every layer; a button that maps an input only in a layer not in use takes the touchpad's meanwhile.
- **Workspace gyro → held buttons**: while a button is held, each gyro axis does the workspace's mapping unless the button maps that axis itself (`gyroInherited`). Physical buttons with a Motion tab work the same way ([Controllers](phone-controllers.md)).
- **Clearing**: mapping an action to Nothing (`NoOutput`, on its Hold) at a level clears what it would inherit. Mapping something else on that action drops the Nothing again (`tidy`).

In the mapping editor inherited mappings show faded, and only on cards that map nothing themselves: mapping something on a card makes its faded chips go. A faded chip can be **removed**: long-press it and drag it onto the library (a hidden action: nothing on screen points to it). The card then overrides the parent: it maps the parent's other chips itself, or is cleared (Nothing) when none is left. Dragged onto a card instead, it is mapped there and its own card overrides the parent the same way. **✕** clears one (Nothing), **↺** follows the parent again, **Reset all** (asks first) follows everything, **✕ Clear all** clears everything (no question; undo brings it back). **+ Button** adds a button that follows the workspace; **+ Empty button** adds one with every inherited input cleared (`Inheritance.cleared`).

## Resolving for the engine

A set is kept in two forms (`WorkspaceMappings`):

- **stored** (`store`): workspaces hold no device mappings, only the id of the preset they pin;
- **resolved** (`resolve`): each workspace gets its Defaults settings applied and holds its pinned preset's mappings as one pad for any game pad (`pads`), `keyboard`, `mouse` and `phone` (the volume rocker, with an unmapped rocker key turning the PC's volume), plus the preset's key timings, stick and trigger tuning and held-button settings.

`GamepadRuntime.activeProfile` is the resolved set the engine and editors see; `editActiveProfile` applies an edit and saves the stored form in the background (`WorkspaceMappings.settle`: a pad mapping edit becomes the pinned preset's; a new workspace is kept as it comes, a blank one blank).

## Storage

All on the phone, in the app's private storage (`data/ProfileStore.kt`):

- `files/profiles/<id>.json`: the workspace set, read with `ProfileJson.decode` and written atomically (`.tmp` then replace). A mapping a build can't read (a newer build's action) is left out (`decodeLenient`) and the file is first copied to `<file>.newer-build.bak` before it is saved over. A file that fails to decode is skipped but never deleted or overwritten.
- `files/button_library.json`: the button library (buttons saved with their look and mappings, shared by every workspace).
- Shared preferences: app-wide options, the active workspace, capsule order, UI state (open sections, card order, recent targets), the gyro bias.
- The Wi-Fi pairing store and the key folders ([Connection](phone-connection.md)).

The app has `allowBackup="false"`.

## Migrations

Old configs load without losing anything set by hand; each migration keeps a backup of the set as it was beside it.

- **Before workspaces** (`ProfileJson`): a file with no `workspaces` becomes one workspace holding its layout, touchpad, gyro and opacity; its controller mappings become an attached pad; its fullscreen corners the ← / → corners.
- **Several profiles** (`WorkspaceSet.merge`): merged once into one set, the active profile's workspaces first, the others' named "<workspace> · <profile>". The old files stay on disk untouched.
- **Mapping profiles, then workspace-owned mappings** (`MappingProfiles.migrate`, `WorkspaceMappings.fromProfiles`): each workspace gets a copy of the mapping profile it used.
- **Mapping triggers** (`TriggerReset`, build of 2026-10-31): a one-time reset to a fresh install's config, keeping each workspace's name and layout and the nav corners.
- **Small upgrades on load**: old button switches into tap and long press mappings (`ButtonActivators`), old key modes and latches into Toggle mappings (`ToggleUpgrade`), the Press trigger into On touch + Once (`Triggers.upgrade`), old swipe sources into surface inputs (`Bindings.upgradeSwipes`), the old gyro corner into a "G" Toggle gyro button in every workspace (`NavCorners.gyroCornerToButtons`), `HOLD_OR_TOGGLE` into Ratchet enables, old editor stand-ins in device presets (`DevicePresets.fixStandIns`).

## Key files

`core/.../model/Profile.kt`, `Workspace.kt`, `WorkspaceSet.kt`, `WorkspaceDefaults.kt`, `Inheritance.kt`, `WorkspaceMappings.kt`, `WorkspaceEdits.kt`, `BuiltinProfiles.kt`, `ButtonLibrary.kt`, `ProfileJson.kt`, `MappingProfile.kt`, `TriggerReset.kt`, `ButtonActivators.kt`, `ToggleUpgrade.kt`, `NavCorners.kt`, `app/.../data/ProfileStore.kt`, `app/.../runtime/GamepadRuntime.kt` (`loadWorkspaceSet`, `loadOwnMappings`, `editActiveProfile`, `selectWorkspace`), `app/.../ui/PadMenu.kt` (`WorkspaceSettingsPage`), `WorkspaceSettingsList.kt`, `OverrideSettings.kt`, `CopyMappingsDialog.kt`, `WorkspaceBar.kt`.
