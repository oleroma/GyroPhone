# Builds the pointer's gun models in Blender (bpy) and exports one GLB per gun, plus boxes.json (the box models of
# Guns.cs, for the viewer's overlay).  Usage: python build.py -- <out dir> [pistol] [smg] [ar] [battle] [shotgun]
# Frame as Guns.cs: x right, y up, z toward the stock (exported with z kept, export_yup=False).
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import *
import guns

if __name__ == '__main__':
    args = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else ['.']
    out = args[0]; names = args[1:] or list(guns.SPEC)
    os.makedirs(out, exist_ok=True)
    for nm in names:
        reset()
        root_name, objs, info = guns.gun(nm)
        root = bpy.data.objects.new(root_name, None); bpy.context.collection.objects.link(root)
        for o in objs: o.parent = root
        export(objs + [root], os.path.join(out, nm + '.glb'))
        print(nm, 'objects', len(objs), 'source', info['src'], 'scale %.4f' % info['scale'], 'pieces left out', info['dropped'])
    json.dump({k: dict(boxes=v['boxes'], muzzle=v['muzzle'], scope=v['scope']) for k, v in guns.BOX.items()}, open(os.path.join(out, 'boxes.json'), 'w'))
