# Phone: gyro sensors and poll rate

How the phone reads its gyro (and accelerometer and magnetometer) with as little delay as Android allows without root. What the samples are turned into is in [Gyro math](phone-gyro-math.md).

## Sources, best first

`sensor/GyroSource.kt` builds the candidate sources in order; `GamepadRuntime.startGyro` starts the first that works:

1. **Sensor direct channel** (`DirectChannelGyroSource`): the sensor HAL writes samples into shared memory (or, on phones like the OnePlus 15, a gralloc `HardwareBuffer`, read on the CPU through the small native library in `app/src/main/cpp/gyrobuffer.c` via `sensor/HardwareBufferReader.kt`) at `RATE_VERY_FAST`. No Binder or Looper hop. A poll thread at `THREAD_PRIORITY_URGENT_DISPLAY` checks the memory at the **gyro poll rate** and parses new reports with `core/.../sensor/DirectReportParser.kt`.
2. **Listener**: `SENSOR_DELAY_FASTEST` on a high-priority thread, used when the direct channel can't start.

The app holds `HIGH_SAMPLING_RATE_SENSORS`, which allows rates above 200 Hz.

**Falling back.** The direct channel is watched:

- If it opens but gives no sample within 1.5 s (seen with gralloc buffers), or its measured rate stays below what the listener would give (the microphone privacy toggle caps it to about 50 Hz, the listener to about 200 Hz), the listener runs instead until the next start or return to the foreground (`onDirectCapped`). The About section then says why ("direct channel limited to ~50 Hz").

On the OnePlus 15 the listener delivers about 480 Hz and the gralloc direct channel about 950 Hz.

## Poll rate

**Gyro poll rate** (Connection page): 1, 2 or 4 kHz, default 4 kHz. It is how often the poll thread looks at the shared memory, so polling above the HAL's rate adds at most one interval on top of it. It applies at once to a running direct channel, without restarting the sensor. It has no effect on the listener; the page says so while the listener runs.

## When the sensor runs

`GamepadRuntime.updateGyro` runs the gyro only while its samples are used:

- input is allowed (the pad is on screen in play, or **Send input only while the pad is open** is off) and the workspace's gyro is not **Gyro off**, or
- the PC's phone overlay asks for motion (it shows the phone turning even when the gyro moves nothing), or
- a rest calibration runs, or an acceleration graph on screen shows its live line.

Otherwise it stops, saving CPU and battery. Ratchets, toggles and the other gyro modes never stop it, so aiming never waits for the sensor to start. Each start resets the sample clock, the rate window and any sub-count remainder.

## Each sample

`GamepadRuntime.onGyroSample` takes rad/s in the Android sensor frame and the HAL timestamp:

- `dt` is the time since the previous sample, clamped to 0..50 ms (the first sample after a start has 0).
- The rest-calibration bias is subtracted.
- The sample goes to the debug trace, the gyro displays and parallax edge, the PC overlay feed, and then either to the PC (in PC gyro mode, see [Controllers](phone-controllers.md)) or to `GyroMouse.route`, which turns it into mouse counts, wheel or stick deflection in the engine.

The measured rate is shown in Settings › About and the debug panel (`core/.../sensor/GyroRateWindow.kt`).

## Rest calibration

`core/.../engine/GyroCalibration.kt`. Calibrate (in the workspace's gyro settings) asks the person to lay the phone still for 2 seconds and averages the gyro. It fails if the phone moved: peak-to-peak noise above 0.05 rad/s (about 3 °/s), or an average above 0.03 rad/s (about 1.7 °/s, which is slow motion, not drift). The result is a fixed bias, stored app-wide (`gyro_bias_x/y/z`), subtracted from every later sample. Nothing else is filtered; the phone's own bias correction also runs.

## Accelerometer and magnetometer

`sensor/AccelSource.kt` reads them at a normal rate on their own threads. The accelerometer runs while input is allowed and something needs it: a tilt or shake mapping in the workspace (`MappingEngine.motionMapped`), the World or Player gyro space, a gyro display or the parallax edge that holds its pose to gravity, or the PC overlay. Each reading is turned into the gyro's axes (right, up, toward the player) and goes to:

- `core/.../engine/GravityPose.kt`: the world's up for World and Player spaces (the gyro's turn pulled toward gravity with a short time constant; a jolt far from 1 g is skipped);
- `core/.../engine/TiltShake.kt`: **tilt** (sideways: the turn in the screen's plane from upright, + clockwise; forward and back: the lean from where it was held when tilt started, + back) as −1..1 with a deadzone and a range (default 30°, 5..90°, per axis if set), and **shake** (the reading jumping by the shake strength, default 14 m/s², 5..40, at most once per short gap);
- the gyro displays, the parallax edge and the PC overlay feed.

The magnetometer runs only while a gyro display with a compass is on the pad, or the PC overlay holds its heading by the compass.

## Screen rotation

How the phone is held decides which axis is which (see [Gyro math](phone-gyro-math.md)). **Screen rotation** (Settings › App › Screen, `runtime/ScreenOrientation.kt`): Auto, Landscape, Portrait (these follow the system rotation lock: unlocked they turn with the phone, Landscape only between its two landscape sides and Portrait between its two portrait sides; locked they stay as the screen is), and Forced portrait, Forced landscape top on the left, Forced landscape top on the right. The gyro follows the rotation the screen shows.

## Key files

`sensor/GyroSource.kt`, `sensor/HardwareBufferReader.kt`, `app/src/main/cpp/gyrobuffer.c`, `sensor/AccelSource.kt`, `runtime/GamepadRuntime.kt` (`startGyro`, `updateGyro`, `onGyroSample`, `onDirectCapped`, `updateMotion`), `core/.../sensor/DirectReportParser.kt`, `core/.../sensor/GyroRateWindow.kt`, `core/.../engine/GyroCalibration.kt`, `core/.../engine/GravityPose.kt`, `core/.../engine/TiltShake.kt`, `runtime/ScreenOrientation.kt`.
