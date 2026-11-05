# PC: install and update

## Where it lives

The companion is one self-contained exe at a fixed path, **`%LOCALAPPDATA%\Programs\GyroPhone\GyroPhoneCompanion.exe`**, so its firewall rule and Run entries stay valid across updates. Settings and keys are in `%APPDATA%\GyroPhone` and survive updates ([Companion](pc-companion.md)). No installer, no admin rights needed except for the firewall rule.

## Installing a build

`.agents/hooks/install` (run on the PC by `agent-install` after `agent-build pc`, see [Build and release](build-release.md)):

1. **Companion**: copies the new exe in only when the installed one is from another build (the exe is stamped `<version>-<commit>`; `VERSION.txt` names the build in place). A running exe is never touched: the new build waits next to it as **`GyroPhoneCompanion.exe.new`**. With no companion or watcher running it is copied straight in.
2. **Key files**: copies the phone's `phone.pub` (over adb) to `%APPDATA%\GyroPhone\authorized_phones\` and the PC's `host.pub` to the phone's `keys/trusted_hosts/`, so the phone connects over Wi-Fi with no code. Only public keys move. A PC whose companion never ran gets its key from `GyroPhoneCompanion.exe --init-keys` (makes the key and exits, no window).
3. It never starts or closes the apps.

The APK is installed by `agent-install` with `adb install -r` (an in-place update; same debug signing key `keystore/debug.keystore`, never an uninstall).

## Self-update

`GyroPhone.Tray/CompanionUpdater.cs` checks every 5 s for `<exe>.new`. When one is there, the running companion stops the watcher, starts the new build as an **update finisher** (`GyroPhoneCompanion.exe.new --finish-update <pid> [--tray] [--watch]`) and exits cleanly (release-all, HOST_BYE EXIT). The finisher (`UpdateFinisher.cs`) waits for it to exit, copies itself over the exe (retrying while the file is still held), and starts the companion and/or watcher again from the usual path with `--updated`, which then says it was updated. It always restarts, even with a phone connected; the phone reconnects by itself (it waits up to 15 s for a PC that said it exited before trying another).

If the copy fails, the old build starts again with `--update-failed` and doesn't retry that `.new` until it is installed again. While only the watcher runs, the watcher does the same. Local builds (no CI version) never update themselves.

## Startup

- **Start with Windows** (App page, default off): the `GyroPhoneCompanion` entry in the per-user Run key (`AutoStart`).
- **Start when my phone appears** (App page): the `GyroPhoneWatcher` Run entry runs `--watch` at login ([Companion](pc-companion.md#watcher)). Besides Start with Windows, this is the only way the companion starts by itself.
- One companion per session (a named mutex); `--restarted` (from Restart as administrator) waits for the old one to exit.

## Firewall

`FirewallHelper`: one inbound UDP allow rule for the exe's path, **Private and Domain profiles only**; Public networks stay closed (the Connection page and Home warn when the Wi-Fi is Public, `NetworkCategory`). A separate **USB rule** opens the port on Public wired adapters to their own subnet only, for USB tethering. Adding either asks for UAC once. Block rules Windows made from a dismissed first-run prompt are removed with them.

## Direct USB cable

With **USB direct** on (default), `GyroPhone.Windows/AoaUsb.cs` switches a phone on the PC's USB port into Android Open Accessory mode (the phone must be in "No data transfer" mode) and `UsbBridge` carries the cable into the UDP server on loopback ([Connection](phone-connection.md#usb)).

## Key files

`.agents/hooks/install`, `GyroPhone.Tray/CompanionUpdater.cs`, `UpdateFinisher.cs`, `Watcher.cs`, `Program.cs`, `GyroPhone.Windows/AutoStart.cs`, `FirewallHelper.cs`, `NetworkCategory.cs`, `AoaUsb.cs`, `UsbNative.cs`, `GyroPhone.Host/UsbBridge.cs`.
