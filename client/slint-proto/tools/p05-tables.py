"""Write review tables from recorded endpoints, keeping all units and scopes explicit."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent/'evidence/bg-port/p0.5'
s=json.loads((ROOT/'summary.json').read_text())
lines=['| Case | Working set | Private | GPU dedicated/shared | GPU total | Tracked allocation/reserve |','|---|---:|---:|---:|---:|---:|']
for c in ['static','live','pipelines','chunk','msaa2','msaa1','packed','best']:
 d=s.get('packed-checked',s.get(c,{})) if c=='packed' else s.get(c,{})
 if not d.get('valid_full_window_endpoint',False):continue
 e=d.get('endpoints_mib',{}).get('foreground60s');a=d.get('allocator_at_endpoints',{}).get('foreground60s')
 if e and a:lines.append(f"|{c}|{e['WorkingSet']:.2f}|{e['Private']:.2f}|{e['Dedicated']:.2f}/{e['Shared']:.2f}|{e['Dedicated']+e['Shared']:.2f}|{a['allocated']:.2f}/{a['reserved']:.2f}|")
(ROOT/'endpoints-table.md').write_text('\n'.join(lines)+'\n')
lines=['| Case/phase | Working set | Private | GPU dedicated/shared | Tracked allocation/reserve |','|---|---:|---:|---:|---:|']
for c in ['static','live','pipelines','chunk','msaa2','msaa1','packed','best','lazy-drop']:
 d=s.get('packed-checked',s.get(c,{})) if c=='packed' else s.get(c,{})
 if not d.get('valid_full_window_endpoint',False):continue
 for phase in ['loaded','foreground60s','minimized30s','restored10s','drop+25s']:
  e=d.get('endpoints_mib',{}).get(phase);a=d.get('allocator_at_endpoints',{}).get(phase)
  if e and a:lines.append(f"|{c}/{phase}|{e['WorkingSet']:.2f}|{e['Private']:.2f}|{e['Dedicated']:.2f}/{e['Shared']:.2f}|{a['allocated']:.2f}/{a['reserved']:.2f}|")
(ROOT/'all-phases-table.md').write_text('\n'.join(lines)+'\n')
print((ROOT/'endpoints-table.md').read_text())
