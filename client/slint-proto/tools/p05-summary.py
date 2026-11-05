"""Summarize raw, host-specific samples. Never equate payload, allocator reserve and Windows usage."""
import json,re,statistics,struct
from datetime import datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent/'evidence/bg-port/p0.5'
ANSI=re.compile(r'\x1b\[[0-9;]*m')
def snapshots(case):
    p=ROOT/'controlled'/f'{case}-app.log'
    if not p.exists():return []
    out=[]
    for line in p.read_text().splitlines():
        line=ANSI.sub('',line)
        if 'Memory allocator snapshot' not in line:continue
        stamp=line.split(' ',1)[0];d=json.loads(line.split('data=',1)[1]);d['time']=stamp;out.append(d)
    return out
summary={}
for file in sorted((ROOT/'controlled').glob('*-memory.jsonl')):
    case=file.name.removesuffix('-memory.jsonl');rows=[json.loads(s) for s in file.read_text().splitlines()]
    endpoints={}
    for phase in ['loaded','foreground60s','minimized30s','restored10s','drop+25s']:
        values=[r for r in rows if r['Phase']==phase]
        if values:
            r=values[0] if phase=='loaded' else values[-1]
            endpoints[phase]={**r,**{k:round(r[k]/2**20,3) for k in ['WorkingSet','Private','Dedicated','Shared']}}
    ss=snapshots(case);phases={}
    for d in ss:
        if not d['report']:continue
        r=d['report'];phase=d['phase'];phases[phase]={'allocated':round(r['allocated']/2**20,3),'reserved':round(r['reserved']/2**20,3),'blocks':[round(b['size']/2**20,3) for b in r['blocks']],'allocations':r['allocations'],'time':d['time']}
    at_endpoints={}
    for phase,e in endpoints.items():
        cutoff=datetime.fromisoformat(e['Time'].replace('Z','+00:00'))
        before=[d for d in ss if datetime.fromisoformat(d['time'].replace('Z','+00:00'))<=cutoff]
        if before:
            d=before[-1];r=d['report']
            if r:at_endpoints[phase]={'allocated':r['allocated']/2**20,'reserved':r['reserved']/2**20,'phase':d['phase'],'time':d['time'],'allocations':r['allocations']}
    text=ANSI.sub('',(ROOT/'controlled'/f'{case}-app.log').read_text())
    samples=[(float(a),float(b)) for a,b in re.findall(r'average_ms=([\d.eE+-]+) max_ms=([\d.eE+-]+)',text)]
    screenshot=ROOT/'controlled'/f'{case}-glass.png'
    dimensions=struct.unpack('>II',screenshot.read_bytes()[16:24]) if screenshot.exists() else None
    valid=dimensions==(2560,1440) if case!='lazy-drop' else True
    summary[case]={'screenshot_dimensions':dimensions,'valid_full_window_endpoint':valid,'endpoints_mib':endpoints,'allocator_last_by_phase':phases,'allocator_at_endpoints':at_endpoints,'completion_windows':len(samples),'completion_average_ms':statistics.mean(a for a,b in samples) if samples else None,'completion_max_ms':max(b for a,b in samples) if samples else None,'errors':len(re.findall(r'ERROR',text))}
(ROOT/'summary.json').write_text(json.dumps(summary,indent=2))
for case,d in summary.items():
    e=d['endpoints_mib'].get('foreground60s',d['endpoints_mib'].get('drop+25s',{}))
    print(case,{k:e.get(k) for k in ['WorkingSet','Private','Dedicated','Shared','Focused']},'allocated/reserved at endpoints:',{p:(round(a['allocated'],3),round(a['reserved'],3)) for p,a in d['allocator_at_endpoints'].items()})
