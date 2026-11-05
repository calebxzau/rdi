"""CI window start/screenshot/close probe. No login or secrets; never a performance test."""
from pathlib import Path
import json
import os
import subprocess
import sys
import time

out = Path("evidence/ui-port/u1/platform")
out.mkdir(parents=True, exist_ok=True)
env = {k: v for k, v in os.environ.items() if k not in ("RDI_PROTO_USR", "RDI_PROTO_PWD")}
with (out / "window.log").open("w") as log:
    process = subprocess.Popen(["target/release/rdi-slint-proto", "--ui-proof"], env=env, stdout=log, stderr=subprocess.STDOUT)
    time.sleep(20)
    result = {"alive_after_20s": process.poll() is None}
    if sys.platform == "darwin":
        result["screenshot_exit"] = subprocess.run(["screencapture", "-x", str(out / "window.png")], check=False).returncode
        # Request a window close on this process, not a machine-wide application quit.
        result["close_request_exit"] = subprocess.run(["osascript", "-e",
            'tell application "System Events" to tell process "rdi-slint-proto" to click button 1 of window 1'], check=False).returncode
    else:
        result["screenshot_exit"] = subprocess.run(["xwd", "-root", "-silent", "-out", str(out / "window.xwd")], check=False).returncode
        result["close_request_exit"] = subprocess.run(["wmctrl", "-c", "RDI Slint Prototype"], check=False).returncode
    try:
        result["exit_code"] = process.wait(timeout=10)
        result["forced_termination"] = False
    except subprocess.TimeoutExpired:
        process.terminate()
        result["exit_code"] = process.wait(timeout=10)
        result["forced_termination"] = True
    (out / "window-result.json").write_text(json.dumps(result, indent=2))
