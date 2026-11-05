"""Re-baseline against the preserved P0.5 best binary; retain every accounting model separately."""
import json,re,struct
from pathlib import Path
from datetime import datetime
R=Path(__file__).resolve().parent.parent/'evidence/bg-port/p1/full'
ANSI=re.compile(r'\x1b\[[0-9;]*m')
summary={}
for case in ['static','baseline','live']:
    p=R/'measure'/f'{case}-memory.jsonl'
    if not p.exists():continue
    rows=[json.loads(l) for l in p.read_text().splitlines()]
    text=ANSI.sub('',(R/'measure'/f'{case}-app.log').read_text())
    snapshots=[]
    for line in text.splitlines():
        if 'Memory allocator snapshot' in line:
            d=json.loads(line.split('data=',1)[1]);d['time']=line.split()[0];snapshots.append(d)
    endpoints={}
    for phase in ['loaded','foreground60s','minimized30s','restored10s']:
        values=[d for d in rows if d['Phase']==phase]
        if not values:continue
        d=values[0] if phase=='loaded' else values[-1]
        cutoff=datetime.fromisoformat(d['Time'].replace('Z','+00:00'))
        prior=[s for s in snapshots if datetime.fromisoformat(s['time'].replace('Z','+00:00'))<=cutoff]
        a=prior[-1]['report'] if prior else None
        endpoints[phase]={**d,**{k:d[k]/2**20 for k in ['WorkingSet','Private','Dedicated','Shared']},'allocated_mib':a['allocated']/2**20 if a else None,'reserved_mib':a['reserved']/2**20 if a else None}
    screenshot=R/'measure'/f'{case}-glass.png'
    dims=struct.unpack('>II',screenshot.read_bytes()[16:24]) if screenshot.exists() else None
    summary[case]={'endpoints_mib':endpoints,'screenshot_dimensions':dims,'errors':text.count('ERROR')}
(R/'memory-summary.json').write_text(json.dumps(summary,indent=2))
lines=['| Case/phase | Working set | Private | GPU dedicated/shared | GPU total | Allocated/reserved |','|---|---:|---:|---:|---:|---:|']
for c,d in summary.items():
    for phase,e in d['endpoints_mib'].items():
        lines.append(f"|{c}/{phase}|{e['WorkingSet']:.2f}|{e['Private']:.2f}|{e['Dedicated']:.2f}/{e['Shared']:.2f}|{e['Dedicated']+e['Shared']:.2f}|{e['allocated_mib']:.2f}/{e['reserved_mib']:.2f}|")
(R/'memory-table.md').write_text('\n'.join(lines)+'\n')
print('\n'.join(lines))
