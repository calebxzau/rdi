"""P4a matched player geometry/pose/images, authenticated memory and glass evidence."""
import json
import re
from pathlib import Path
from datetime import datetime
import numpy as np
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parent.parent/"evidence/bg-port/p4a"
ANSI=re.compile(r"\x1b\[[0-9;]*m")
def rgb(path): return np.asarray(Image.open(path).convert("RGB"),dtype=float)
def pair(name,left,right,box=(0,0,1280,720),zoom=1):
    w,h=box[2]-box[0],box[3]-box[1]
    out=Image.new("RGB",(w*zoom*2,h*zoom+40),"#181b20");draw=ImageDraw.Draw(out)
    for i,(p,label) in enumerate([(left,"Kotlin player / no crowd / AA off"),(right,"Rust P4a")]):
        draw.text((i*w*zoom+8,8),label,fill="white")
        out.paste(Image.open(p).convert("RGB").crop(box).resize((w*zoom,h*zoom),Image.Resampling.NEAREST),(i*w*zoom,40))
    out.save(ROOT/name)
s={"variants":{},"memory":{"status":"pending"}}
for variant in ["steve","slim-cape","legacy","hd"]:
    a=np.fromfile(ROOT/f"reference/{variant}/player-mesh.bin","<f4")
    b=np.fromfile(ROOT/f"{variant}/player-mesh.bin","<f4")
    assert np.array_equal(a,b),(variant,"mesh mismatch")
    d={"mesh_byte_exact":True,"vertices":len(a)//11,"times":{}}
    for p in sorted((ROOT/f"reference/{variant}").glob("kotlin-matched-*.png")):
        t=p.stem.removeprefix("kotlin-matched-")
        q=ROOT/f"{variant}/p0-rust-{t}.png"
        a,b=rgb(p),rgb(q)
        pa=np.fromfile(ROOT/f"reference/{variant}/player-params-{t}.bin","<f4")
        pb=np.fromfile(ROOT/f"{variant}/player-params-{t}.bin","<f4")
        error=float(np.abs(pa-pb).max());assert error<3e-6
        crop=np.abs(a[375:438,610:670]-b[375:438,610:670])
        v={"full_rgb_mae":float(np.abs(a-b).mean()),"player_crop_rgb_mae":float(crop.mean()),"player_crop_rgb_median_error":float(np.median(crop)),"max_model_bone_parameter_error":error}
        if variant=="steve":
            ra=np.fromfile(ROOT/f"reference/{variant}/reflection-{t}.rgb32f","<f4").reshape(360,640,3)
            rb=np.fromfile(ROOT/f"{variant}/reflection-{t}.rgba16f","<f2").reshape(360,640,4)[...,:3].astype(float)
            assert np.isfinite(rb).all()
            v["reflection_hdr_mae"]=float(np.abs(ra-rb).mean())
            # A separate diagnostic disables all player resources, preserving P3.
            old=rgb(ROOT/f"../p3/final/p0-rust-{t}.png")
            preserved=rgb(ROOT/f"p3-preserved/p0-rust-{t}.png")
            v["p3_max_channel_error"]=float(np.abs(old-preserved).max());assert v["p3_max_channel_error"]<=1
            pair(f"p4a-matched-{t}.png",p,q)
            pair(f"p4a-compare-{t}.png",ROOT/f"reference/kotlin-original-{t}.png",q)
        pair(f"p4a-player-{variant}-{t}.png",p,q,(610,375,670,438),6)
        d["times"][t]=v
    s["variants"][variant]=d
assert np.array_equal(rgb(ROOT/"hd/p0-rust-0.0.png"),rgb(ROOT/"steve/p0-rust-0.0.png"))
s["hd_nearest_normalization_image_exact"]=True
s["preview_timing"]=json.loads((ROOT/"steve/timing.json").read_text())
log=ANSI.sub("",(ROOT/"steve.log").read_text())
s["player_pipeline_creation_ms"]={n:float(v) for n,v in re.findall(r'pipeline="(scene player (?:main|reflection))".*?compile_ms=([0-9.]+)',log)}
# Exact persistent mesh variants include cape geometry even if it is skipped in the draw.
s["player_logical_payload_mib"]=(2*(468+288)*44+64*64*4+64*32*4+528)/2**20
if (ROOT/"measure/complete.exit").exists():
    cases={}
    for case in ["static","p3","p4a"]:
        tag=f"{case}-aligned" if case in ["static","p3","p4a"] and (ROOT/f"measure/{case}-aligned-measure.exit").exists() else case
        assert (ROOT/f"measure/{tag}-measure.exit").read_text().strip()=="0"
        rows=[json.loads(l) for l in (ROOT/f"measure/{tag}-memory.jsonl").read_text().splitlines()]
        text=ANSI.sub("",(ROOT/f"measure/{tag}-app.log").read_text())
        assert "Menu data loaded" in text and "Menu login/load failed" not in text
        assert not re.search(r"\b(?:WARN|ERROR)\b|Scene unavailable|Scene loader failed",text),case
        snaps=[]
        for line in text.splitlines():
            if "Memory allocator snapshot" in line:
                snap=json.loads(line.split("data=",1)[1]);snap["time"]=datetime.fromisoformat(line.split()[0].replace("Z","+00:00"));snaps.append(snap)
        endpoints={}
        for phase in ["loaded","foreground60s","minimized30s","restored10s"]:
            matching=[v for v in rows if v["Phase"]==phase]
            row=matching[0] if phase=="loaded" else matching[-1]
            assert row["Authenticated"]
            time=datetime.fromisoformat(row["Time"].replace("Z","+00:00"))
            a=[v["report"] for v in snaps if v["time"]<=time and v["report"]][-1]
            endpoints[phase]={**row,**{k:row[k]/2**20 for k in ["WorkingSet","Private","Dedicated","Shared"]},"gpu_total_mib":(row["Dedicated"]+row["Shared"])/2**20,"allocated_mib":a["allocated"]/2**20,"reserved_mib":a["reserved"]/2**20,"blocks_mib":[v["size"]/2**20 for v in a["blocks"]]}
        assert endpoints["foreground60s"]["Width"]==2560 and endpoints["foreground60s"]["Height"]==1440
        assert endpoints["minimized30s"]["ActualMinimized"] and not endpoints["restored10s"]["ActualMinimized"]
        statuses=re.findall(r'API response.*?path="([^"]+)".*?status=(\d+)',text)
        assert statuses and all(v=="200" for _,v in statuses)
        counts=re.search(r'Menu data loaded.*?hosts=(\d+).*?modpacks=(\d+)',text)
        appearance=re.findall(r'Account player appearance ready.*?slim=(\w+).*?cape=(\w+)',text)
        uploads=re.findall(r'Player appearance uploaded; CPU pixels released.*?slim=(\w+).*?legacy=(\w+).*?cape=(\w+)',text)
        if case=="p4a":assert appearance and uploads
        else:assert not appearance and not uploads
        completion=[(float(a),float(b)) for a,b in re.findall(r'Scene completion window.*?average_ms=([0-9.]+).*?max_ms=([0-9.]+)',text)]
        cases[case]={"source_prefix":tag,"endpoints":endpoints,"hosts":int(counts[1]),"modpacks":int(counts[2]),"api_responses":len(statuses),"api_paths":sorted(set(p for p,_ in statuses)),"appearance_flags":appearance,"upload_flags":uploads,"mean_completion_window_ms":float(np.mean([a for a,b in completion])) if completion else None,"max_completion_ms":max((b for a,b in completion),default=None),"startup_ms":dict((n,int(v)) for n,v in re.findall(r'(First live scene frame|First frame).*?elapsed_ms=(\d+)',text))}
    delta={}
    for phase in ["foreground60s","minimized30s","restored10s"]:
        delta[phase]={k:cases["p4a"]["endpoints"][phase][k]-cases["p3"]["endpoints"][phase][k] for k in ["WorkingSet","Private","gpu_total_mib","allocated_mib","reserved_mib"]}
        delta[phase]["ws_minus_allocated_delta_mib"]=delta[phase]["WorkingSet"]-delta[phase]["allocated_mib"]
        delta[phase]["private_minus_allocated_delta_mib"]=delta[phase]["Private"]-delta[phase]["allocated_mib"]
    s["memory"]={"status":"passed","cases":cases,"p4a_minus_accepted_p3":delta}
(ROOT/"summary.json").write_text(json.dumps(s,indent=2)+"\n")
print(json.dumps({"variants":s["variants"],"pipelines":s["player_pipeline_creation_ms"],"memory_status":s["memory"]["status"]},indent=2))
