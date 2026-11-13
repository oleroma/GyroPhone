# Phone: gyro sensors and poll rate

How the phone reads its gyro (and accelerometer and magnetometer) with as little delay as Android allows without root. What the samples are turned into is in [Gyro math](phone-gyro-math.md).

## Sources, best first

`sensor/GyroSource.kt` builds the candidate sources in order; `GamepadRuntime.startGyro` starts the first that works:

1. **Sensor direct channel** (`DirectChannelGyroSource`): the sensor HAL writes samples into shared memory (or, on phones like the OnePlus 15, a gralloc `HardwareBuffer`, read on the CPU through the small native library in `app/src/main/cpp/gyrobuffer.c` via `sensor/HardwareBufferReader.kt`) at `RATE_VERY_FAST`. No Binder or Looper hop. A poll thread at `THREAD_PRIORITY_URGENT_DISPLAY` checks the memory at the **gyro poll rate** and parses new reports with `core/.../sensor/DirectReportParser.kt`. Each check reads only the header of the slot the HAL writes next (after the newest sample seen; on a gralloc buffer under the same lock as the copy) and copies the whole ring only when that slot holds a new sample. Every 64th check reads the whole ring anyway: if that finds new samples the watched slot didn't have, the HAL fills its slots in another order and every check reads the whole ring until the next start.
2. **Listener**: `SENSOR_DELAY_FASTEST` on a high-priority thread, used when the direct channel can't start.

The app holds `HIGH_SAMPLING_RATE_SENSORS`, which allows rates above 200 Hz.

**Falling back.** The direct channel is watched:

- If it opens but gives no sample within 1.5 s (seen with gralloc buffers), or its measured rate stays below what the listener would give (the microphone privacy toggle caps it to about 50 Hz, the listener to about 200 Hz), the listener runs instead until the next start or return to the foreground (`onDirectCapped`). A direct channel that gave no samples at all is not tried again until the app process restarts (`directSilent`), so later starts and returns to the app don't pay the 1.5 s of dead gyro again; a capped one is tried again, since the toggle may be off by then. The About section then says why ("direct channel limited to ~50 Hz").

On the OnePlus 15 the listener delivers about 480 Hz and the gralloc direct channel about 950 Hz.

## Poll rate

**Gyro poll rate** (Connection page): 1, 2 or 4 kHz, default 4 kHz. It is how often the poll thread looks at the shared memory, so polling above the HAL's rate adds at most one interval on top of it. It applies at once to a running direct channel, without restarting the sensor. It has no effect on the listener; the page says so while the listener runs.

## Performance hints

The gyro poll thread, the Wi-Fi sender (`WifiPump`) and the Bluetooth sender (`ReportPump`) each open a performance hint session (the gyro displays' drawing thread too, with 4 ms a frame) for their own thread (`runtime/WorkHint.kt`, `PerformanceHintManager`) with a 1 ms target per pass, prefer speed to saving power (`setPreferPowerEfficiency(false)`), and report how long each pass took (the longest at most once a millisecond), after the pass's work, so it adds no delay. Without hint support they run as before.

## When the sensor runs

`GamepadRuntime.updateGyro` runs the gyro only while its samples are used:

- input is allowed (the pad is on screen in play, or **Send input only while the pad is open** is off) and the workspace's gyro is not **Gyro off**, or
- the PC's phone overlay asks for motion (it shows the phone turning even when the gyro moves nothing), or
- a rest calibration runs, or an acceleration graph on screen shows its live line.

Otherwise it stops, saving CPU and battery. Ratchets, toggles and the other gyro modes never stop it, so aiming never waits for the sensor to start. Each start resets the sample clock, the rate window and any sub-count remainder. `updateGyro` only decides: the source is started, switched (`onDirectCapped`) and stopped on its own `GyroControl` thread (`syncGyro`), so opening a direct channel (shared memory and binder calls) or waiting for a stopped source's last sample never holds up the main thread, as when the pad comes back from a menu page. A rest calibration whose gyro can't start ends there.

## Each sample

`GamepadRuntime.onGyroSample` takes rad/s in the Android sensor frame and the HAL timestamp:

- `dt` is the time since the previous sample, clamped to 0..50 ms (the first sample after a start has 0).
- The rest-calibration bias is subtracted.
- The sample goes to the debug trace, the gyro displays and parallax edge, the PC overlay feed, and then either to the PC (in PC gyro mode, see [Controllers](phone-controllers.md)) or to `GyroMouse.route`, which turns it into mouse counts, wheel or stick deflection in the engine.

The measured rate is shown in App › About and the debug panel (`core/.../sensor/GyroRateWindow.kt`).

## Rest calibration

`core/.../engine/GyroCalibration.kt`. Calibrate (in the workspace's gyro settings) asks the person to lay the phone still for 2 seconds and averages the gyro. It fails if the phone moved: peak-to-peak noise above 0.05 rad/s (about 3 °/s), or an average above 0.03 rad/s (about 1.7 °/s, which is slow motion, not drift). The result is a fixed bias, stored app-wide (`gyro_bias_x/y/z`), subtracted from every later sample. Nothing else is filtered; the phone's own bias correction also runs.

## Accelerometer and magnetometer

`sensor/AccelSource.kt` reads them on their own threads: the accelerometer at about 200 Hz (one reading every 5 ms, `ACCEL_PERIOD_US`), the magnetometer at game rate (for the gyro displays and PC overlays with a compass, and for sideways tilt's Use compass when flat). The accelerometer runs while input is allowed and something needs it: a tilt or shake mapping in the workspace (`MappingEngine.motionMapped`), the World or Player gyro space, a gyro display or the parallax edge that holds its pose to gravity, or the PC overlay. Each reading is turned into the gyro's axes (right, up, toward the player) and goes to:

- `core/.../engine/GravityPose.kt`: the world's up for World and Player spaces (the gyro's turn pulled toward gravity with a short time constant; a jolt far from 1 g is skipped);
- `core/.../engine/TiltShake.kt`: **tilt** (sideways: the phone's turn about the axis through its screen, like a steering wheel, counted from upright, + clockwise; forward and back: the lean from where it was held when tilt started, + back) as −1..1 with a deadzone and a range (default 30°, 5..90°, per axis if set). While the gyro runs (any gyro mode but Gyro off) tilt follows the gyro (`TiltShake.gyro`, every gyro sample, so it is as quick as the gyro and a tap on the screen doesn't jerk it), and gravity holds it: the gyro's pose is pulled toward the accelerometer over 0.5 s (a jolt more than 15% off 1 g is skipped), and the sideways turn toward gravity's turn from upright over 50 ms with the screen upright, more slowly the flatter the phone lies (by the squared share of gravity in the screen's plane), so level is always straight. **Lying flat** (within 20° of flat; on a lap, on a table) gravity can't tell where upright is: the gyro carries the turn on alone from where it was (it starts straight if tilt starts there), drifting only as slowly as the gyro does; **Set orientation** makes the way it points straight there (tilted up more, it changes nothing: gravity already says), and the sideways tilt ⚙'s **Use compass when flat** (`GyroSettings.tiltCompass`, off by default) lets the magnetometer hold straight where the phone went flat (its level part, pulled over 1 s, firmer the flatter; magnets and metal nearby can bend it). With Gyro off, tilt is gravity alone, smoothed by time with a 35 ms time constant (`TILT_TAU_NS`; about 63% of a change within 35 ms, settled in about 0.15 s, the same at any sensor rate), and the sideways turn holds still within 10° of flat, where gravity's turn is noise. And **shake** (the reading jumping by the shake strength, default 14 m/s², 5..40, at most once per short gap). A shake is measured against a slower gravity (90 ms, the old 50 Hz filter's time) and scaled by 0.8 as that filter saw it, so a shake strength needs the same jolt it always did;
- the gyro displays, the parallax edge and the PC overlay feed.

The magnetometer runs only while a gyro display with a compass is on the pad, or the PC overlay holds its heading by the compass.

## Screen rotation

How the phone is held decides which axis is which (see [Gyro math](phone-gyro-math.md)). **Screen rotation** (App › Screen, `runtime/ScreenOrientation.kt`): Auto, Landscape, Portrait (these follow the system rotation lock: unlocked they turn with the phone, Landscape only between its two landscape sides and Portrait between its two portrait sides; locked they stay as the screen is), and Forced portrait, Forced landscape top on the left, Forced landscape top on the right. The gyro follows the rotation the screen shows.

## Key files

`sensor/GyroSource.kt`, `sensor/HardwareBufferReader.kt`, `app/src/main/cpp/gyrobuffer.c`, `sensor/AccelSource.kt`, `runtime/GamepadRuntime.kt` (`startGyro`, `updateGyro`, `onGyroSample`, `onDirectCapped`, `updateMotion`), `core/.../sensor/DirectReportParser.kt`, `core/.../sensor/GyroRateWindow.kt`, `core/.../engine/GyroCalibration.kt`, `core/.../engine/GravityPose.kt`, `core/.../engine/TiltShake.kt`, `runtime/ScreenOrientation.kt`.
