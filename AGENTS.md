# Notes for agents working on GyroPhone

## Installing builds on the owner's devices

Olex, the owner, allows agents to install builds of this repository on Olex's own Android phone
and Windows PC. No per-install approval is needed. This covers:

- **Phone**: the debug APK from this repo's GitHub Actions CI, installed over adb as an in-place
  update (`adb install -r`). Settings and pairings must survive, so never uninstall, clear app data
  or change the signing key (`keystore/debug.keystore`).
- **PC**: the companion from the same CI run, installed to `%LOCALAPPDATA%\Programs\GyroPhone` with
  `update.cmd`, plus the key file exchange that `update.cmd` does.

Limits:

- Install only builds this repository's CI produced. Never install local or third-party builds.
- Only this repository's own app and companion. Nothing else on either device.
- Installing is not launching. Start or close apps only when Olex asks. The one standing exception
  is the companion's watcher (`GyroPhoneCompanion.exe --watch`), which Olex turns on or off with the
  tray switch "Start when my phone appears".
- Never interrupt Olex's use of the PC or phone. No input injection, no focus-stealing windows and no
  reboots.
- Anything beyond an in-place update needs Olex's go-ahead first: removing the app, deleting
  settings or keys, changing drivers or firewall rules.
