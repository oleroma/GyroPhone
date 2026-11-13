# PC: install and update

## Where it lives

The companion is one self-contained exe at a fixed path, **`%LOCALAPPDATA%\Programs\GyroPhone\GyroPhoneCompanion.exe`**, with OpenCV's two native DLLs beside it (used only by the latency test, so kept out of the exe), so its firewall rule and Run entries stay valid across updates. Settings and keys are in `%APPDATA%\GyroPhone` and survive updates ([Companion](pc-companion.md)). No installer, no admin rights needed except for the firewall rule and, for the FPS overlay, setting up the FPS helper once ([Companion](pc-companion.md), Performance).

## Installing a build

`.agents/hooks/install` (run on the PC by `agent-install` after `agent-build pc`, see [Build and release](build-release.md)):

1. **Companion**: copies the new exe in only when the installed one is from another build (the exe is stamped `<version>-<commit>`; `VERSION.txt` names the build in place). A running exe is never touched: the new build waits next to it as **`GyroPhoneCompanion.exe.new`**. With no companion or watcher running it is copied straight in. Before the exe, OpenCV's DLLs are copied next to it when they differ from the installed ones; one that can't be replaced (a running companion loaded it for the latency test) waits as **`<dll>.new`**.
2. **Key files**: copies the phone's `phone.pub` (over adb) to `%APPDATA%\GyroPhone\authorized_phones\` and the PC's `host.pub` to the phone's `keys/trusted_hosts/`, so the phone connects over Wi-Fi with no code. Only public keys move. A PC whose companion never ran gets its key from `GyroPhoneCompanion.exe --init-keys` (makes the key and exits, no window).
3. It never starts or closes the apps.

The APK (the release build type) is installed by `agent-install` with `adb install -r` (an in-place update; same signing key, kept outside the repo (`~/.android-keys/gyrophone.jks` on the PC, repo secrets in CI), never an uninstall).

## Self-update

`GyroPhone.Tray/CompanionUpdater.cs` watches the exe's folder for `<exe>.new` (a FileSystemWatcher; it checks 300 ms after the last event, so a copy still in progress is waited out) and also checks every 5 s in case an event is missed. When one is there, the running companion starts the new build as an **update finisher** (`GyroPhoneCompanion.exe.new --finish-update <pid> [--tray] [--watch]`) in the background, and only once it runs stops the watcher and exits cleanly (release-all, HOST_BYE EXIT). Starting the 190 MB file makes the antivirus check it (about 4 s); the old companion keeps serving the phone and overlays meanwhile. The finisher (`UpdateFinisher.cs`) waits for it to exit, **moves** itself over the exe (retrying while the file is still held), starts the companion again from the usual path with `--updated`, moves in any `<dll>.new` waiting beside it (only once the exe move worked; one that still can't be moved keeps waiting for the next update), and starts the watcher. Moving keeps the file the antivirus already checked, so the new companion starts at once; the old copy made a new file that was checked again, about 4 s more with no companion running (update went from about 6 s without input to about 1 s). After the move the finisher's own path is gone and the single-file exe can't load more of itself, so it uses only assemblies it already loaded and starts processes with CreateProcess directly. It then starts the companion and/or watcher, which then says it was updated. It always restarts, even with a phone connected; the phone reconnects by itself (it waits up to 15 s for a PC that said it exited before trying another).

If the copy fails, the old build starts again with `--update-failed` and doesn't retry that `.new` until it is installed again. While only the watcher runs, the watcher does the same. Local builds (no CI version) never update themselves.

## Startup

- **Start with Windows** (App page, default off): the `GyroPhoneCompanion` entry in the per-user Run key (`AutoStart`).
- **Start when my phone appears** (App page): the `GyroPhoneWatcher` Run entry runs `--watch` at login ([Companion](pc-companion.md#watcher)). Besides Start with Windows, this is the only way the companion starts by itself.
- One companion per session (a named mutex); `--restarted` (from Restart as administrator or an update) waits up to 30 s for the old one to exit, and if it still hasn't, says the previous copy is still closing.

## Firewall

`FirewallHelper`: one inbound UDP allow rule for the exe's path, **Private and Domain profiles only**; Public networks stay closed by this rule. The phone often gets through on Public anyway (an allow rule Windows made from its own first-run prompt, or the USB rule below when the PC is on Ethernet and the phone on the same subnet), so the Public warning (`NetworkCategory`) shows only when no phone packet has arrived: in Home and the tray after 30 s with none, and on the Connection page as soon as the network is Public and none has arrived. A separate **USB rule** opens the port on Public wired adapters to their own subnet only, for USB tethering. Adding either asks for UAC once. Block rules Windows made from a dismissed first-run prompt are removed with them.

## Direct USB cable

With **USB direct** on (default), `GyroPhone.Windows/AoaUsb.cs` switches a phone on the PC's USB port into Android Open Accessory mode (the phone must be in "No data transfer" mode) and `UsbBridge` carries the cable into the UDP server on loopback ([Connection](phone-connection.md#usb)). Thread gp-usb looks at the USB devices when Windows reports a device interface arriving or leaving (`DeviceChangeNotifier`, CM_Register_Notification), so each step of the switch and a pulled cable are handled at once; it also looks every 250 ms while the phone is switching and once a second otherwise, in case a notification is missed.

## Key files

`.agents/hooks/install`, `GyroPhone.Tray/CompanionUpdater.cs`, `UpdateFinisher.cs`, `Watcher.cs`, `Program.cs`, `GyroPhone.Windows/AutoStart.cs`, `FirewallHelper.cs`, `NetworkCategory.cs`, `AoaUsb.cs`, `UsbNative.cs`, `GyroPhone.Host/UsbBridge.cs`.
