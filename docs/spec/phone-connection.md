# Phone: connection

How the phone's output reaches the PC, and what the Connection page shows. The wire format itself is in [Protocol and pairing](protocol.md).

## One link at a time

The phone has two transports (`link/TransportKind.kt`): **Bluetooth** (the default on a fresh install) and **Wi-Fi**. The Connection page's top switch picks one; the choice is stored. Only one link (`link/OutputLink.kt`) runs at a time, so only one thread ever calls `MappingEngine.snapshot()`. Switching (`GamepadRuntime.setTransport`) stops the old link (a Wi-Fi session says BYE first), releases everything the engine holds, then starts the new one.

Each link reports a `LinkState`: Off, Searching, Pairing (with the 6-digit code once known), Connecting, Connected (with a detail line such as "5 GHz · 1201 Mbps · −51 dBm"), HostSilent (the PC stopped answering, reconnecting) or Failed. The pad's Connection capsule, the Connection page and the service notification show it.

## The foreground service

`service/GamepadService.kt` keeps the link and the gyro alive while the app is open and connected. It is a `connectedDevice` foreground service with a notification. It stops, ending the link:

- on the notification's Stop (which also closes the app's screen and removes it from Recents),
- when the app is swiped away in Recents,
- after the app has been off screen for the **Stop when idle** time (Connection page: Off, 5, 10 or 30 minutes; default 10).

Opening the app starts it again. `GamepadRuntime` (`runtime/GamepadRuntime.kt`) is the process-wide wiring that the service starts and stops: profile → engine → link, plus the sensors.

## Bluetooth HID

`hid/HidDeviceManager.kt` registers the phone as a Bluetooth Classic HID device through `BluetoothHidDevice`, named "GyroPhone", subclass combo + game pad, with interrupt-channel QoS asking for guaranteed service. Windows pairs with it like any Bluetooth controller; nothing is installed on the PC.

The descriptor (`core/.../hid/HidDescriptor.kt`) carries three top-level collections, each with its own report id:

- a DirectInput-style game pad: 16 buttons, a hat, two sticks, two triggers;
- a 6-key keyboard with the modifier byte;
- a 5-button mouse with 16-bit relative X/Y, a wheel and horizontal pan (AC Pan). Wheel and pan sit in logical collections with a Resolution Multiplier feature report, so a host that enables it (Windows does) reads them in 1/120 notch; otherwise whole notches. A PC that paired with an older descriptor without it must remove and re-pair the phone once to get smooth scrolling.

Windows sees a DirectInput pad, not XInput: XInput needs a Microsoft VID/PID in the Device ID record, which Android apps cannot set. XInput comes from the companion.

**Report pump.** `hid/ReportPump.kt` runs on its own thread and snapshots the engine at a fixed rate, the **Report rate** (Connection page: 30, 45, 60, 75 or 90 Hz; default 60). Android's `sendReport` only queues and gives no "sent" signal, and the reference phone's stack forces the HID link into sniff mode after every report (it can't be turned off without root), which carries about one packet per sniff interval. So each tick sends at most **one** report, and two reports are never closer than one interval. A rate above what the link carries builds a queue, which feels like growing lag.

`core/.../hid/ReportScheduler.kt` picks that one report: keyboard changes first, then mouse button edges, then game pad button and hat edges, then analog pad changes and mouse motion taking turns. Mouse motion not yet sent keeps adding up, so nothing is lost or queued; button edges stay latched until a report carries them. After a tick that sent nothing, new input (the engine's `onChange`) ends the wait early. When nothing changed for a while (well under the stack's 5 s idle timer) the current pad or held-keyboard state is sent again, which repairs a report the stack dropped and keeps the link out of its idle sniff profile.

**Reconnect.** When the link drops without the person asking, `core/.../hid/HostReconnect.kt` reconnects to the last host after 1, 2, 4 and 8 s, then every 10 s, for about a minute. A new connection, a connect or disconnect by the person, or stopping cancels it; a link the person ended is never retried.

**Bluetooth panel** (`ui/ConnectScreen.kt`): the HID registration state, **Make discoverable**, the bonded PCs with Connect and Disconnect, and reports sent per second.

## Wi-Fi link

`wifi/WifiLink.kt` is the phone side of the companion link: discovery, pairing, sessions, the USB paths and the link stats. It owns one UDP socket bound to the Wi-Fi network (`wifi/WifiNetworkBinder.kt`), with DSCP CS6 (`0xC0`, which maps to WMM voice) so the AP queues it first. Its receive thread feeds the pure state machines in `core/.../net/` (`PhonePairingFlow`, `PhoneSessionFlow`, `QrEnrollFlow`, `KeyTakeover`) and sends what they say.

**Send loop.** `wifi/WifiPump.kt` is event driven: the engine's `onChange` wakes it, and `core/.../net/SendScheduler.kt` decides what to send. A STATE goes out as soon as the state differs from the last one sent, but never two within 1/rate; after a packet carrying a discrete change (a button, key or click, not only motion) two repeats follow at +2 ms and +6 ms; a KEEPALIVE goes when nothing was sent for 20 ms. The rate cap is the **Report rate** on Wi-Fi: 250, 500 or 1000 Hz (default 1000). It is the only thread that seals phone-to-PC packets, BYE included. Once a second it also sends PING and LINK_INFO, and while the PC's phone overlay is on it sends OVERLAY about 60 times a second.

**Low-latency lock.** `wifi/WifiLocks.kt` holds a `WIFI_MODE_FULL_LOW_LATENCY` lock while a session is live, which keeps the radio out of power save. Android honours it only while the screen is on and GyroPhone is in front; the Wi-Fi panel says whether it is held.

**Finding PCs.** The phone broadcasts DISCOVER (to 255.255.255.255 and the subnet's directed broadcast) once a second while looking. Each PC that answers with ANNOUNCE shows under **Found on this network** for 3 s after its last answer. **Enter PC address** reaches a PC on another subnet or behind a network that drops broadcasts.

**Pairing on the phone.** Three ways, all described byte by byte in [Protocol and pairing](protocol.md):

- **Scan QR code** (the usual way): the camera reads the QR code in the PC's pairing window. The phone then trusts the PC's key and sends its own key to it; both sides know each other and key pairing connects.
- **Code pairing**: tap a found PC; both screens show the same six-digit code (`ui/PairDialog.kt`); the person taps Confirm on the phone and Allow on the PC.
- **Key files**: the Key pairing section (`ui/KeyPairingSection.kt`) shows this phone's key fingerprint, shares `phone.pub`, imports a PC's `host.pub` and lists the trusted PCs. The PC build's install hook swaps the two key files over adb, so the phone and the PC know each other with no step at all.

Code-paired PCs are kept by `wifi/WifiPairingStore.kt`: the pairing key is wrapped with an Android Keystore AES-256-GCM key, never stored in the clear, with the address the PC last answered from. Key pairing keeps the phone's static P-256 key in the Android Keystore (`wifi/PhoneKeys.kt`) and the trusted PC keys as files in `Android/data/com.oleroma.gyrophone/files/keys/trusted_hosts/`, next to `phone.pub`.

**Connecting.** With a paired PC the phone runs HELLO/WELCOME (see the protocol page). A PC that is ready by key (both sides know each other) connects by itself, but only while GyroPhone is on screen and **Connect automatically** (Key pairing section, default on) is on; only the person's Disconnect stops it. When a session to one PC has been down for 3 s, another PC that is ready by key may take its place (15 s if the first PC said it exited, giving it time to restart) (`core/.../net/KeyTakeover.kt`).

**Link quality** section (`ui/WifiPanel.kt`): packets per second sent, refused and lost, the round trip, the one-way latency and jitter the PC reports in HOST_STATUS, the Wi-Fi radio (band, link speed, signal), whether the low-latency lock is held, and whether the link runs over USB.

## USB

Two USB paths carry the same Wi-Fi protocol, so pairing, sessions and encryption are unchanged:

- **USB tethering**: the phone looks for a tethering interface once a second. While the session runs over Wi-Fi, a probe sends DISCOVER on the tether; when the session's PC answers there, the link moves to USB. Pulling the cable, or the PC going silent on USB, moves it back to Wi-Fi.
- **Direct cable (Android Open Accessory)**: the companion switches a phone on its USB port into accessory mode, naming GyroPhone. Android offers GyroPhone for it (`ui/UsbAccessoryActivity.kt` brings the app to the front and closes). `wifi/AccessoryBridge.kt` bridges the cable's byte stream to a loopback UDP socket, so `WifiLink` uses it exactly like tethering. Frames on the cable are `0x47, u16 length, bytes` (`core/.../net/UsbFrames.kt`). The phone must be in "No data transfer" USB mode for the PC to switch it (Windows passes control requests only to a composite device's first interface).

## What is sent and when

**Input only while the pad is open.** With **Send input only while the pad is open** (App › Screen, default on), controller, keyboard, mouse and gyro input reach the engine only while the pad is on screen in play. The link stays up and sends nothing held. Leaving the pad releases everything, so no key sticks on the PC, and pauses the gyro sensor.

**Haptics.** Haptic mappings play on the phone's vibrator only while a PC is connected (`runtime/PhoneHaptics.kt`), on a thread of their own so the report never waits for a vibrator call.

## Key files

`link/OutputLink.kt`, `link/BluetoothLink.kt`, `hid/HidDeviceManager.kt`, `hid/ReportPump.kt`, `core/.../hid/ReportScheduler.kt`, `core/.../hid/HostReconnect.kt`, `core/.../hid/HidDescriptor.kt`, `wifi/WifiLink.kt`, `wifi/WifiPump.kt`, `wifi/WifiLocks.kt`, `wifi/WifiNetworkBinder.kt`, `wifi/WifiPairingStore.kt`, `wifi/PhoneKeys.kt`, `wifi/AccessoryBridge.kt`, `wifi/UsbTether.kt`, `core/.../net/SendScheduler.kt`, `service/GamepadService.kt`, `ui/ConnectScreen.kt`, `ui/WifiPanel.kt`, `ui/KeyPairingSection.kt`, `ui/PairDialog.kt`.

Paths starting `core/...` are under `core/src/main/kotlin/com/oleroma/gyrophone/core/`; the others under `app/src/main/java/com/oleroma/gyrophone/`.
