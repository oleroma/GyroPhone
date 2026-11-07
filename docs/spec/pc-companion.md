# PC: companion app and tray

The Windows companion (`GyroPhoneCompanion.exe`) receives the phone's Wi-Fi/USB link and turns it into a virtual game pad, keyboard and mouse, and draws the overlays. Bluetooth needs no companion. Projects in `companion/src/`:

| Project | What it is |
|---|---|
| `GyroPhone.Protocol` | Wire format, crypto, STATE to pad and SendInput mapping, gyro math port. Cross-platform, unit tested. |
| `GyroPhone.Host` | The protocol engine (`HostEngine`), UDP server, sessions, pairing, settings, key files, link stats, overlay pose. Cross-platform; all I/O behind interfaces in `Abstractions/`. |
| `GyroPhone.Windows` | Windows sinks and probes: SendInput, ViGEm, XInput PC pad, AOA USB, firewall, DPAPI, autostart, MMCSS, power QoS, logs. |
| `GyroPhone.Tray` | The exe: tray icon and menu, overlays, watcher, updater, Debug window. WinForms + Direct2D (Vortice). |
| `GyroPhone.Ui` | The WPF main window and pairing window, reached through `ICompanion`. |
| `GyroPhone.Headless` | A console host for Linux/testing (`--pair`, `--no-vigem`, `--latency-csv`). Not shipped. |

.NET 8, published self-contained, single file, win-x64.

## Threads and timing

- **gp-rx** (`UdpHostServer`): the one UDP socket on port **47800**. Blocks in ReceiveFrom, drains the backlog into `HostEngine.OnDatagram`, then `Flush` injects once, so a burst of STATEs makes one injection. A STATE whose keys or buttons differ from the one before it injects that earlier one first, so no press is lost; a pad button pressed and released inside one burst stays visible for 20 ms for polling games. Registered with MMCSS ("Games", high) and opted out of power throttling (`Mmcss`, `PowerQos`). No timer resolution change.
- **Watchdog** (`Watchdog`): a 50 ms tick for `HostEngine.OnTick`: releases everything after **250 ms** without a STATE, drops a session after 10 s, expires pending sessions (2 s), runs pairing timers and sends HOST_STATUS once a second. Environment probes (1 Hz, on the thread pool), the held-keys marker and settings writes run here, never on gp-rx.
- **gp-usb-rx / gp-usb-tx** (`UsbBridge`) for the direct cable.
- **Overlay pacer** and the UI thread ([Overlays](pc-overlays.md)).

All engine state is under one lock. Engine callbacks only post to the UI thread.

## Sessions and safety

- One **active session**; a new one replaces it only when its first valid STATE arrives (`SessionManager`), and the old phone gets HOST_BYE REPLACED.
- **Release-all** on silence (250 ms), session end, Pause, workstation lock or suspend, exit, and session switch.
- **Crash safety** (`HeldKeysMarker`): while anything is held, `%LOCALAPPDATA%\GyroPhone\held.json` lists it; the next start releases what it lists and deletes it.
- **UIPI**: when an elevated window is in front or the secure desktop is up, SendInput drops events; the engine notices, and once input goes through again re-sends the ups and then the downs of what is held, so Windows' key state matches. Home warns, with **Restart as administrator**. The companion only checks its own token, never opens other processes (anti-cheat logs that).
- **Pause input** (tray, Gamepad page): releases everything; STATEs keep updating the baseline but inject nothing.

## Tray

`TrayApp`. Left click opens the main window (App page: or pairs a new phone). The right-click menu is short, the everyday switches only:

- the version, a status line, and a problems line that opens Home;
- **Open GyroPhone**, **Pause input**;
- **Gamepad output** ▸ Xbox 360 (default) / DualShock 4 / Off (keyboard and mouse only);
- **Overlays** ▸ one switch per window, Hide all overlays (Ctrl+Alt+O), Edit: drag the windows to move and resize (Ctrl+Alt+E), Level all: as held now shows face-on (Ctrl+Alt+R), Overlay settings…;
- **Pair new phone…**;
- **Exit**.

## Main window

`GyroPhone.Ui/MainWindow.cs`, a sidebar of pages, refreshed twice a second:

- **Home**: a warning bar per problem with its fix (no ViGEmBus, firewall, Public network, input blocked by an elevated window…), then cards: phone, link, latency (with a graph and the **Measure buttons** switch, see [Latency test](pc-latency-test.md)), gamepad (output, pause); full status under Details.
- **Phones**: Pair new phone, paired phones (each with Forget), this PC's key.
- **Gamepad**: output Xbox 360 / DualShock 4 / Off, Pause, the ViGEmBus driver state and Retry.
- **Overlays**: see [Overlays](pc-overlays.md).
- **Connection**: network category, firewall and USB checks with their fixes, the USB cable (direct cable on/off, default on), the server's port and addresses.
- **Latency test**: times the gyro from a slow-motion video, see [Latency test](pc-latency-test.md).
- **App**: Startup (Start with Windows, default off; **Start when my phone appears**, the watcher), Tray icon click (Opens this window / Pairs a new phone), Troubleshooting (Open log folder, Debug window…), About (version).

**Pairing window** (`PairWindow`): a QR code for the phone's Scan QR code (a one-time token, renewed when it expires, gone when the window closes; holds up to four of this PC's IPv4 addresses), and for code pairing the PC's name, addresses, port and the pairing countdown, then "Pair <phone>? Code 123 456" with **Allow** / **Deny**. On first run with nothing paired the pairing window opens by itself.

**Debug window** (`DebugForm`, from the App page): the link as the PC sees it, what the game is given now, and the newest debug events at 4 Hz; **Save last 30 s**. The engine logs only while it is open or the phone's debug tools are on. A phone-side Record also saves the PC's (LINK_INFO save counter).

**Input log** (`InputRecorder`, always on, no switch): `%LOCALAPPDATA%\GyroPhone\logs\input.log` (5 MB, then `input.log.old`), for finding stutter after real play. Each line has the wall-clock time, then seconds since the session started. While a session is up there is one `sec` line a second (packets, moving packets, lost, late, the latest packet's extra delay, latency p50/p99 and jitter once the phone sent a clock offset, injections, pixels moved, biggest step, slowest receive-to-SendInput), and one line per event, written when it happens:
- `lost`: 3 or more packets missing from the seq.
- `late`: packets more than 15 ms later than the session's fastest ones of the last 5-10 s (receive time minus phone send time, so no clock sync is needed), one line per stretch; "held up on the way" when the phone sent them on time.
- `phone gap`: the phone sent nothing for over 30 ms (its keepalive is 20 ms) with nothing lost, while input was moving or held.
- `glide`: late or lost motion spread out by `MouseCatchUp` instead of landing at once.
- `big move`: 64 px or more in one SendInput.
- `slow hand-off`: over 2 ms from receive to SendInput.
- `refused`: Windows took fewer events than given.
- `release`: everything let go after 250 ms of silence.
- `session start` / `session end` (with the session's totals and extra-delay percentiles).
Like every log it counts keys and buttons but never names them.

## Watcher

`GyroPhoneCompanion.exe --watch` (`Watcher`): no window, no tray. While the companion is off it holds UDP 47800; the first DISCOVER from a known phone (by phone id or key id) makes it free the port and start the installed companion, at most once per gap. A companion started any other way asks the watcher to yield the port. Started at login by the `GyroPhoneWatcher` Run entry; on and off with **Start when my phone appears** on the App page. DISCOVER is not authenticated, so a spoofer can only start the companion; connecting still needs the key.

## Settings and files

| Path | What |
|---|---|
| `%APPDATA%\GyroPhone\settings.json` | `HostSettings`: host id and name, output mode, Start with Windows, tray click, USB direct, code-paired phones (pairing keys wrapped with DPAPI), overlay windows and shortcuts. Written atomically off the rx thread. |
| `%APPDATA%\GyroPhone\host.key`, `host.pub` | This PC's static P-256 key (the private scalar wrapped with DPAPI). |
| `%APPDATA%\GyroPhone\authorized_phones\*.pub` | Trusted phone keys; watched, so a new file pairs at once and a removed one drops the phone (live session included). |
| `%LOCALAPPDATA%\GyroPhone\logs` | Append-only logs (counters and lifecycle only, never keys): `companion.log` and the input log `input.log` (above). |
| `%LOCALAPPDATA%\GyroPhone\held.json` | Crash marker (above). |
| `%LOCALAPPDATA%\Programs\GyroPhone\` | The installed exe ([Install and update](pc-install-update.md)). |

## Key files

`GyroPhone.Host/HostEngine.cs`, `SessionManager.cs`, `UdpHostServer.cs`, `Watchdog.cs`, `PairingManager.cs`, `KeyFiles.cs`, `HeldKeysMarker.cs`, `LinkStats.cs`, `PhoneWatch.cs`, `Settings/HostSettings.cs`, `GyroPhone.Tray/Program.cs`, `TrayApp.cs`, `Watcher.cs`, `DebugForm.cs`, `GyroPhone.Ui/MainWindow.cs`, `HomePage.cs`, `SettingsPages.cs`, `PairWindow.cs`.
