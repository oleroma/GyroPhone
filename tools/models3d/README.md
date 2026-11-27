# Overlay 3D models

The PC phone overlay's real look draws these models (see `docs/spec/pc-overlays.md`, Real look). The built files are
shipped in `companion/src/GyroPhone.Host/Models3D/*.glb`.

Build them with Blender's Python module (`pip install bpy`, Blender 5.x):

```
python tools/models3d/build.py -- build/models3d oneplus15 gp-handles 8bitdo-ultimate-mobile
```

then copy the `.glb` files from `build/models3d/` into `companion/src/GyroPhone.Host/Models3D/`.

Conventions: 1 unit = 1 cm; x right, y up, z toward the viewer (exported with z kept as the front); the phone held
landscape with its camera end on the left, the controllers built round it at the origin. Every node is one part (the
overlay moves buttons, sticks and triggers by their names, `ControllerRig`), and every material name is a slot
(`Materials.Slots`) the overlay gives its look.

`8b-cage.json` holds the 8BitDo grip's subdivision cage (Catmull-Clark, at most 3 levels, with edge creases);
`8b-outline.json` the outline traced from reference photos.
