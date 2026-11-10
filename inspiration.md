# Inspiration and third-party names

GyroPhone was written with the help of AI (Claude). This page lists, by name, every product, project and piece of other people's work that shaped it or is named in it, and how it was used. GyroPhone is not affiliated with or endorsed by any of them. All trademarks belong to their owners.

## Ideas and approaches

- **Alpakka** (Input Labs, [alpakka_firmware](https://github.com/inputlabs/alpakka_firmware)): the overall idea of a gyro-first controller that moves the mouse. The README calls GyroPhone "in the spirit of" Alpakka, and the gyro-to-mouse code is described as "Alpakka / JoyShockMapper style". No Alpakka code is included.
- **JoyShockMapper** (Jibb Smart): the "player space" gyro mode and its yaw easing ("relax factor") follow the approach JoyShockMapper describes. No JoyShockMapper code is included.
- **Steam Input** (Valve): the idea of split touch zones and radial menus (listed in the spec as "Steam Input style"), and Steam Input as a way to play with GyroPhone's pads.
- **Logitech K380**: the built-in Keyboard layout copies the K380's key rows.

## Controllers and devices named or emulated

- **Sony DualSense** and **DualShock 4**: GyroPhone can present itself to Windows as one. `driver/src/dualsense.h` holds a wired DualSense's USB IDs (054C:0CE6) and HID report descriptor so games accept it. The on-screen controller drawing was first laid out as a DualSense.
- **Microsoft Xbox 360 / Xbox** controllers: output mode (through ViGEmBus) and a controller diagram style.
- **GameSir X5 / X5 Lite**: the clip-on controller GyroPhone was built and tested with. It has its own controller diagram, and an early app icon showed a phone in an X5 Lite grip (since replaced by an original icon).
- **Nintendo**, **PlayStation**, **8BitDo**, **Razer Kishi**, **Backbone One**: recognised by name so the app picks the right button labels and diagram.
- **OnePlus 15**: the reference phone used for testing; named in docs, code comments and test data.

## Games named

- **Counter-Strike 2, Apex Legends, Valorant, Overwatch 2, Destiny 2, Titanfall 2, Quake Champions**: sensitivity presets (how far each game's camera turns per mouse count), so gyro speed can be set in real-world degrees.
- **The Finals**: named in the README as a game with its own controller gyro.

## Software and libraries used

- **ViGEmBus** (Nefarius): the virtual Xbox 360 / DualShock 4 driver the Windows companion uses. Installed separately by the user.
- **Material Icons** (Google, Apache 2.0): the edit menu's icon paths are copied from Material Icons.
- **Android**, **Windows**, **Windows Driver Kit / Virtual HID Framework**, **Segoe UI** (Microsoft): platforms and system fonts used, not redistributed.
- **Obtainium**: used to install test builds on the phone.

## History

On 2026-11-01 most product names were taken out of the code and README (commit `74fe079`). Several came back in later work, and the Alpakka and JoyShockMapper credits were put back on 2026-11-10. This list covers everything named then and now.
