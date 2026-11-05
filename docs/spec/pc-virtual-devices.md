# PC: virtual devices

How a STATE becomes input a game sees. Over Bluetooth the phone is itself a HID device ([Connection](phone-connection.md)); this page is the companion's side.

## Game pad (ViGEm)

`GyroPhone.Windows/ViGEmPadSink.cs` plugs a virtual pad through the **ViGEmBus** driver (installed by the person; Home and the Gamepad page say when it is missing, with Retry):

- **Xbox 360** (XInput, the default) or **DualShock 4** (DirectInput HID), chosen in the tray or the Gamepad page; **Off** plugs nothing (keyboard and mouse only).
- `GyroPhone.Protocol/Mapping/PadMapper.cs` maps STATE to the report: the 16 phone buttons and hat to XUSB bits or DS4 buttons/special/hat, sticks signed (+Y up for X360, bytes with 128 = centre for DS4), triggers 0..1023 to bytes. Buttons X360 can't show (M1–M4, Share) are logged once.
- Reports are built whole and sent with one SubmitReport, only when the mapped frame changed. The DualShock 4 always gets its full report (`Ds4Report`, ViGEm's DS4_REPORT_EX via SubmitRawReport): buttons and sticks plus gyro, accelerometer, a timestamp, battery on cable and an untouched touchpad.
- **DualShock 4 motion**: while the pad is a DualShock 4 the phone sends MOTION ([Protocol](protocol.md)) with its raw gyro and accelerometer, whatever the workspace's own gyro does. `Ds4MotionFeed` turns each new sample into its own report with the pad's current buttons and sticks, stamped with the sample's own time (16/3 µs units; the DualSense, below, gets the same samples), so games that read the pad's gyro (The Finals, Steam Input) add up the turn exactly. Input not allowed on the phone, the companion paused or locked, or no MOTION for 60 ms: the gyro rests (the accelerometer keeps its last reading). Turn the app's gyro mouse off in a workspace whose game aims with the pad's gyro, or both move the camera. The phone maps its axes to the pad's first (`PadGyro`, the workspace's PC pad yaw / pitch / roll settings; default: the screen is the pad's face). Games check for gyro when they start, so start the game after the phone connects. A broken bus is retried at 10 Hz; a pad removed underneath is re-plugged.
- **Rumble** from games is recorded on the driver thread and sent as RUMBLE at most 100 Hz. The phone ignores it today.
- The virtual pad's XInput slot is reported, so the PC-controller reader skips it.

## Game pad (DualSense, optional driver)

`driver/` is the optional **GyroPhone pad driver**: a user-mode (UMDF 2) driver on Windows' Virtual HID Framework that shows a wired **DualSense** (054C:0CE6, the real HID descriptor, answers to the calibration, pairing and firmware feature reports). ViGEmBus can't do a DualSense. See `driver/README.md` for install, uninstall and risks.

- Installed by the person with `driver\install.cmd` (after a PC build the install hook copies the package, unzipped, into the repo's `build\GyroPhonePad-driver`; it never installs it. The zip is only for releases): it makes a signing certificate for that PC only, trusts it there, signs the package and installs it on a `Root\GyroPhonePad` device. Test signing stays off. `uninstall.cmd` removes all of it.
- `PadSinks.Create` gives the engine a `CombinedPadSink` when the driver's device interface is there: ViGEm for Xbox 360 / DualShock 4, `DualSensePadSink` for **DualSense**. The DualSense choice shows in the tray and the Home and Gamepad pages only then (restart the companion after installing). The driver is looked for at start and on "Try again".
- The DualSense takes the DualShock 4's frame (`PadMapper.ToDs4`, the same buttons), built as report 0x01 by `DualSenseReport` and sent with one DeviceIoControl per change. A pad belongs to the companion's handle and goes when it closes.
- **Motion**: the DualSense gets the same MOTION as the DualShock 4 (the phone sends it for output modes 2 and 3), one report per sample through the same `Ds4MotionFeed`: gyro at bytes 16..21, accelerometer at 22..27, in the DualShock 4's units (the driver's calibration report says so), and the sample's own time at 28..31 in 1/3 µs, the DualSense's sensor timestamp. The feed keeps one clock in 1/3 µs; the DualShock 4's 16/3 µs timestamp is every 16th tick of it. The workspace's PC pad yaw / pitch / roll settings apply to both.
- **Not yet**: touchpad, rumble, lightbar and adaptive triggers. Output reports are accepted and ignored.

## Keyboard and mouse (SendInput)

`GyroPhone.Windows/SendInputSink.cs`, one SendInput call per injection:

- `InputDiff` diffs what was injected against the new STATE: modifier downs, key downs, key ups, modifier ups, mouse buttons, move, wheel, horizontal wheel.
- Keys go as **Set 1 scan codes** (`HidScanCodes`, from HID usages), so games that read scan codes work; Pause and the media keys go as virtual keys. Unknown usages are counted, never injected.
- Mouse moves are relative, from the difference of STATE's cumulative totals (`MouseDeltaTracker`), each step capped at 16384; buttons L, R, M, Back, Forward.
- Wheels in 1/120 notch when the session has HIRES_WHEEL (STATE_HIRES), else whole notches.
- **Key repeat** (`KeyRepeat`): held Volume Up/Down repeat after 400 ms every 50 ms, because Windows doesn't repeat injected keys.

## PC gyro mode

When the phone's **Use PC controller for gyro ratchet** is on ([Controllers](phone-controllers.md)), STATE_GYRO carries raw samples and the gyro config. The engine:

1. reads the PC's controller (`XInputPcPad`: the first XInput pad that is not ours, every millisecond on its own thread, counting presses so none is lost between STATEs; empty slots checked only every few seconds);
2. works out each sample's ratchet state from what was held **at that sample's time** (`PadHeldHistory`) and the PAD_GYRO buttons (`GyroGate`: axes each ratchet flips, toggle, own settings from PAD_GYRO_SET);
3. runs the samples through `GyroMath.cs` (the exact port of the phone's `GyroMath.kt`), and fills a gap longer than the 8-sample ring in one step from the running totals;
4. adds the result to STATE's non-gyro mouse, wheel and pad axes before injecting.

The PC controller's own buttons still reach games directly (or through Steam Input); the companion only reads it. Its ratchets also count for the overlays' "Ease on ratchet".

## Key files

`GyroPhone.Windows/ViGEmPadSink.cs`, `DualSensePadSink.cs`, `PadSinks.cs`, `GyroPhone.Protocol/Mapping/Ds4Report.cs`, `DualSenseReport.cs`, `driver/src/driver.c`, `driver/src/dualsense.h`, `MotionFrame.cs`, `SendInputSink.cs`, `XInputPcPad.cs`, `GyroPhone.Protocol/Mapping/PadMapper.cs`, `InputDiff.cs`, `HidScanCodes.cs`, `KeyRepeat.cs`, `InjectEvent.cs`, `GyroPhone.Protocol/MouseDeltaTracker.cs`, `StateFrame.cs`, `GyroMath.cs`, `GyroGate.cs`, `PcGyro.cs`, `PcGyroTail.cs`, `PadHeldHistory.cs`, `GyroPhone.Host/HostEngine.cs` (`Flush`, `OnPadGyro`).
