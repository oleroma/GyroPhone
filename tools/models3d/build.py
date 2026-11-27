# Builds the overlay's 3D models in Blender (bpy) and exports GLB: OnePlus 15, GP handles, 8BitDo Ultimate Mobile.
# Usage: python build.py -- <out dir> [oneplus15] [gp-handles] [8bitdo-ultimate-mobile]
# Units: 1 = 1 cm. Front view: X right, Y up, Z toward the viewer (exported with Z kept as front).
# The phone is landscape with its camera end on the left; the controllers are built around it at the origin.
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import *
import phone, eightbitdo, gp

MODELS = {
    'oneplus15': phone.oneplus15,
    'gp-handles': gp.gp_handles,
    '8bitdo-ultimate-mobile': lambda: eightbitdo.eightbitdo(stretched=True),
    '8bitdo-ultimate-mobile-retracted': lambda: eightbitdo.eightbitdo(stretched=False),   # as in the reference picture
    '8bitdo-ultimate-mobile-cage': eightbitdo.cages,   # the control cages only (Catmull-Clark; creases in the JSON beside it)
}

if __name__ == '__main__':
    args = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else ['.']
    out = args[0]; names = args[1:] or list(MODELS)
    os.makedirs(out, exist_ok=True)
    for nm in names:
        reset()
        root_name, objs = MODELS[nm]()
        root = bpy.data.objects.new(root_name, None); bpy.context.collection.objects.link(root)
        for o in objs: o.parent = root
        export(objs + [root], os.path.join(out, nm + '.glb'))
        print(nm, 'objects', len(objs), 'triangles', tri_count(objs))
        if nm.endswith('-cage'):
            import json, cage
            json.dump(dict(units='cm', note='control cages in model space (retracted); Catmull-Clark, at most 3 levels, with edge creases [a, b, weight]',
                           cages=[dict(name=e['name'], vertices=e['C'].tolist(), quads=[list(f) for f in e['F']], creases=[[a, b, v] for (a, b), v in e['crease'].items()]) for e in cage.REG]),
                      open(os.path.join(out, nm + '.json'), 'w'))
