"""Read-only packet checks plus a small generated validation report.

These checks verify evidence/artifact integrity, not game runtime behavior.
Run from an extracted packet: python tools/verify_packet.py
"""
from pathlib import Path
import hashlib, json, re

ROOT=Path(__file__).resolve().parent.parent
SRC=ROOT/'evidence/sources'
checks=[]
witnesses=[]

def check(name,ok,detail):
    checks.append({'name':name,'passed':bool(ok),'detail':detail})
    if not ok: raise AssertionError(name+': '+str(detail))

def data(p):return json.loads((ROOT/p).read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def text_for(cls):
    filename='ao.javap.txt' if cls=='game.units.ao' else 'com.corrodinggames.rts.'+cls+'.javap.txt'
    return SRC/'bytecode'/filename

def method(cls,marker):
    p=text_for(cls);lines=p.read_text().splitlines()
    hits=[i for i,line in enumerate(lines) if re.match(r'^  \S',line) and marker in line]
    check(cls+' method '+marker,len(hits)==1,{'hits':len(hits)})
    start=hits[0];end=start+1
    while end<len(lines) and not re.match(r'^  \S',lines[end]):end+=1
    body='\n'.join(lines[start:end])
    witnesses.append({'path':p.relative_to(ROOT).as_posix(),'line':start+1,'selector':marker,'text':body})
    return body

def window(cls,needle,before=3,after=12):
    p=text_for(cls);lines=p.read_text().splitlines()
    hits=[i for i,l in enumerate(lines) if needle in l]
    check(cls+' contains '+needle,bool(hits),{'matches':len(hits)})
    start=max(0,hits[0]-before);end=min(len(lines),hits[0]+after+1)
    body='\n'.join(lines[start:end])
    witnesses.append({'path':p.relative_to(ROOT).as_posix(),'line':start+1,'selector':needle,'text':body})
    return body

manifest=data('SOURCE_MANIFEST.json')
bad=[]
for f in manifest['files']:
    p=ROOT/f['path']
    if not p.is_file() or p.stat().st_size!=f['bytes'] or sha(p)!=f['sha256']:bad.append(f['path'])
check('source hashes',not bad,{'files':len(manifest['files']),'mismatches':bad})
required=['TERRAIN.md','MOVEMENT.md','VISION.md','TARGETING.md','UNIT_CATALOG_CANDIDATES.json','CONFLICTS.md','CODE_POINTERS.md']
check('seven required deliverables',all((ROOT/p).is_file() for p in required),required)

catalog=data('UNIT_CATALOG_CANDIDATES.json')
check('core coverage',len(catalog['units'])==10 and len(catalog['aliasMap'])==14,{'units':len(catalog['units']),'aliases':len(catalog['aliasMap'])})
priority={'cost','movementType','maxSpeed','sightRange','sightRangeWhileNotBuilt','attackRange','canAttack','canAttackLand','canAttackAir','canAttackUnderwater','building','mobile'}
field_count=0
for u in catalog['units']:
    check('required fields '+u['id'],priority<=u['fields'].keys(),sorted(u['fields']))
    for key,f in u['fields'].items():
        field_count+=1
        assert {'value','status','source'}<=f.keys() and f['source'],(u['id'],key)
        for s in f['source']+f.get('conditionSource',[]):
            p=ROOT/s['path'];assert p.is_file(),(u['id'],key,s)
            if 'line' in s:
                assert 0<s['line']<=len(p.read_text(encoding='utf-8-sig').splitlines()),(u['id'],key,s)
check('field-level provenance',True,{'fields':field_count,'allSourcesExist':True})

maps=data('TERRAIN_MAP_CANDIDATES.json')['maps']
check('nine original maps',len(maps)==9,{'maps':len(maps)})
tile_total=0
for m in maps:
    assert sha(ROOT/m['source']['path'])==m['sha256']
    assert m['tileWidth']==m['tileHeight']==20
    for ts in m['tilesets']:assert sha(ROOT/ts['resolvedPath'])==ts['sha256']
    g=data(m['terrainGridCandidate']);n=m['width']*m['height'];tile_total+=n
    for mode,runs in g['costRle'].items():
        assert sum(count for value,count in runs)==n,(m['label'],mode)
        actual={}
        for v,count in runs:
            assert v in (-1,0,40) and count>0
            actual[str(v)]=actual.get(str(v),0)+count
        assert actual==m['terrainCostHistogramCandidate'][mode]
check('map dependency hashes and RLE lengths/histograms',True,{'tilesAcrossMaps':tile_total,'movementGrids':len(maps)*8})
check('no native override sample',all(not m['hasPathingOverride'] for m in maps),'All nine source TMX files omit PathingOverride')
check('resource aggregation',sum(m['resourcesItemsLayer'] for m in maps)==98 and all(m['resourcesItemsLayer']==m['resourcesAllInstantiatedLayers'] for m in maps),{'total':98})

ao=method('game.units.ao','static strictfp {};')
mapping=dict((field,name) for name,field in re.findall(r'// String ([A-Z_]+).*?putstatic[^\n]*// Field ([a-h]):',ao,re.S))
check('frozen movement enum',mapping==dict(zip('abcdefgh',['NONE','LAND','BUILDING','AIR','WATER','HOVER','OVER_CLIFF','OVER_CLIFF_WATER'])),mapping)
for cls,marker,token in [
    ('game.units.e.b','float z();','float 0.8f'),('game.units.e.b','float A();','float 3.8f'),
    ('game.units.e.b','float C();','float 0.04f'),('game.units.e.b','float D();','float 0.1f'),
    ('game.units.e.b','float B();','float 0.35f'),('game.units.e.b','float m();','float 30.0f'),
    ('game.units.e.b','boolean l();','iconst_0'),
    ('game.units.e.f','float m();','float 160.0f'),('game.units.e.f','float z();','float 0.8f'),
    ('game.units.e.f','float A();','float 1.9f'),('game.units.e.f','float B();','float 0.2f'),
    ('game.units.e.f','float C();','float 0.05f'),('game.units.e.f','float D();','float 0.1f'),
    ('game.units.e.f','boolean af();','iconst_1'),('game.units.e.f','boolean l();','iconst_1'),
    ('game.units.ar$52','int c();','sipush        500'),('game.units.ar$16','int c();','sipush        800'),
    ('game.units.ar$12','int c();','sipush        700'),('game.units.ar$23','int c();','sipush        1000'),
    ('game.units.y','int s();','bipush        15'),('game.units.y','boolean ae();','iconst_0'),
    ('game.units.y','boolean af();','iconst_1'),('game.units.y','boolean ag();','iconst_1'),
    ('game.units.d.i','boolean l();','iconst_0'),('game.units.d.d','float z();','fconst_0'),
    ('game.units.d.d','ao h();','/ao.a:'),('game.units.e.j','ao h();','/ao.b:')]:
    body=method(cls,marker)
    check(cls+' constant '+marker,token in body,{'expectedToken':token})

builder_z=next(x['text'] for x in witnesses if x['path'].endswith('units.e.b.javap.txt') and x['selector']=='float z();')
check('builder liquid speed branch','cK:()Z' in builder_z and 'float 0.6f' in builder_z,'cK conditional .6 / .8')
for key,token in [('fogOfWarSightRange','bipush        15'),('fogOfWarSightRangeWhileNotBuilt','iconst_m1'),('isBuilding','iconst_0')]:
    body=window('game.units.custom.ag','// String '+key)
    check('loader default '+key,token in body,token)

override=method('gameFramework.k.i','void d();')
check('override resets terrain byte before restrictions',bool(re.search(r'480: iconst_0\s+481: bastore\s+482: aload',override)) and 'game/b/b.x:' in override,'Frozen bytecode offsets 480–481; full method retained')
visible=method('game.b.b','boolean a(float, float, com.corrodinggames.rts.game.n);')
check('visibility uses player.N and threshold5','game/n.N:[[B' in visible and 'iconst_5' in visible,'Map caches not used by current-visible method')
fogcache=method('game.b.b','void l();')
check('smoothFog caches initialized127','bipush        127' in fogcache and 'Field M:[[B' in fogcache and 'Field N:[[B' in fogcache,'Map b M/N')
for cls,marker in [('game.units.y','boolean k(com.corrodinggames.rts.game.units.am);'),('game.units.custom.j','boolean k(com.corrodinggames.rts.game.units.am);'),
                   ('game.units.custom.j','void c(boolean);'),('game.units.y','void c(boolean);'),
                   ('game.units.am','boolean cH();'),('game.units.am','boolean cK();'),
                   ('game.b.b','void a(float, float, int, com.corrodinggames.rts.game.n, boolean);')]:
    method(cls,marker)
for s in ['none','map','los']:
    window('game.b.b','// String '+s,before=2,after=6)

def ini_value(file,section,key):
    active=None
    for line in (SRC/'frozen/assets/units'/file).read_text().splitlines():
        s=line.strip()
        if s.startswith('['):active=s.strip('[]');continue
        if active==section and not s.startswith(('#',';')) and ':' in s:
            k,v=s.split(':',1)
            if k.strip()==key:return v.strip()
for f,v in [('mechs_large/mech_artillery.ini','1400'),('mechs_large/mech_lightning.ini','5200')]:
    check('corrected price '+f,ini_value(f,'core','price')==v,v)
check('extractor T2 price separation',ini_value('extractor/extractor.ini','action_upgradeT2','price')=='1400' and ini_value('extractor/extractorT2.ini','core','price')=='2100','1400 upgrade versus 2100 type price')

report={'scope':'Static evidence and packet consistency only; NOT engine regression / NOT E4','allPassed':all(c['passed'] for c in checks),
        'checkCount':len(checks),'sourceFiles':len(manifest['files']),'catalogFields':field_count,'maps':len(maps),'resourceTiles':98,
        'checks':checks,'runtimeTestsPerformed':False,'nativeMatchesPerformed':False,'productFilesModified':False}
(ROOT/'STATIC_VERIFICATION.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
out=['# 冻结字节码证据摘录','', '本文件由 verify_packet.py 从冻结 JAR 的 javap 文本定向抽取。只证明静态指令，非运行测试。','']
seen=set()
for w in witnesses:
    ident=(w['path'],w['line'])
    if ident in seen:continue
    seen.add(ident)
    out += [f"## {w['selector']}",'',f"来源：`{w['path']}`，起始行 {w['line']}。",'', '```text',w['text'],'```','']
(ROOT/'evidence/bytecode_witnesses.md').write_text('\n'.join(out),encoding='utf-8')
print(json.dumps({k:report[k] for k in ['allPassed','checkCount','sourceFiles','catalogFields','maps','resourceTiles','runtimeTestsPerformed']},ensure_ascii=False))
