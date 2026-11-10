# Inspiration and names mentioned

GyroPhone was written with the help of AI (Claude). No screenshots, images or code from any of the products below were given to the AI, in chats or in agent work. Names came up only in words. This page lists, by name, every product, project and piece of other people's work that is named somewhere in GyroPhone's code, docs or history, and the context it was named in. Being named here doesn't mean GyroPhone includes or depends on it. GyroPhone is not affiliated with or endorsed by any of them. All trademarks belong to their owners.

## Inspiration

- **Alpakka** (Input Labs, [alpakka_firmware](https://github.com/inputlabs/alpakka_firmware)): named as the inspiration. The README says GyroPhone is "in the spirit of" the Alpakka controller, and the gyro-to-mouse code comment says "Alpakka / JoyShockMapper style". No Alpakka code is included.
- **JoyShockMapper** (Jibb Smart): named for the "player space" gyro mode and its "relax factor" in code comments and the README. No JoyShockMapper code is included.
- **Steam Input** (Valve): named in the spec as the style for an idea (split touch zones and radial menus), and in docs as something that can sit between GyroPhone's pad and a game.
- **Logitech K380**: named as the layout the built-in keyboard's key rows follow.

## Controllers and devices

- **Sony DualSense** and **DualShock 4**: named as pads GyroPhone's PC side can appear as. The optional DualSense output is an accessibility measure for games that accept no controller other than a DualSense. Its driver header lists a wired DualSense's USB IDs and HID report layout so those games accept it. The driver is a trial: it can appear in test pre-releases, but not in GyroPhone's releases. A code comment once named the DualSense as the shape of the on-screen controller drawing.
- **Xbox 360 / Xbox**: named as an output mode and a controller diagram style.
- **GameSir X5 / X5 Lite**: named as the clip-on controller GyroPhone was tested with, and as a controller diagram style. An early app icon was described as a phone in an X5 Lite grip (since replaced by an original icon).
- **Nintendo**, **PlayStation**, **8BitDo**, **Razer Kishi**, **Backbone One**: named in the list of controllers the app recognises by name, to pick button labels and diagrams.
- **OnePlus 15**: named as the test phone, in docs, code comments and test data.

## Games

- **Counter-Strike 2, Apex Legends, Valorant, Overwatch 2, Destiny 2, Titanfall 2, Quake Champions**: named in sensitivity presets (how far each game turns per mouse count).
- **The Finals**: named in the README as an example of a game with its own controller gyro.

## Other software

- **ViGEmBus** (Nefarius): named as the separately installed driver the companion talks to for a virtual Xbox 360 / DualShock 4 pad.
- **Material Icons** (Google, Apache 2.0): named as the source of the edit menu's icon paths, which are copied from it.

## History

On 2026-11-01 most product names were taken out of the code and README (commit `74fe079`). Several came back in later work, and the Alpakka and JoyShockMapper credits were put back on 2026-11-10. This list covers everything named then and now.
