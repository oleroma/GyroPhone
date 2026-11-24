# Video latency test

Times how long the phone's gyro takes to move the mouse a game gets, screen included, from a slow-motion
video. A tool of its own: not part of the app, the companion or any build or release. The full description
is in [docs/spec/pc-latency-test.md](../../docs/spec/pc-latency-test.md).

1. Copy `markers.png` to the phone once. For a test, open it in the phone's gallery in **split screen** above
   GyroPhone's pad, or switch off GyroPhone's **Send input only while the pad is open** (otherwise the gyro
   stops while the image is in front).
2. Double-click `latency-test.bat` (needs the .NET 10 SDK; the first start builds the tool into `build/latency-test`).
   The test window opens: a green bar that follows only GyroPhone's mouse.
3. Hold the phone in front of the window, the markers toward you, and twist your wrist left and right for
   about 10 seconds while another phone films it in slow motion (240 fps) from behind your hand, along the
   forearm, the markers and the green bar in the picture.
4. Copy the original video to the PC and drop it on the test window (or on `latency-test.bat`).

Results go to `Documents\GyroPhone\Latency`, and `results.html` there (all results, with a history chart)
opens after each one. The analyser's tests: `dotnet test tools/latency-test/Tests`.
