"""Tiny s-expression reader/writer for KiCad files, plus symbol-library helpers."""
import os, re
SYMDIR = next((os.environ[k] for k in ("KICAD7_SYMBOL_DIR", "KICAD8_SYMBOL_DIR", "KICAD9_SYMBOL_DIR") if k in os.environ),
              "/usr/share/kicad/symbols")
def parse(s):
    tok=re.compile(r'\s*(\(|\)|"(?:[^"\\]|\\.)*"|[^\s()]+)')
    pos=0; stack=[[]]
    while True:
        m=tok.match(s,pos)
        if not m: break
        t=m.group(1); pos=m.end()
        if t=='(':
            stack.append([])
        elif t==')':
            x=stack.pop(); stack[-1].append(x)
        else:
            stack[-1].append(t)
    return stack[0][0]
def dump(x,ind=0):
    if isinstance(x,list):
        if all(not isinstance(i,list) for i in x):
            return '('+' '.join(x)+')'
        out='('+' '.join(i for i in x if not isinstance(i,list) )
        # keep order
        out='('
        parts=[]
        for i in x:
            parts.append(dump(i,ind+1) if isinstance(i,list) else i)
        res='('; first=True
        for i,p in zip(x,parts):
            if isinstance(i,list): res+='\n'+'  '*(ind+1)+p
            else: res+=('' if first else ' ')+p
            first=False
        return res+')'
    return x
def q(s): return '"'+s.replace('\\','\\\\').replace('"','\\"')+'"'
def uq(s): return s[1:-1] if s.startswith('"') else s
_libcache={}
def lib(name):
    if name not in _libcache:
        _libcache[name]=parse(open(os.path.join(SYMDIR, name + ".kicad_sym"), encoding="utf-8").read())
    return _libcache[name]
def find_sym(libname,sym):
    L=lib(libname)
    for e in L:
        if isinstance(e,list) and e[0]=='symbol' and uq(e[1])==sym: return e
    raise KeyError(sym)
def flat(libname,sym):
    import copy
    e=copy.deepcopy(find_sym(libname,sym))
    ext=[i for i in e if isinstance(i,list) and i[0]=='extends']
    if ext:
        parent=flat(libname,uq(ext[0][1]))
        pname=uq(parent[1])
        props=[i for i in e if isinstance(i,list) and i[0]=='property']
        pn={uq(p[1]) for p in props}
        new=['symbol',q(f'{libname}:{sym}')]
        for i in parent[2:]:
            if isinstance(i,list) and i[0]=='property' and uq(i[1]) in pn: continue
            if isinstance(i,list) and i[0]=='symbol':
                i=copy.deepcopy(i); i[1]=q(uq(i[1]).replace(pname.split(':')[-1],sym,1))
            new.append(i)
        # insert props after header
        idx=2
        while idx<len(new) and not (isinstance(new[idx],list) and new[idx][0]=='property'): idx+=1
        for p in reversed(props): new.insert(idx,p)
        return new
    e[1]=q(f'{libname}:{sym}')
    return e
def pins(symexpr):
    out=[]
    def walk(x,unit):
        for i in x:
            if isinstance(i,list):
                if i[0]=='symbol':
                    m=re.search(r'_(\d+)_(\d+)$',uq(i[1])); walk(i, int(m.group(1)) if m else unit)
                elif i[0]=='pin':
                    at=[j for j in i if isinstance(j,list) and j[0]=='at'][0]
                    name=[j for j in i if isinstance(j,list) and j[0]=='name'][0]
                    num=[j for j in i if isinstance(j,list) and j[0]=='number'][0]
                    out.append(dict(unit=unit,type=i[1],name=uq(name[1]),num=uq(num[1]),x=float(at[1]),y=float(at[2]),a=float(at[3]) if len(at)>3 else 0))
    walk(symexpr,0)
    return out
