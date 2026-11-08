# PC: overlays

Windows on the PC that show the phone's motion over a game or in a stream.

## Windows and kinds

Any number of overlay windows (`OverlaySettings`, Overlays page › Add), each one of:

- **Phone**: the phone in 3D with the workspace's layout, presses and fingers on its screen; optional Switch-style **controller handles** on its sides (narrow, sticks and buttons centred, −/+/Home next to the phone edge, bumper/trigger pills on the front, presses shown visually, no labels). Handles: **While a controller is attached** (default) / Always / Off; **Controller**: Phone (default) or PC (the PC's controller, only while the phone's PC ratchet switch is on).
- **3D shape**: Globe (default), Icosphere (subdivision 0–3), Cube, Tetrahedron, Dodecahedron, Torus, Gimbal rings, Paper plane, Camera (seen from behind, lens pointing where it looks); smooth edges, hidden edges, filled faces, equator, screen and top marks.
- **Attitude indicator**: horizon with a heading tape.
- **Bubble disk**: heading disk with a tilt dot (range 30°), crosshair.

The phone overlay is only drawn on the PC; the phone sends its raw motion, layout, touches and presses (OVERLAY, OVERLAY_LAYOUT) only while some overlay is shown (HOST_STATUS flags tell it). The PC makes the pose itself (`GyroPhone.Host/OverlayPose.cs`, a port of the phone's `GyroPose`).

## Per-window settings

Overlays page: the switches for all (Edit, Hide all, Level all), the list of windows (Show each, Add, shortcuts), and the picked window's settings in three tabs.

- **Window**: place and size (default 420 × 300), opacity (0.1–1), background **Transparent** or **Green** (for an OBS chroma key), **Quality** High (default) / Balanced / Fast, **Frame rate** screen refresh / 60 (default) / 30.
- **Look**: kind-specific options above, colours (one swatch per colour; Windows colour picker), finger dots (off) and trails (on).
- **Turning**: **Turns with**: **Phone** (default: how the phone is held), **Camera** (where the game's camera points: the live gyro axes and every other camera move, touchpad, buttons, controllers, keys, glides; a ratchet stops it as it stops the camera) or **Trackball** (as Camera but rolling the other way, held still against the world while the gyro aims); Camera and Trackball turn as far as the game's camera (when its turn is known) or Spin 0.1–10× times the phone, have no Gravity, Compass or Turn amount, and Level sets them straight; a Trackball never eases back (every roll is a camera move). **Level** (the way the phone is held now shows face-on; with Gravity only the tilt is kept), **Gravity** (tilt held to gravity, default on), **Compass** (heading held by the magnetometer, off), **Ease** back to straight (on): after 300 ms still, over 2 s or at a speed (90 °/s); **Ease on ratchet** (on: while a ratchet is held it eases back until straight, instead of whenever the phone stops); heading only (without gravity). **Amount**: **Turn amount** (0.5–3×, not on a trackball, which turns by Spin) and **Level**.

The phone's own gyro displays use the same Look and Turning settings, names and defaults (see [Pad UI](phone-pad-ui.md#gyro-displays)); their values are separate.

**Shortcuts** (global, `Hotkeys`, a hidden message window so they work with no overlay open; reconfigurable, one press never does two things): **Ctrl+Alt+R** Level all, **Ctrl+Alt+O** Hide all, **Ctrl+Alt+E** Edit. A **Level displays** mapping on the phone levels every overlay shown too (OVERLAY byte 139 counts its presses; a change levels, the first count seen in a session levels nothing).

## Edit and click-through

Overlay windows (`OverlayForm`) are borderless, always on top, per-pixel-alpha layered windows that never take focus. The mouse passes through them, except in **Edit** (one switch for all): then they can be dragged and resized, and a click without a drag picks a window, cycling through overlapping ones. Each has an owner window rather than the tool-window style, so **OBS Window Capture** can record it, over games running windowed or borderless.

## Drawing

- **GPU**: one shared Direct3D 11 / Direct2D / DirectWrite device (`D2DCanvas`), presented without a CPU copy; a lost device (sleep, driver update) is replaced. **CPU fallback** (GDI) when Direct2D can't start.
- **Pacer** (`OverlayPacer`): a background thread waits for each compositor refresh (DwmFlush), spaced by at least the refresh period, and draws there, never on the UI thread. It sleeps while no overlay is open. Each window draws at most at its frame rate.
- Draw times are logged every 30 s. In game the overlays cost about a fifth of one CPU core.

## Key files

`GyroPhone.Tray/OverlayForm.cs`, `OverlayRenderer.cs`, `OverlayPacer.cs`, `D2DCanvas.cs`, `Canvas.cs`, `ShapeDrawing.cs`, `HandleDrawing.cs`, `DialDrawing.cs`, `Meshes.cs`, `FingerTrails.cs`, `Hotkeys.cs`, `GyroPhone.Host/OverlayPose.cs`, `Settings/OverlaySettings.cs`, `GyroPhone.Protocol/Overlay.cs`, `GyroPhone.Ui/OverlaysPage.cs`, phone side `core/.../net/OverlayFeed.kt`.
