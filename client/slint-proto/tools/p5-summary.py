"""P5 evidence: focused measurements, native controls, runtime policy and unchanged visuals."""
import json
import re
from datetime import datetime
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parent.parent/'evidence/bg-port/p5'
ANSI=re.compile(r'\x1b\[[0-9;]*m')
def clean(path):return ANSI.sub('',path.read_text())
def date(v):return datetime.fromisoformat(v.replace('Z','+00:00'))
def events(text,needle):
    result=[]
    for line in text.splitlines():
        if needle in line:
            v=json.loads(line.split('data=',1)[1]);v['_time']=line.split()[0];result.append(v)
    return result
def engine(row):
    return sum(float(e['utilization']) for e in row.get('Engines',[]) if 'engtype_3D' in e['name'])
def case_summary(prefix,lifecycle):
    text=clean(Path(str(prefix)+'-app.log'))
    rows=[json.loads(l) for l in Path(str(prefix)+'-memory.jsonl').read_text().splitlines()]
    counts=re.search(r'Menu data loaded.*?hosts=(\d+).*?modpacks=(\d+)',text)
    api=re.findall(r'API response.*?path="([^"]+)".*?status=(\d+)',text)
    assert counts and api and all(v=='200' for p,v in api)
    alloc=events(text,'Memory allocator snapshot')
    beats=events(text,'Runtime probe')
    rust=[]
    for line in text.splitlines():
        if 'Rust allocation snapshot' in line:
            m=re.search(r'rust_live_bytes=(\d+).*?rust_peak_bytes=(\d+)',line)
            rust.append((date(line.split()[0]),int(m[1]),int(m[2])))
    phases=['foreground60s','minimized30s','restored10s'] if lifecycle else ['foreground60s']
    ends={}
    for phase in phases:
        row=[r for r in rows if r['Phase']==phase][-1]
        assert row['ActualMinimized'] if phase=='minimized30s' else row['Focused'] and row['Visible'] and not row['ActualMinimized'],(str(prefix),phase,'endpoint mismatch')
        if phase!='minimized30s':assert (row['Width'],row['Height'])==(2560,1440)
        prior=[v['report'] for v in alloc if date(v['_time'])<=date(row['Time']) and v['report']]
        heartbeat=[v for v in beats if date(v['_time'])<=date(row['Time'])][-1]
        if phase!='minimized30s':
            assert heartbeat['focused'] and heartbeat['visible'] and not heartbeat['minimized'],(prefix.name,phase,'application focus state mismatch')
            if heartbeat['enabled']:assert heartbeat['active'] and heartbeat['bound'],(prefix.name,phase,'scene inactive at focused endpoint')
        a=prior[-1]
        live=[v for v in rust if v[0]<=date(row['Time'])]
        ends[phase]={**row,**{k:row[k]/2**20 for k in ['WorkingSet','Private','Dedicated','Shared']},'gpu_total_mib':(row['Dedicated']+row['Shared'])/2**20,'allocated_mib':a['allocated']/2**20,'reserved_mib':a['reserved']/2**20,'blocks_mib':[v['size']/2**20 for v in a['blocks']],'runtime_at_endpoint':heartbeat,'allocations':a['allocations'],'rust_live_mib':live[-1][1]/2**20 if live else None,'rust_peak_mib':live[-1][2]/2**20 if live else None}
    completion=[(float(a),float(b)) for a,b in re.findall(r'Scene completion window.*?average_ms=([0-9.]+).*?max_ms=([0-9.]+)',text)]
    last_min=next((r['Seconds'] for r in rows if r['Phase']=='minimized'),float('inf'))
    loaded=[r for r in rows if r['Phase']=='loaded']
    active=[r for r in loaded if r['Focused'] and last_min-r['Seconds']>0]
    # Sum only this process's 3D engines; it is a Windows counter, not inferred from completion latency.
    busy=[engine(r) for r in active]
    return {'prefix':prefix.name,'endpoints':ends,'hosts':int(counts[1]),'modpacks':int(counts[2]),'api_responses':len(api),'api_paths':sorted(set(p for p,_ in api)), 'pipeline_ms':dict((n,float(v)) for n,v in re.findall(r'pipeline="([^"]+)".*?compile_ms=([0-9.]+)',text)), 'startup_ms':dict((n,int(v)) for n,v in re.findall(r'(First live scene frame|First frame).*?elapsed_ms=(\d+)',text)), 'completion_mean_ms':float(np.mean([a for a,b in completion])) if completion else None,'completion_max_ms':max((b for a,b in completion),default=None), 'focused_3d_mean_percent':float(np.mean(busy)) if busy else None,'focused_3d_max_percent':max(busy,default=None),'appearance_ready':'Account player appearance ready' in text,'warnings':[l for l in text.splitlines() if re.search(r'\b(?:WARN|ERROR)\b',l)]}
def compare(a,b):
    keys=['WorkingSet','Private','gpu_total_mib','allocated_mib','reserved_mib']
    if a.get('rust_live_mib') is not None and b.get('rust_live_mib') is not None:keys+=['rust_live_mib']
    d={k:a[k]-b[k] for k in keys}
    d['ws_minus_allocated_mib']=d['WorkingSet']-d['allocated_mib']
    d['private_minus_allocated_mib']=d['Private']-d['allocated_mib']
    if 'rust_live_mib' in d:d['private_minus_allocated_and_rust_mib']=d['Private']-d['allocated_mib']-d['rust_live_mib']
    return d
def pair(name,left,right):
    im=Image.new('RGB',(2560,760),'#181b20');draw=ImageDraw.Draw(im)
    for i,(p,label) in enumerate([(left,'Kotlin player, no crowd / AA off'),(right,'P5 default (accepted P4a visuals)')]):
        draw.text((1280*i+8,8),label,fill='white');im.paste(Image.open(p).convert('RGB'),(1280*i,40))
    im.save(ROOT/name)
s={'visuals':{},'measurements':{'status':'pending'},'runtime':{'status':'pending'}}
for t in ['0.0','15.0','45.0']:
    path=ROOT/f'preview/default/p0-rust-{t}.png'
    if not path.exists():continue
    a=np.asarray(Image.open(path).convert('RGB'),dtype=float)
    b=np.asarray(Image.open(ROOT/f'../p4a/steve/p0-rust-{t}.png').convert('RGB'),dtype=float)
    assert np.array_equal(a,b)
    p=ROOT/f'reference/steve/kotlin-matched-{t}.png'
    if p.exists():
        ref=np.asarray(Image.open(p).convert('RGB'),dtype=float)
        s['visuals'][t]={'p4a_byte_exact':True,'kotlin_full_rgb_mae':float(np.abs(ref-a).mean())}
        pair(f'p5-matched-{t}.png',p,path)
if (ROOT/'preview/default/timing.json').exists():s['preview_timing']=json.loads((ROOT/'preview/default/timing.json').read_text())
s['compile_order']={}
for case in ['default','reflection-first']:
    p=ROOT/f'preview-{case}.log'
    if p.exists():s['compile_order'][case]=dict((n,float(v)) for n,v in re.findall(r'pipeline="(scene player [^"]+)".*?compile_ms=([0-9.]+)',clean(p)))
manifest=ROOT/'measure/manifest.json'
if manifest.exists():
    cases={}
    for name,tag in json.loads(manifest.read_text()).items():
        lifecycle=name.startswith('final-')
        assert (ROOT/f'measure/{tag}-measure.exit').read_text().strip()=='0'
        assert (ROOT/f'measure/{tag}-app.exit').read_text().strip()=='0'
        cases[name]=case_summary(ROOT/f'measure/{tag}',lifecycle)
        assert not cases[name]['warnings'],(name,cases[name]['warnings'])
    s['measurements']={'status':'passed' if (ROOT/'measure/complete.exit').exists() else 'partial','cases':cases,'deltas':{}}
    d=s['measurements']['deltas']
    if 'final-static' in cases and 'final-scene' in cases:
        for phase in ['foreground60s','minimized30s','restored10s']:d[f'final-scene-minus-static-{phase}']=compare(cases['final-scene']['endpoints'][phase],cases['final-static']['endpoints'][phase])
    if 'ab-p3' in cases:
        baseline=cases['ab-p3']['endpoints']['foreground60s']
        for name in ['skip-bloom','skip-fog','skip-copy','skip-mip','skip-final','unrendered','p2','p3-repeat']:
            key=f'ab-{name}'
            if key in cases:d[f'{name}-minus-p3']=compare(cases[key]['endpoints']['foreground60s'],baseline)
    for a,b in [('ab-scene','ab-steve'),('ab-steve','ab-p3'),('ab-p3','ab-p2')]:
        if a in cases and b in cases:d[f'{a}-minus-{b}']=compare(cases[a]['endpoints']['foreground60s'],cases[b]['endpoints']['foreground60s'])
    names={'skip-bloom':'scene bloom atlas','skip-fog':'scene valley fog march','skip-copy':'scene bilateral fog HDR copy','skip-mip':'scene linear HDR downsample','skip-final':'scene bloom tonemap saturation'}
    for case,pipe in names.items():
        if f'ab-{case}' in cases:assert pipe not in cases[f'ab-{case}']['pipeline_ms'],(case,'pipeline was not actually omitted')
    if 'ab-unrendered' in cases:assert all(n in cases['ab-unrendered']['pipeline_ms'] for n in names.values())
    if 'ab-steve' in cases:assert not cases['ab-steve']['appearance_ready']
runtime_manifest=ROOT/'runtime/manifest.json'
if runtime_manifest.exists():
    results={}
    for case,tag in json.loads(runtime_manifest.read_text()).items():
        assert (ROOT/f'runtime/{tag}-runtime.exit').read_text().strip()=='0'
        assert (ROOT/f'runtime/{tag}-app.exit').read_text().strip()=='0'
        prefix=ROOT/f'runtime/{tag}';text=clean(Path(str(prefix)+'-app.log'))
        rows=[json.loads(l) for l in Path(str(prefix)+'-memory.jsonl').read_text().splitlines()]
        beats=events(text,'Runtime probe');alloc=events(text,'Memory allocator snapshot')
        phases={}
        for phase in dict.fromkeys(r['Phase'] for r in rows):
            rs=[r for r in rows if r['Phase']==phase]
            start,end=date(rs[0]['Time']),date(rs[-1]['Time'])
            bs=[v for v in beats if 1<(date(v['_time'])-start).total_seconds() and date(v['_time'])<=end]
            busy=[engine(r) for r in rs]
            phases[phase]={'rows':len(rs),'seconds':rs[-1]['Seconds']-rs[0]['Seconds'],'beats':bs,'mean_3d_percent':float(np.mean(busy)), 'focused_values':sorted(set(r['Focused'] for r in rs)),'minimized_values':sorted(set(r['ActualMinimized'] for r in rs)),'last_working_set_mib':rs[-1]['WorkingSet']/2**20,'last_private_mib':rs[-1]['Private']/2**20,'last_gpu_mib':(rs[-1]['Dedicated']+rs[-1]['Shared'])/2**20}
        if case=='policy':
            for phase in ['unfocused','hidden','minimized']:
                bs=phases[phase]['beats'];assert len(bs)>=2,(phase,'insufficient stable runtime evidence')
                assert len({v['scene_frames'] for v in bs})==1,(phase,'scene frames advanced')
                assert max(v['elapsed'] for v in bs)-min(v['elapsed'] for v in bs)<1e-6,(phase,'animation advanced')
                assert all(not v['active'] for v in bs),(phase,'still active')
            for phase in ['focused-live','refocused','shown','restored','resized-small','scale-1.5','restored-size-scale']:
                bs=phases[phase]['beats'];assert len(bs)>=2
                assert bs[-1]['scene_frames']>bs[0]['scene_frames'],(phase,'did not resume')
                assert bs[-1]['elapsed']>bs[0]['elapsed'],(phase,'animation did not resume')
            assert all(abs(v['scale']-1.5)<1e-4 for v in phases['scale-1.5']['beats'])
        elif case=='no-glass':
            assert 'Scene loader finished' not in text and 'Scene pipeline compiled' not in text
        else:
            assert 'Terminal scene state' in text
            stable=[v for v in beats if not v['enabled']]
            assert stable and all(not v['bound'] and not v['loading'] for v in stable)
            assert len(set(v['scene_frames'] for v in stable))==1,'terminal scene restarted'
            post=[v for v in alloc if v['phase']=='app-static' and date(v['_time'])>=date(stable[0]['_time'])]
            assert post,'no post-fallback resource snapshots'
            for snap in post:
                assert not [a for a in snap['report']['allocations'] if a['name'].startswith('scene ')], 'scene GPU allocations retained'
            phases['released_resources']={'snapshots':len(post),'last_allocated_mib':post[-1]['report']['allocated']/2**20,'last_reserved_mib':post[-1]['report']['reserved']/2**20}
        results[case]={'phases':phases,'warnings':[l for l in text.splitlines() if re.search(r'\b(?:WARN|ERROR)\b',l)]}
    s['runtime']={'status':'passed' if (ROOT/'runtime-complete.exit').exists() else 'partial','cases':results}
(ROOT/'summary.json').write_text(json.dumps(s,indent=2)+'\n')
print(json.dumps({'visuals':s['visuals'],'compile_order':s['compile_order'],'measurements':s['measurements']['status'],'runtime':s['runtime']['status']},indent=2))
