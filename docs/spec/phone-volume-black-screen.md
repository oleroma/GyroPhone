# Phone: volume rocker and black screen

## Volume rocker

The phone's own volume keys are a mappable device, **Phone** (`PhysicalDevice.PHONE`, the device preset's `phone` mappings), edited in the device screens like a controller ([Controllers](phone-controllers.md)).

- **Unmapped**, Volume Up / Down turn the **PC's** volume (`DevicePresets.withPhoneDefaults` adds those mappings for any rocker key the preset leaves unmapped, even with no preset pinned).
- They can be mapped to anything else, with the usual When / How (tap, long press, double tap…).
- Read **only while input is allowed** (the pad open in play). Then the key is consumed, so the phone's own volume slider does not appear (`ControllerInput`). In menus, edit mode, with the pad closed, or while a mapping editor is learning a key, the rocker changes the phone's volume as usual.
- Android does not deliver the keys with the real screen off; under the black screen (below) they work, since the pad is still open.

**Media keys.** Targets include Volume Up / Down, Mute, Play/Pause, Next, Previous (HID consumer usages; `core/.../hid/HidUsage.kt`). Over Bluetooth they go in the keyboard report; the companion sends them as Windows media keys (Windows shows its volume popup) and repeats a held Volume key (400 ms delay, then every 50 ms).

## Black screen

For playing with a controller while the phone sits on the desk.

- **Black screen when idle** (App › Screen, default off) blacks out the pad after **Black screen after** (30 s, 1, 2 or 5 min; default 1 min) without a screen touch, in play mode only.
- A mapping to **Black screen** (Other targets) blacks it out at once, for example from a controller button.
- While black, the touch controls are off. Gyro, controllers, the volume rocker and the PC link keep working.
- A **tap** on the screen brings the pad back; that tap presses nothing.
- A black pad keeps the screen on even with **Keep the screen on** off, so the phone doesn't lock under it.
- The app **never touches brightness**: it draws a black overlay only (black OLED pixels are off anyway). Brightness is left to the phone.
- Entering edit mode clears it.

## Key files

`input/ControllerInput.kt`, `core/.../model/DevicePresets.kt` (`withPhoneDefaults`, `standardPhone`), `core/.../hid/HidUsage.kt`, `ui/PadScreen.kt` (black screen), `runtime/GamepadRuntime.kt` (`blackScreenIdle`, `blackScreenSeconds`, `blackScreenRequests`), `companion/src/GyroPhone.Host/Windows` input code (media keys, key repeat).
