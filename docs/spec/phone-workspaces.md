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

- **Controller**: a touchscreen game pad (sticks, ABXY, D-pad, bumpers, triggers, L3/R3, View, Menu) plus a "Gyro" button mapped to Ratchet; gyro mode Ratchet enables. Its pad mappings became a device preset it pins.
- **WASD**: a WASD stick and keys for keyboard-and-mouse games; gyro mode Ratchet disables.
- **Keyboard**: a full keyboard (compact keyboard layout, plus Del, Home, End, PgUp and PgDn) under a touchpad strip; gyro Ratchet enables.

**Adding** a workspace ("+" capsule, added at the front) offers **Blank**, **Windows touchpad** and these presets. Its last choice, **From a file…**, imports a workspace someone exported (added last, under a free name such as "WASD 2", never replacing one). A blank workspace starts with no mappings and no layers of its own (it follows the Defaults' mappings); Windows touchpad is a blank one whose touchpad works like a Windows one (`BuiltinProfiles.touchpadWorkspace`). **Rename, Duplicate, Delete** (not the last), **Copy mappings from…** and **Export** are fixed at the top of its settings, outside the scrolling list; **Copy mappings from…** (`ui/CopyMappingsDialog.kt`) brings another workspace's mappings in, either with its buttons (replacing this one's) or mappings only (matched to this workspace's buttons by name; buttons with no match are listed first).

**Export and import** (`core/.../model/ProfileExport.kt`, `ui/ProfileFiles.kt`): a file `<name>.gyrophone.json`, saved with the system's Save picker (phone storage, Drive...). It is the stored profile JSON under a header (`format` "gyrophone", `version`, `kind`); older and newer builds read each other's files, leaving out mappings they don't know (the import says how many). **Export** (workspace settings) writes one workspace as saved, its pinned device preset and the Defaults it was made under. Importing it keeps how it worked: where it followed a Default that differs here, it takes the old value as its own (a mapping it did not have is cleared); where the Defaults agree it keeps following them, so no override shows that changes nothing. Its device preset is added beside the ones here. **Export everything / Import everything…** (App page, Backup) carry every workspace, the Defaults, device presets, the button library and mapping presets; the import asks first, replaces the workspaces, Defaults and device presets (the old set is kept as a backup file, `<profile>.import-<time>.bak`), and adds the file's library buttons and presets beside yours. PC overlay settings are not in either file.

## Settings inheritance

Every workspace setting has a **key** (`core/.../model/WorkspaceDefaults.kt`):

- top level: Tap time, control opacity, haptic feel, haptic edge;
- `gyro.<property>`: every `GyroSettings` field (mode, sensitivity, per-axis values, inertia, acceleration, tilt, shake, game turn, gyro space…);
- `touchpad.<field>`: every shared touch surface setting (`SurfaceField`), each direction's own (`touchpad.dir.<D>.<field>`) and each Move X/Y's own (`touchpad.move.<slot>.<field>`).

The chain is **built-in values → Defaults → workspace → layer**:

- `Workspace.ownSettings` names the keys a workspace sets itself; every other key takes the Defaults' value when the set is resolved (`WorkspaceDefaults.apply`). An edit marks the keys it changed as the workspace's own (`markOwn`). **↺** on a setting follows the parent again; **Reset all** follows it for everything (it asks first). Under a setting, a pink line lists the children (workspaces, layers, buttons) that set it themselves, with their values, and **Reset children** makes them follow again ([Settings](phone-settings.md#defaults-tab)).
- A level that sets a shared value hides the more specific values above it: a shared touchpad value hides each direction's and Move's own; a shared gyro sensitivity hides each axis's; an exact sensitivity hides a factor above it. Resetting the shared value brings them back.
- A **layer** (`MappingSet.settings`) sets keys of its own over the workspace's while it is in use (`WorkspaceDefaults.layered`, applied by the engine as `eff`). A layer may set every key but the control opacity, the game's turn and the gyro space (`layerKeys`). The Defaults have no layers. The workspace settings page has chips on top to pick Base or a layer.
- A **button** has its own fields over the workspace's: its long-press and double-tap times, its touch surface settings (`SurfaceSettings`, null = the workspace touchpad's), and its gyro and touchpad speed while held, per layer (`ButtonGyro`). A held button's settings win while a finger is on it; of several, the last pressed.

Settings that are not keys are the workspace's alone: its layout, mappings, layers and pinned preset. App-wide options (the App page) are outside the chain ([Settings](phone-settings.md)).

## Mapping inheritance

`core/.../model/Inheritance.kt`. Mappings pass down **chip by chip**: a chip is one mapping, an input (finger count, surface input, trigger, zone, a button's Area and Outside) and its target. A level's own chips never hide the parent's other chips on the same card: both work side by side. A parent's chip is left out only where the level maps the same chip itself (same input, same target), or blocks it with an **Off** chip (`OutputTarget.Off`, shown as "Nothing (Left)"): an Off blocks exactly the one inherited chip it names, so blocking a tap and a double tap takes two. The old card-wide **Nothing** (`NoOutput`, from + Empty button and older setups) still clears a whole action.

- **Defaults → workspace**: the Defaults' touchpad, gyro, tilt and shake chips fill the Base of each workspace (`Inheritance.fill`). The Defaults map nothing until the person maps something there (Settings › Defaults › Mappings, with the presets Windows touchpad and Gyro mouse).
- **Workspace touchpad → buttons** (see-through buttons): each touch button takes the touchpad's chips (in the layers in use) it doesn't block (`toButtons`, `passedOn`). The button doesn't run them: a finger on it is passed straight to the touchpad, which runs them as if the finger were on the background, and the button's own chips run as well. A button with no own chips never shows pressed: it is a window onto the touchpad. A button that blocks everything it would take keeps its fingers. A button finger and a background finger never make one multi-finger gesture: the later one only aims (moves). While the touchpad runs a button's finger, that button's Off chips also hold back the touchpad's matching chips.
- **Workspace gyro → held buttons**: while a button is held, each gyro axis does the workspace's mapping unless the button maps that axis itself (`gyroInherited`); this stays whole-axis. Physical buttons with a Motion tab work the same way ([Controllers](phone-controllers.md)).
- **Tidying**: an Off whose own chip is mapped at the same level is dropped (`tidy`).
- **Older setups**: setups saved before chip-by-chip inheritance are converted once on load (`Inheritance.chipByChip`, `Workspace.chipInherit`): wherever a card used to hide all the parent's chips, each such chip gets an Off, so they work as before. Importing a profile with other Defaults does the same against the file's Defaults.

In the mapping editor (Base only) inherited chips show **faded beside the card's own chips**. **Tap a faded chip**, or long-press and **drag it out of its card** (onto the library; both hidden actions), and a popup asks: **Override** puts an Off for that one chip (it stops working here), **Replace** makes an own copy of it. Changing a copy (say its When from Tap to Long press) makes it a different chip, so the inherited one shows faded again. A faded chip dragged onto another card is mapped there as an own chip. **✕** on a card puts an Off on every faded chip of it and removes its own chips, **↺** follows the parent again (drops the card's own chips and Offs), **Reset all** (asks first) follows everything, **✕ Clear all** clears everything (no question; undo brings it back). **+ Button** adds a button that follows the workspace; **+ Empty button** adds one with every inherited input cleared (`Inheritance.cleared`).

## Resolving for the engine

A set is kept in two forms (`WorkspaceMappings`):

- **stored** (`store`): workspaces hold no device mappings, only the id of the preset they pin;
- **resolved** (`resolve`): each workspace gets its Defaults settings applied and holds its pinned preset's mappings as one pad for any game pad (`pads`), `keyboard`, `mouse` and `phone` (the volume rocker, with an unmapped rocker key turning the PC's volume), plus the preset's key timings, stick and trigger tuning and held-button settings.

`GamepadRuntime.activeProfile` is the resolved set the engine and editors see; `editActiveProfile` applies an edit and saves the stored form in the background (`WorkspaceMappings.settle`: a pad mapping edit becomes the pinned preset's; a new workspace is kept as it comes, a blank one blank). Resolving again reuses each workspace resolved last time whose stored form, Defaults and device presets didn't change (`resolveStored`, `WorkspaceMappings.resolve(set, last, lastResolved)`), and `markOwn` skips a workspace the edit left as the same object or whose settings all stayed equal (a control moved in edit mode), so a settings tap works out only what it touched.

## Storage

All on the phone, in the app's private storage (`data/ProfileStore.kt`):

- `files/profiles/<id>.json`: the workspace set, read with `ProfileJson.decode` and written atomically (`.tmp` then replace). At start only the set's own file is read (`ProfileStore.readOwnFile`); every profile file is read only for the one-time merge, or when the set's file is missing, unreadable or has a second copy. The runtime that reads it is built on a background thread from `GyroPhoneApp.onCreate`, so it overlaps the activity's start; the pad waits only for what is left. A mapping a build can't read (a newer build's action) is left out (`decodeLenient`) and the file is first copied to `<file>.newer-build.bak` before it is saved over. A file that fails to decode is skipped but never deleted or overwritten.
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
