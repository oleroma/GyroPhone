"""Checks the schematic file against the netlist written in gen_sch.py: every pin on the intended net."""
import collections, sys
import os, subprocess, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from sexp import parse, uq, flat, pins
net = os.path.join(tempfile.mkdtemp(), 'board.net')
subprocess.run(['kicad-cli', 'sch', 'export', 'netlist', '-o', net, os.path.join(HERE, 'back-button-board.kicad_sch')], check=True, capture_output=True)
T=parse(open(net, encoding='utf-8').read())
nets={}
for e in T:
    if isinstance(e,list) and e[0]=='nets':
        for n in e[1:]:
            name=[uq(i[1]) for i in n if isinstance(i,list) and i[0]=='name'][0]
            nodes=set()
            for i in n:
                if isinstance(i,list) and i[0]=='node':
                    d={j[0]:uq(j[1]) for j in i[1:] if isinstance(j,list)}
                    nodes.add((d['ref'],d['pin']))
            nets[name.lstrip('/')]=nodes
src=open(os.path.join(HERE, 'gen_sch.py'), encoding='utf-8').read().split('# ---------------------------------------------------------------- writer')[0]
ns={'__name__':'x','__file__':os.path.join(HERE,'gen_sch.py')}; exec(compile(src,'g','exec'),ns)
want=collections.defaultdict(set)
for p in ns['P']:
    if p['lib']=='power': continue
    for x in pins(flat(p['lib'],p['sym'])):
        net=p['nets'].get(x['num'],p['nets'].get(x['name']))
        if net: want[net].add((p['ref'],x['num']))
bad=0
for n,s in want.items():
    g=nets.get(n)
    if g!=s: bad+=1; print("MISMATCH",n,"missing",s-(g or set()),"extra",(g or set())-s)
for n,s in nets.items():
    if n not in want and not n.startswith('unconnected'): print("EXTRA NET",n,s)
for n,s in want.items():
    if len(s)<2: print("ONE-PIN NET",n,s)
print(len(nets),"nets;",len(want),"wanted; mismatches:",bad)
sys.exit(1 if bad else 0)
