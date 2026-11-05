"""Explicit payload projection only. No claim that Windows counters equal payload bytes."""
import json
from pathlib import Path
MIB=2**20
W,H=1280,720
N=714386  # Current full Kotlin preview, approximately306k vertices beyond the P0 terrain.
def mip_bytes(w,h,bpp):
    total=0
    while True:
        total+=w*h*bpp
        if max(w,h)==1:return total
        w,h=max(1,w//2),max(1,h//2)
def resources(samples,packed,hdr_bytes=8):
    return {
        'mesh vertices':N*(24 if packed else 52),
        'mesh indices (1.5 per vertex)':N*3//2*(2 if packed else 4),
        'scene MSAA color':W*H*hdr_bytes*samples if samples>1 else 0,
        'scene depth':W*H*4*samples,
        'pre-water HDR capture':W*H*hdr_bytes,
        'pre-water R32 depth capture':W*H*4,
        'reflection HDR mips':mip_bytes(640,360,hdr_bytes),
        'reflection depth':640*360*4,
        'raw HDR resolve':W*H*hdr_bytes,
        'half-res fog RGBA16F':640*360*8,
        'linear HDR copy mips':mip_bytes(W,H,hdr_bytes),
        'bloom atlas RGBA8':W*H*4,
        'final RGBA8':W*H*4,
        '2048 shadow D32':2048*2048*4,
        '64 cubed fog R8':64**3,
        'material/cloud/noise maps with mips allowance':int(.75*MIB),
    }
out={}
for name,samples,packed in [('current',4,False),('packed4',4,True),('packed2',2,True),('packed1',1,True)]:
    r=resources(samples,packed)
    out[name]={'resources_mib':{k:v/MIB for k,v in r.items()},'payload_mib':sum(r.values())/MIB,'computed_rg11b10_saving_mib':(sum(r.values())-sum(resources(samples,packed,4).values()))/MIB}
p=Path(__file__).resolve().parent.parent/'evidence/bg-port/p0.5/projection.json';p.write_text(json.dumps(out,indent=2))
for name,d in out.items():print(name,round(d['payload_mib'],3),'MiB; computed HDR-format saving',round(d['computed_rg11b10_saving_mib'],3))
