# PC: virtual devices

How a STATE becomes input a game sees. Over Bluetooth the phone is itself a HID device ([Connection](phone-connection.md)); this page is the companion's side.

## Game pad (ViGEm)

`GyroPhone.Windows/ViGEmPadSink.cs` plugs a virtual pad through the **ViGEmBus** driver (installed by the person; Home and the Gamepad page say when it is missing, with Retry):

- **Xbox 360** (XInput, the default) or **DualShock 4** (DirectInput HID), chosen in the tray or the Gamepad page; **Off** plugs nothing (keyboard and mouse only).
- `GyroPhone.Protocol/Mapping/PadMapper.cs` maps STATE to the report: the 16 phone buttons and hat to XUSB bits or DS4 buttons/special/hat, sticks signed (+Y up for X360, bytes with 128 = centre for DS4), triggers 0..1023 to bytes. Buttons X360 can't show (M1–M4, Share) are logged once.
- Reports are built whole and sent with one SubmitReport, only when the mapped frame changed. A broken bus is retried at 10 Hz; a pad removed underneath is re-plugged.
- **Rumble** from games is recorded on the driver thread and sent as RUMBLE at most 100 Hz. The phone ignores it today.
- The virtual pad's XInput slot is reported, so the PC-controller reader skips it.

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

`GyroPhone.Windows/ViGEmPadSink.cs`, `SendInputSink.cs`, `XInputPcPad.cs`, `GyroPhone.Protocol/Mapping/PadMapper.cs`, `InputDiff.cs`, `HidScanCodes.cs`, `KeyRepeat.cs`, `InjectEvent.cs`, `GyroPhone.Protocol/MouseDeltaTracker.cs`, `StateFrame.cs`, `GyroMath.cs`, `GyroGate.cs`, `PcGyro.cs`, `PcGyroTail.cs`, `PadHeldHistory.cs`, `GyroPhone.Host/HostEngine.cs` (`Flush`, `OnPadGyro`).
