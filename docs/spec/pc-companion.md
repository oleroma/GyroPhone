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

- **gp-rx** (`UdpHostServer`): the one UDP socket on port **47800**. Blocks in ReceiveFrom, drains the backlog into `HostEngine.OnDatagram`, then `Flush` injects once, so a burst of STATEs makes one injection. A STATE whose keys or buttons differ from the one before it injects that earlier one first, so no press is lost; a pad button pressed and released inside one burst stays visible for 20 ms for polling games. Registered with MMCSS ("Games", high) and opted out of power throttling (`Mmcss`, `PowerQos`). No timer resolution change. While a lost or late stretch of mouse motion catches up it also wakes every millisecond without packets to move it on: on Windows by waiting on the socket's read event and a high-resolution waitable timer together (`ExactCatchUpWait`; WSAEventSelect for the wait only, the socket stays blocking otherwise), since a plain socket.Poll timeout only ends on the 15.6 ms system tick; a datagram still wakes it at once.
- **gp-watchdog** (`Watchdog`): its own thread at AboveNormal priority, ticking every 50 ms on a high-resolution waitable timer, for `HostEngine.OnTick`: releases everything after **250 ms** without a STATE, drops a session after 10 s, expires pending sessions (2 s), runs pairing timers and sends HOST_STATUS once a second. The tick does only in-memory work. Environment probes (1 Hz) go to the thread pool.
- **gp-save** (`Watchdog`): a BelowNormal writer thread that, every 500 ms, writes the held-keys marker if the held set changed and settings if anything changed (all changes since its last turn in one write; a failed write is retried after 1 s). A slow disk or a virus scan never holds up a tick, and never runs on gp-rx.
- **gp-usb-rx / gp-usb-tx** (`UsbBridge`) for the direct cable: every packet passes through them before gp-rx, so they get the same MMCSS ("Games", high) registration and power-throttling opt-out.
- **Overlay pacer** and the UI thread ([Overlays](pc-overlays.md)).
- **gp-log** (`FileLog`, Normal priority): writes the log files. `Write` only puts the line in a lock-free queue and wakes it, so no thread ever waits on it.

All engine state is under one lock, which gp-rx takes for every packet and Flush. So that a thread Windows pauses while holding it never keeps gp-rx waiting, everything read many times a second is read without it: when a locked call ends, the engine copies what changed into a published view (`OverlayView`, a sequence lock: readers copy and retry if a write came in between, the writer never waits), and the overlay windows (newest OVERLAY and layout, each window's pose, "Fade when phone rests", the Fire and Aim displays' outputs, Connected), the held-keys marker and the catch-up flag read only that. The PC controller's state comes from its reader thread's own published copy (`IPcPad.Peek`). An OVERLAY that comes in a burst with a STATE is published after the Flush, and the laser outputs after the hand-off, so publishing never delays input. `Snapshot` (tray and main window) only copies plain values under the lock (the paired phones list is kept ready), and settings are turned into JSON after the lock is let go (a change made meanwhile is saved on the next tick). The pad driver and SendInput calls stay under the lock, as they decide what the engine counts as held. Engine callbacks only post to the UI thread.

## Sessions and safety

- One **active session**; a new one replaces it only when its first valid STATE arrives (`SessionManager`), and the old phone gets HOST_BYE REPLACED.
- **Release-all** on silence (250 ms), session end, Pause, workstation lock or suspend, exit, and session switch.
- **Crash safety** (`HeldKeysMarker`): while anything is held, `%LOCALAPPDATA%\GyroPhone\held.json` lists it (written by gp-save within half a second of a change, and on exit); the next start releases what it lists and deletes it.
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

`GyroPhone.Ui/MainWindow.cs`, a sidebar of pages, refreshed twice a second. A page is built the first time it is shown (Home at once, for its latency graph), and the Latency test page reads its result files off the UI thread. A change made on a page (a slider step, a toggle) refreshes only that page at once; the tray and Home follow 250 ms after the changes stop, and an overlay draws the change at its next frame.

- **Home**: a warning bar per problem with its fix (no ViGEmBus, firewall, Public network, input blocked by an elevated window…), then cards: phone, link, latency (with a graph and the **Measure buttons** switch, see [Latency test](pc-latency-test.md)), gamepad (output, pause); full status under Details.
- **Phones**: Pair new phone, paired phones (each with Forget), this PC's key.
- **Gamepad**: output Xbox 360 / DualShock 4 / Off, Pause, the ViGEmBus driver state and Retry.
- **Overlays**: see [Overlays](pc-overlays.md).
- **Connection**: network category, firewall and USB checks with their fixes, the USB cable (direct cable on/off, default on), the server's port and addresses.
- **Latency test**: times the gyro from a slow-motion video, see [Latency test](pc-latency-test.md).
- **Performance**: the game's frames per second and jumps next to the phone link's trouble, and test records to compare ways of connecting, see [Performance](#performance).
- **App**: Startup (Start with Windows, default off; **Start when my phone appears**, the watcher), Tray icon click (Opens this window / Pairs a new phone), Mouse (**Catch-up time**: Off / 8 / 16 / 32 ms, default 16, `HostSettings.CatchUpMs`; the longest a lost or late stretch of mouse motion is spread over, Off = the old one-step jump), Troubleshooting (**Record input log**, default on; Open log folder, Debug window…), About (version).

**Pairing window** (`PairWindow`): a QR code for the phone's Scan QR code (a one-time token, renewed when it expires, gone when the window closes; holds up to four of this PC's IPv4 addresses), and for code pairing the PC's name, addresses, port and the pairing countdown, then "Pair <phone>? Code 123 456" with **Allow** / **Deny**. On first run with nothing paired the pairing window opens by itself.

**Debug window** (`DebugForm`, from the App page): the link as the PC sees it, what the game is given now, and the newest debug events at 4 Hz; **Save last 30 s**. The engine logs only while it is open or the phone's debug tools are on. A phone-side Record also saves the PC's (LINK_INFO save counter).

**Input log** (`InputRecorder`; **Record input log** on the App page, on by default, `HostSettings.InputLog`): `%LOCALAPPDATA%\GyroPhone\logs\input.log` (5 MB, then `input.log.old`), for finding stutter after real play. Turning it off ends the session's log at once; turning it on starts one for the session already up. Each line has the wall-clock time, then seconds since the session started. While a session is up there is one `sec` line a second (packets, moving packets, lost, late, the latest packet's extra delay, latency p50/p99 and jitter once the phone sent a clock offset, injections, pixels moved, biggest step, slowest receive-to-SendInput, and `lockwait` with who had the lock when gp-rx waited 0.1 ms or more for it), and one line per event, written when it happens:
- `lost`: 3 or more packets missing from the seq.
- `late`: packets more than 15 ms later than the session's fastest ones of the last 5-10 s (receive time minus phone send time, so no clock sync is needed), one line per stretch; "held up on the way" when the phone sent them on time.
- `phone gap`: the phone sent nothing for over 30 ms (its keepalive is 20 ms) with nothing lost, while input was moving or held.
- `catch-up`: late or lost motion spread out by `MouseCatchUp` instead of landing at once.
- `big move`: 64 px or more in one SendInput.
- `slow hand-off`: over 2 ms from receive to SendInput, with what took the time (each part from 0.05 ms): gp-rx's waits for the `engine lock` and who had it at the longest (`ui`, `watchdog`, `settings save`, `probes`, `pad`, the pad thread taking the newest wish or finishing a plug), time inside the `pad driver` (ViGEm or the DualSense driver) and `SendInput`, `garbage collection` pauses (`GC.GetTotalPauseDuration`) and `the rest` (gp-rx waiting for a CPU, the burst's other packets), e.g. `3.1 ms from receive to SendInput: engine lock 2.6 ms (ui had it), pad driver 0.2 ms, the rest 0.3 ms`.
- `refused`: Windows took fewer events than given.
- `release`: everything let go after 250 ms of silence.
- `session start` / `session end` (with the session's totals and extra-delay percentiles).
At most 10 event lines a second: the rest are counted in that second's `sec` line (`bigmoves N`, "N more events not written"), so a fast flick that moves 64 px every millisecond stays cheap (an event's text is made only when it will be written). The engine queues the lines under its lock and hands them to the log only after letting go of it. Like every log it counts keys and buttons but never names them.

## Performance

The **Performance** page (`PerformancePage`, `Host/Perf`) answers "does this setup make the game stutter?" with numbers.

- **Frames**: `FrameWatch` reads the frames every program shows with Direct3D 9 to 12 from Windows' event tracing (the DXGI and D3D9 present events, as PresentMon and the Xbox Game Bar do). It only listens, nothing touches the game, so it is anti-cheat safe. It runs while the main window is open or a test records, and stops when the window closes.
- **Permission**: Windows lets a program start that tracing only with admin rights or in the **Performance Log Users** group. Without it the page shows a warning bar: **Allow** asks once for admin rights and adds the user to the group (by its SID, so it works in any Windows language); it works from the next Windows sign-in. **Restart as administrator** works at once.
- **The game**: once a second, the window in front if it shows frames; else the game picked before while it still shows frames; else whichever program shows the most. The companion itself and `dwm` are never picked. "In the background" when it isn't in front.
- **Jump**: a frame at least **2×** the middle (median) of the 60 frames before it and at least **8 ms** longer: one rule at any frame rate. A gap of 1 s or more (a loading screen, the game in the background) isn't a frame; the count starts again after it.
- **Game card**: the game's name and frames in the last second; 1% low (the slowest 1% of the last minute's frames, as fps), jumps in the last minute, worst frame. The graph shows the last 10 s of frame times (dashed lines at 60 and 30 fps), jumps as red dots, and an orange mark under each second where the phone link lost or held up packets, paused, or let go of held keys (from the input log's `sec` line, so **Record input log** must be on). Under it the PC's load: CPU, driver time (DPC + interrupt) and the busiest processor's driver time (performance counters by their English names).
- **Record test**: a **Setup** pick (Router / Phone hotspot / PC hotspot / Wired (USB) / Ethernet) that fills in the **Name** (which starts as the link the phone uses now, such as "Wi-Fi 5 GHz" or "Ethernet"; type "PC hotspot", "Overlays off"…), a **Length** (3 / 5 / 10 min, default 3, or Until I stop), **Record** / **Stop**. Only frames while the game is in front count. It keeps recording after the window closes, until its time is up.
- **Results**: one row per record, newest first (the last 50): name (click it for its second-by-second file), when, game and minutes in front, avg fps, 1% low, jumps per minute, worst frame, link lost and late packets (with the worst delay), let go (releases), CPU, driver time and the busiest processor's. With two or more records the best value of fps, 1% low, jumps, lost and late is in the accent colour. **Open folder** opens `logs\perf`: `runs.jsonl` (the rows) and one text file per record with a line per second (frames, fps, worst frame, jumps | link rx, lost, late, worst delay, phone pauses, releases | CPU, DPC, interrupt, busiest processor).

## Link test

Finds out why phone packets get lost or held up, without anyone playing: `tools/link-test.sh [minutes] [--open]` (Git Bash on the PC, default 10 minutes; `--open` starts GyroPhone on the phone over adb first). It sends LINK_TEST_CTL to the companion on loopback; the companion logs `link test started` (companion.log and input.log) and sets TEST_MODE LINK_TEST until the time is up or it is stopped. The phone then sends, in place of its real input, STATEs with nothing held and the mouse totals sweeping 3 counts a packet (`StateFrame.fillLinkTest`), marked LINK_TEST, so it sends at its full rate; the pad shows **Link test running** over it and keeps the screen on. The companion handles them like any STATE (seq, loss, lateness, catch-up, input log) but never moves, presses or submits anything from them. Meanwhile the script records, when the phone is on adb, logcat (all buffers), `cmd wifi status` every 2 s and `dumpsys wifi` / `wifiscanner` before and after, then copies input.log and companion.log and runs `tools/link-test-analyze.py`, which groups the dropouts, says how regularly they come (and at which second of the minute) and lists the phone's Wi-Fi-related log lines from 1.5 s before to 0.5 s after each one. Everything goes to `build/link-test/<time>/` (`report.txt`).

## Watcher

`GyroPhoneCompanion.exe --watch` (`Watcher`): no window, no tray. While the companion is off it holds UDP 47800; the first DISCOVER from a known phone (by phone id or key id) makes it free the port and start the installed companion (with `--woken`), at most once per gap. It never binds while a companion runs or is starting (the companion takes its lock first thing), nor for up to 30 s after it started one that hasn't taken its lock yet. It waits on the socket, the stop switch and the companion's yield request together, so it lets go of the port within milliseconds. Opted out of power throttling. Started at login by the `GyroPhoneWatcher` Run entry; on and off with **Start when my phone appears** on the App page. DISCOVER is not authenticated, so a spoofer can only start the companion; connecting still needs the key.

**Companion start and the port.** When a watcher runs, the companion asks it to yield the port before its first bind. If the port is still taken, a gp-bind thread keeps trying, off the UI thread, every 25 ms for 5 s (the tray says Starting…), then every second for as long as the app runs (Home then shows the port as in use by another program), asking the watcher each time; the server, watchdog, cable link, overlays and shortcuts start once it binds.

**Locked files at start.** settings.json and host.key, when another program holds them (a scanner or backup tool), are retried for 3 s on a start by hand and 15 s on a start by the watcher (`--woken`) or an update. A settings.json still locked stops the start (starting on defaults would overwrite the pairings). A host.key still locked leaves key file and QR code pairing off only until a background retry (every 5 s) reads it; meanwhile Home shows it as a problem.

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
