from pathlib import Path
import base64, bisect, collections, gzip, hashlib, json, re, shutil, struct, xml.etree.ElementTree as ET, zlib

WORK = Path(__file__).resolve().parent
if (WORK.parent/'evidence/sources').is_dir():
    OUT = WORK.parent
    SRC = OUT/'evidence/sources'
    REFERENCES = SRC/'references'
else:
    SRC = WORK / 'desktop_sources'
    OUT = WORK.parent / 'output/knowledge_packet_2026-09-29'
    REFERENCES = WORK/'references'
OUT.mkdir(parents=True, exist_ok=True)
SOURCES = OUT / 'evidence/sources'
if SRC.resolve() != SOURCES.resolve(): shutil.copytree(SRC, SOURCES, dirs_exist_ok=True)
if REFERENCES.resolve() != (SOURCES/'references').resolve(): shutil.copytree(REFERENCES, SOURCES/'references', dirs_exist_ok=True)
GAME_SHA = '8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9'
BASE = 'G:\\deepseek 工作台'
GAME = BASE + '\\游戏环境\\rustedwarfare PC 1.15 原版'
RA = BASE + '\\游戏资料\\30_GitHub原始仓库\\skywater275__rw_analysis'

def dump(name, data):
    p = OUT / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def source(path, selector, level='E1', line=None):
    d = {'path':'evidence/sources/'+path, 'selector':selector, 'evidenceLevel':level}
    if line is not None: d['line'] = line
    return d

def raw(rel, selector, needle=None):
    p = 'rw_analysis/02-decompiled/com/corrodinggames/rts/'+rel+'.java'
    lines = (SRC/p).read_text(encoding='utf-8').splitlines()
    found = [i for i,l in enumerate(lines,1) if (needle or selector) in l]
    return source(p,selector,line=found[0] if found else None)

def bc(cls, signature):
    p = 'bytecode/'+ ('ao.javap.txt' if cls == 'game.units.ao' else 'com.corrodinggames.rts.'+cls+'.javap.txt')
    lines = (SRC/p).read_text().splitlines()
    found = [i for i,l in enumerate(lines,1) if signature in l]
    if not found: raise ValueError((cls,signature))
    return source(p,signature,line=found[0])

def field(value,status,sources,**kwargs):
    return dict(value=value,status=status,source=sources,**kwargs)

# Preserve the existing original-file hashes and add explicit provenance for the
# supplemental javap/source files. No modified decompiled source is used.
manifest = json.loads((SRC/'SOURCE_SNAPSHOT_MANIFEST.json').read_text())
old = {x['path']:x for x in manifest['files']}
ref_manifest = json.loads((REFERENCES/'references_manifest.json').read_text())
ref_entries = ref_manifest if isinstance(ref_manifest,list) else ref_manifest.get('files',[])
refs_by_name = {Path(x.get('path',x.get('local_name',x.get('name','')))).name:x for x in ref_entries}
files=[]
for p in sorted(SOURCES.rglob('*')):
    if not p.is_file(): continue
    rel=p.relative_to(SOURCES).as_posix()
    prior=old.get(rel)
    origin=prior.get('origin_path') if prior else None
    if origin:
        origin=re.sub(r'\\+',r'\\',origin)
        assert sha(p)==prior['sha256'], rel
    elif rel.startswith('bytecode/'):
        cls=rel[len('bytecode/'):].replace('.javap.txt','')
        if cls=='ao': cls='com.corrodinggames.rts.game.units.ao'
        origin=GAME+'\\game-lib.jar!/'+cls.replace('.','/')+'.class'
    elif rel.startswith('rw_analysis/'):
        origin=RA+'\\'+rel[len('rw_analysis/'):].replace('/','\\')
    elif rel.startswith('references/'):
        origin=refs_by_name.get(p.name,{}).get('origin_path')
        if not origin:
            origin='DERIVED: references_manifest.json / source PDF or XLSX; see extraction selectors'
    else:
        origin='DERIVED: acquisition manifest'
    files.append({'path':'evidence/sources/'+rel,'origin_path':origin,'bytes':p.stat().st_size,'sha256':sha(p),
                  'representation':'javap -p -c -s, static disassembly; no engine invocation' if rel.startswith('bytecode/') else 'original bytes' if prior or rel.startswith('rw_analysis/') else 'see reference manifest'})
dump('SOURCE_MANIFEST.json',dict(gameJarSha256=GAME_SHA,p1fAgentSha256=manifest['p1fAgentSha256'],repoHeads=manifest['repo_heads'],files=files))

# TMX decoder and static terrain-cost mirror. It intentionally excludes live
# buildings, units, path-island caching and all fog/visibility decisions.
ASSETS=SRC/'frozen/assets'
def props(e):
    return {p.get('name'):p.get('value','') for p in e.findall('properties/property')}

def resolve_tsx(s):
    parts=s.replace('\\','/').split('/')
    candidates=[s]+['/'.join(parts[-n:]) for n in (3,2,1) if len(parts)>=n]
    for k,c in enumerate(dict.fromkeys(candidates)):
        p=ASSETS/'tilesets'/c
        if p.is_file(): return p.resolve(), 'direct' if k==0 else 'suffix_fallback'
    raise FileNotFoundError(s)

def decode(layer,w,h):
    assert int(layer.get('width'))==w and int(layer.get('height'))==h
    d=layer.find('data'); enc=d.get('encoding'); comp=d.get('compression')
    if enc=='base64':
        b=base64.b64decode(d.text or '')
        if comp=='zlib': b=zlib.decompress(b)
        elif comp=='gzip': b=gzip.decompress(b)
        elif comp: raise ValueError(comp)
        assert len(b)==w*h*4
        out=list(struct.unpack('<'+'I'*(w*h),b))
    elif enc=='csv': out=[int(v) for v in (d.text or '').replace('\n','').split(',') if v.strip()]
    elif enc is None: out=[int(t.get('gid')) for t in d.findall('tile')]
    else: raise ValueError(enc)
    assert len(out)==w*h
    return out

def flags(properties, layer):
    if 'showFog' in properties or 'unit' in properties or 'customUnit' in properties: return None
    if layer.lower()=='units' and properties: return None
    p=properties
    return {'water':'water' in p,'waterBridge':'water-bridge' in p,
            'lava':bool({'lava','lava-cliff'} & p.keys()),
            'cliff':bool({'cliff','cliff-soft','lava-cliff'} & p.keys()),
            'largeObstacle':bool({'large-cliff','trees'} & p.keys()),
            'resource':'res_pool' in p,'blockBuildings':'block-buildings' in p,
            'cost':-1 if {'large-rock','block-land'} & p.keys() else 40 if 'small-rock' in p else 0}

def cost(g,it,ov,movement):
    if movement in ('AIR','NONE'): return 0
    aw=movement in ('WATER','HOVER','OVER_CLIFF_WATER')
    ac=movement in ('HOVER','OVER_CLIFF','OVER_CLIFF_WATER')
    al=movement in ('OVER_CLIFF','OVER_CLIFF_WATER')
    def blocked(t): return (t['water'] and not aw) or (t['cliff'] and not ac) or (t['largeObstacle'] and not al) or t['lava'] or (movement=='WATER' and not t['water'] and not t['waterBridge'])
    c=-1 if g and blocked(g) else 0
    if it:
        if (movement=='LAND' and it['resource']) or (it['largeObstacle'] and not al): c=-1
        if c==0: c=it['cost']
    if g and c==0: c=g['cost']
    if ov:
        c=-1 if blocked(ov) else ov['cost']
    return c

def rle(vals):
    out=[]
    for v in vals:
        if out and out[-1][0]==v: out[-1][1]+=1
        else: out.append([v,1])
    assert sum(n for v,n in out)==len(vals)
    return out

names={'Beach':'登岛','Big':'巨岛','Dire':'直岛','Fire':'火桥','Hills':'山丘','Ice':'冰岛','Lake':'湖陆','Small':'小岛','Two':'两极'}
maps=[]
for mapfile in sorted((ASSETS/'maps/skirmish').glob('*.tmx')):
    root=ET.parse(mapfile).getroot()
    w,h,tw,th=[int(root.get(k)) for k in ('width','height','tilewidth','tileheight')]
    assert root.get('orientation')=='orthogonal'
    sets=[]; palette={}; deps=[]
    for ts in root.findall('tileset'):
        first=int(ts.get('firstgid')); tsx=ts.get('source')
        if tsx:
            sp,method=resolve_tsx(tsx); element=ET.parse(sp).getroot()
        else: sp=mapfile; element=ts; method='inline'
        deps.append({'firstgid':first,'sourceAttribute':tsx,'resolvedPath':'evidence/sources/'+sp.relative_to(SRC).as_posix(),
                     'sha256':sha(sp),'resolution':method})
        sets.append((first,{int(t.get('id')):props(t) for t in element.findall('tile')},len(deps)-1))
    sets.sort(); firsts=[s[0] for s in sets]
    layers={}; layer_info=[]; resources=set(); item_resources=set(); flipped=0
    used_props=collections.Counter()
    for lay in root.findall('layer'):
        name=lay.get('name'); gids=decode(lay,w,h); fs=[]; pc=collections.Counter(); res=[]; skip=0
        for idx,raw_gid in enumerate(gids):
            gid=raw_gid & 0x1fffffff
            if raw_gid & 0xe0000000: flipped+=1
            if gid==0: fs.append(None); continue
            ti=bisect.bisect_right(firsts,gid)-1
            assert ti>=0
            first,tprops,dep=sets[ti]; local=gid-first; p=tprops.get(local,{})
            key=str(gid)
            palette[key]={'gid':gid,'localId':local,'tilesetIndex':dep,'properties':p}
            pc.update(p.keys()); f=flags(p,name); fs.append(f)
            if f is None: skip+=1
            elif f['resource']:
                coord=(idx%w,idx//w); res.append(list(coord)); resources.add(coord)
                if name.lower() in ('items','objects'): item_resources.add(coord)
        lname=name.lower(); assert lname not in layers
        layers[lname]=fs
        used_props.update(pc)
        layer_info.append({'name':name,'nonzeroGids':sum(bool(g & 0x1fffffff) for g in gids),
                           'nonTerrainControlTiles':skip,'propertyCounts':dict(sorted(pc.items())),'resourceCoordinates':res,
                           'encoding':lay.find('data').attrib})
    assert 'ground' in layers
    ground=layers['ground']; items=layers.get('items',layers.get('objects',[None]*(w*h))); override=layers.get('pathingoverride',[None]*(w*h))
    movements=['LAND','WATER','HOVER','OVER_CLIFF','OVER_CLIFF_WATER','BUILDING','AIR','NONE']
    grids={}; counts={}
    for movement in movements:
        vals=[cost(*args,movement) for args in zip(ground,items,override)]
        grids[movement]=rle(vals); counts[movement]=dict(sorted(collections.Counter(vals).items()))
    label=next(v for k,v in names.items() if k in mapfile.name)
    mid=mapfile.name[4:].split(' (')[0].split('_(')[0].replace(' ','_').lower()
    # Coordinate arrays are offline public-map evidence only, never live enemy state.
    grid_rel='terrain_grids/'+mid+'.json'
    dump(grid_rel,{'schemaVersion':1,'mapFile':mapfile.name,'mapSha256':sha(mapfile),'status':'DERIVED_STATIC_UNCALIBRATED',
                  'width':w,'height':h,'order':'TMX_ROW_MAJOR_Y_TIMES_WIDTH_PLUS_X','rlePair':'[signed terrain cost, run length]',
                  'engineIndex':'x*height+y','excluded':['live_building_cost','live_unit_cost','clearance','path_group','visibility'],
                  'costRle':grids})
    mapinfo=[props(o) for o in root.findall('.//object') if o.get('name','').lower()=='map_info']
    maps.append({'id':mid,'label':label,'filename':mapfile.name,'source':source('frozen/assets/maps/skirmish/'+mapfile.name,'TMX map/layer/tileset'),
                 'sha256':sha(mapfile),'width':w,'height':h,'tileWidth':tw,'tileHeight':th,'mapInfo':mapinfo,
                 'hasPathingOverride':'pathingoverride' in layers,'flippedGids':flipped,
                 'tilesets':deps,'usedTilePalette':list(palette.values()),'layers':layer_info,
                 'resourcesAllInstantiatedLayers':len(resources),'resourcesItemsLayer':len(item_resources),
                 'resourceCoordinatesAllLayers':[list(x) for x in sorted(resources)],
                 'terrainCostHistogramCandidate':counts,'terrainGridCandidate':grid_rel})
dump('TERRAIN_MAP_CANDIDATES.json',{'schemaVersion':1,'gameJarSha256':GAME_SHA,'status':'STATIC_ASSETS_WITH_DERIVED_UNCALIBRATED_COSTS',
    'sourceRules':[raw('game/b/g','tile properties','public static g a('),raw('game/b/e','resource registration','if (g2.i)'),
                   raw('game/b/b','tileset suffix fallback','public InputStream d(String string, String string2)'),raw('gameFramework/k/i','terrain cost d()','void d()')],
    'warning':'Offline evidence only. Cost mirror has not been compared with the live engine. Do not preload unseen terrain into Recon.',
    'maps':maps})
print(json.dumps({'maps':[{k:m[k] for k in ('label','width','height','resourcesAllInstantiatedLayers','resourcesItemsLayer','hasPathingOverride')} for m in maps],
                  'fallbacks':[(m['label'],t['sourceAttribute'],t['resolvedPath']) for m in maps for t in m['tilesets'] if t['resolution']=='suffix_fallback'],
                  'source_files':len(files)},ensure_ascii=False,indent=2))

# Catalog helpers: values resolve through explicit INI copyFrom sources only.
# Complex mods/macros are not silently interpreted by this focused extractor.
def ini(p,stack=()):
    p=p.resolve()
    if p in stack: raise ValueError('copyFrom cycle')
    own={}; section=None
    for num,line in enumerate(p.read_text(encoding='utf-8-sig').splitlines(),1):
        s=line.strip()
        if not s or s.startswith(('#',';')): continue
        if s.startswith('[') and s.endswith(']'): section=s[1:-1]; continue
        if ':' in s:
            key,value=s.split(':',1); own[(section,key.strip())]=(value.strip(),source(p.relative_to(SRC).as_posix(),f'[{section}] {key.strip()}',line=num))
    out={}
    copies=own.get(('core','copyFrom'))
    if copies:
        cp=[x.strip() for x in copies[0].split(',')]
        assert len(cp)==1, 'focused extractor: inspect multi-parent precedence before extending'
        out.update(ini(p.parent/cp[0],stack+(p,)))
    out.update(own)
    return out

def literal(s):
    if s.lower() in ('true','false'): return s.lower()=='true'
    try: return int(s)
    except ValueError:
        try: return float(s)
        except ValueError: return s

def inif(data,sec,key,default=None,default_source=None,**kw):
    x=data.get((sec,key))
    if x: return field(literal(x[0]),'VERIFIED_STATIC_CONFIG',[x[1]],**kw)
    assert default_source, (sec,key)
    return field(default,'DERIVED_STATIC_DEFAULT',default_source,note='No explicit key in resolved copyFrom chain; frozen loader/default applies.',**kw)

loader='game/units/custom/ag'
loader_b='game.units.custom.ag'
js='game/units/custom/j'
sight_default=[raw(loader,'fogOfWarSightRange default 15','l5.cL.n ='),bc(loader_b,'// String fogOfWarSightRange')]
building_default=[raw(loader,'isBuilding default false','l5.aH = ab2.a'),bc(loader_b,'// String isBuilding')]
catalog=[]
for rel,aliases in [('scout/scout.ini',['scout']),('tanks/tank.ini',['tank','c_tank']),('tanks/artillery.ini',['artillery','c_artillery']),
                    ('extractor/extractor.ini',['extractor','extractorT1']),('extractor/extractorT2.ini',['extractorT2']),('turrets/turret_t1.ini',['turret','c_turret_t1'])]:
    d=ini(ASSETS/'units'/rel)
    get=lambda s,k,**kw: inif(d,s,k,**kw)
    idf=get('core','name'); uid=idf['value']; building=get('core','isBuilding',default=False,default_source=building_default)
    fields={'identity':idf,'aliases':field(aliases,'VERIFIED_STATIC_CONFIG',[idf['source'][0]]+([d['core','overrideAndReplace'][1]] if ('core','overrideAndReplace') in d else [])),
            'cost':get('core','price',unit='credits',scope='type base price; not upgrade increment'),
            'maxHp':get('core','maxHp',unit='hitpoints'),'building':building,
            'mobile':field(not building['value'],'DERIVED_STATIC',[*building['source'],raw(js,'I() mobility gate','public boolean I()')],note='Base type mobility; transported/attached/disabled state still gates movement.'),
            'movementType':get('movement','movementType'),
            'maxSpeed':get('movement','moveSpeed',unit='raw engine movement value',scope='base config at globalScale=1; not current speed'),
            'moveAccelerationSpeed':get('movement','moveAccelerationSpeed',unit='raw engine movement value'),
            'moveDecelerationSpeed':get('movement','moveDecelerationSpeed',unit='raw engine movement value'),
            'maxTurnSpeed':get('movement','maxTurnSpeed',unit='raw engine body rotation value'),
            'turnAcceleration':get('movement','turnAcceleration',unit='raw engine body rotation value'),
            'sightRange':get('core','fogOfWarSightRange',default=15,default_source=sight_default,unit='tiles'),
            'canAttack':get('attack','canAttack'),
            'canAttackLand':get('attack','canAttackLandUnits',scope='surface including water surface'),
            'canAttackAir':get('attack','canAttackFlyingUnits'),
            'canAttackUnderwater':get('attack','canAttackUnderwaterUnits'),
            'canAttackNotTouchingWater':get('attack','canAttackNotTouchingWaterUnits',default=True,
                default_source=[raw(loader,'canAttackNotTouchingWaterUnits null default','l5.et ='),raw(js,'ah() null -> true','public boolean ah()')]),
            'attackRange':get('attack','maxAttackRange',unit='world units',scope='base maximum; not a guarantee of fire'),
            'globalScale':get('core','globalScale',default=1,default_source=[raw(loader,'globalScale default 1','l5.aG =')])}
    if ('core','fogOfWarSightRangeWhileNotBuilt') in d:
        fields['sightRangeWhileNotBuilt']=get('core','fogOfWarSightRangeWhileNotBuilt',unit='tiles')
    else:
        fields['sightRangeWhileNotBuilt']=field(fields['sightRange']['value'],'DERIVED_STATIC_DEFAULT',
            fields['sightRange']['source']+[raw(loader,'unfinished sight -1 sentinel','l5.dh ='),raw(js,'c(boolean) resolves unfinished sight','if (this.cm < 1.0f && l2.dh != -1)')],unit='tiles',note='-1 loader sentinel means use current completed-unit sight stat; candidate resolves base value only.')
    if uid=='extractorT1':
        fields['upgradeToT2Cost']=get('action_upgradeT2','price',unit='credits',scope='incremental action cost')
    if uid=='extractorT2':
        base_d=ini(ASSETS/'units/extractor/extractor.ini')
        fields['upgradeFromT1Cost']=inif(base_d,'action_upgradeT2','price',unit='credits',scope='incremental action cost')
    catalog.append({'id':uid,'fields':fields,'runtimeVerification':'NOT_PERFORMED_THIS_TASK'})

def bfield(value,cls,method,**kw):return field(value,'VERIFIED_STATIC_BYTECODE',[bc('game.units.'+cls,method)],**kw)
def native_base(uid,alias,cls,enumclass,costv,building):
    src=bc('game.units.'+enumclass,' c();')
    baseclass='d.d' if building else 'e.j'
    ff={'identity':field(uid,'VERIFIED_STATIC_BYTECODE',[bc('game.units.ar','static {};')],note='Enum static initializer name -> subclass; factory binding in '+enumclass),
        'aliases':field(alias,'VERIFIED_STATIC_BYTECODE',[bc('game.units.ar','static {};')]),'cost':field(costv,'VERIFIED_STATIC_BYTECODE',[src],unit='credits',scope='T1 factory/type base price'),
        'building':bfield(building,'d.d' if building else 'am',' bI();'),
        'mobile':bfield(not building,'d.d' if building else 'w',' I();'),
        'movementType':bfield('NONE' if building else 'LAND',baseclass,' h();'),
        'sightRange':bfield(15,'y',' int s();',unit='tiles'),
        'sightRangeWhileNotBuilt':field(15,'DERIVED_STATIC',[bc('game.units.y',' int s();'),raw('game/units/y','c(boolean) reveal','public void c(boolean')],unit='tiles',note='Native y.c(boolean) uses s(); no custom unfinished override.'),
        'canAttack':bfield(not building and uid!='builder','d.i' if building else cls,' l();')}
    for fieldname,m in [('canAttackLand','ag'),('canAttackAir','af'),('canAttackUnderwater','ae'),('canAttackNotTouchingWater','ah')]:
        owner='e.f' if uid=='heavyTank' and m=='af' else 'y'
        if ff['canAttack']['value'] or fieldname=='canAttackNotTouchingWater':
            ff[fieldname]=bfield(m!='ae',owner,' '+m+'();',scope='raw base domain flag; AND with canAttack')
        else:
            ff[fieldname]=field(False,'DERIVED_EFFECTIVE_CAPABILITY',[*ff['canAttack']['source'],bc('game.units.'+owner,' '+m+'();')],
                note='Effective capability false because canAttack=false; inherited raw flag is '+str(m!='ae').lower()+'.')
    if uid=='builder':
        ff['maxSpeed']=bfield(.8,cls,' z();',unit='raw engine movement value',conditionalValue=.6,condition='cK()==true (over-liquid rule)',
            conditionSource=[bc('game.units.am',' cK();'),bc('gameFramework.utility.y',' boolean d(float, float);')])
        ff['maxTurnSpeed']=bfield(3.8,cls,' A();',conditionalValue=1.7,condition='cK()==true',unit='raw engine body rotation value')
        ff['attackRange']=field(None,'NOT_APPLICABLE',[*ff['canAttack']['source'],bc('game.units.'+cls,' m();')],unit='world units',
            note='m() returns 30, but builder cannot attack; retain as noncombat rawM=30, not weapon range.',rawM=30)
        ff['moveAccelerationSpeed']=bfield(.04,cls,' C();');ff['moveDecelerationSpeed']=bfield(.1,cls,' D();');ff['turnAcceleration']=bfield(.35,cls,' B();')
    elif building:
        ff['maxSpeed']=bfield(0,'d.d',' z();',unit='raw engine movement value')
        ff['maxTurnSpeed']=bfield(0,'d.d',' A();',unit='raw engine body rotation value')
        ff['attackRange']=field(None,'NOT_APPLICABLE',[*ff['canAttack']['source'],bc('game.units.d.i',' m();')],unit='world units',rawM=0,note='Noncombat factory.')
        for key,met,val in [('moveAccelerationSpeed','C',99),('moveDecelerationSpeed','D',99),('turnAcceleration','B',-1)]:
            ff[key]=field(None,'NOT_APPLICABLE',[*ff['mobile']['source'],bc('game.units.y',' '+met+'();')],rawInheritedValue=val,note='Immobile building; do not interpret inherited default as usable movement stat.')
        ff['upgradeToT2Cost']=bfield(2000 if uid=='landFactory' else 1500,enumclass,' c(int);',unit='credits',scope='c(2), incremental upgrade cost')
    else:
        for key,met,val in [('maxSpeed','z',.8),('maxTurnSpeed','A',1.9),('moveAccelerationSpeed','C',.05),('moveDecelerationSpeed','D',.1),('turnAcceleration','B',.2),('attackRange','m',160)]:
            ff[key]=bfield(val,cls,' '+met+'();',unit='world units' if key=='attackRange' else 'raw engine value')
    catalog.append({'id':uid,'fields':ff,'runtimeVerification':'NOT_PERFORMED_THIS_TASK'})

native_base('builder',['builder'],'e.b','ar$52',500,False)
native_base('heavyTank',['heavyTank'],'e.f','ar$16',800,False)
native_base('landFactory',['landFactory'],'d.m','ar$12',700,True)
native_base('airFactory',['airFactory'],'d.a','ar$23',1000,True)
order=['builder','scout','c_tank','heavyTank','c_artillery','landFactory','airFactory','extractorT1','extractorT2','c_turret_t1']
catalog.sort(key=lambda u:order.index(u['id']))
aliasmap={a:u['id'] for u in catalog for a in u['fields']['aliases']['value']}
dump('UNIT_CATALOG_CANDIDATES.json',{'schemaVersion':1,'gameJarSha256':GAME_SHA,'catalogRole':'STATIC_CANDIDATES_REQUIRING_RUNTIME_INTEGRATION',
    'generatedDate':'2026-09-29','baseAssetRoot':GAME+'\\assets','aliasMap':aliasmap,
    'statusDefinitions':{'VERIFIED_STATIC_CONFIG':'E1: explicit value in frozen original INI, copyFrom provenance retained; not live observation.',
      'VERIFIED_STATIC_BYTECODE':'E1: method constant/branch or binding in javap of frozen game-lib.jar.',
      'DERIVED_STATIC_DEFAULT':'E1-derived: absent key resolved using loader/default source; no live instantiation.',
      'DERIVED_STATIC':'E1-derived: combined rules; source may include repository decompilation.',
      'DERIVED_EFFECTIVE_CAPABILITY':'AND canAttack with domain flag; inherited raw values preserved in note.',
      'NOT_APPLICABLE':'Value null deliberately means no applicable weapon or mobility field; never coerce unknown to false.'},
    'limitations':['No runtime probes, native matches or E4 acceptance were performed in this task.',
        'Base speed/range can change with current unit stats, globalScale, movement modifiers, upgrade/state and per-turret restrictions.',
        'canAttackLand means surface targets, including water surface; target current altitude/submerged/touching-water state matters.',
        'Public type catalog must never be used to infer hidden enemy instance state.'],
    'units':catalog})
print('Catalog generated:',len(catalog),'canonical units;',len(aliasmap),'aliases')
