#!/usr/bin/env python3
"""Breaks nodes under a real Minecraft client and checks that it stays connected.

Topology:

    game client -> local proxy -> node A or B (fault_relay) -> proxy-rs -> game server

Start the game server on --backend-port first, this script next, and then the game
client, which must connect to --listen. The script waits for the player to arrive,
runs the fault trials, prints a JSON report and shuts down what it started.
"""

from __future__ import annotations

import argparse
import http.client
import http.server
import json
import os
import random
import re
import shlex
import subprocess
import threading
import time
from dataclasses import dataclass, field
from typing import Optional

NODE_PORTS = (65261, 65262)
PROXY_PORT = 65260
MASTER_PORT = 65399
FAILOVER_TIMEOUT = 20.0
KICK_PATTERN = re.compile(r"left the game|lost connection|Disconnecting|timed out", re.IGNORECASE)
TP_RING = [(0, 0), (1200, 0), (1200, 1200), (0, 1200), (-1200, 1200), (-1200, 0), (-1200, -1200), (0, -1200)]


def start_master(backend_port: int) -> http.server.ThreadingHTTPServer:
    route = json.dumps(
        {"code": 0, "msg": "", "data": {"status": "PLAYABLE", "backendHost": "127.0.0.1", "backendPort": backend_port}}
    ).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args: object) -> None:
            pass

        def do_GET(self) -> None:  # noqa: N802 - stdlib callback name
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(route)))
            self.end_headers()
            self.wfile.write(route)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", MASTER_PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def spawn(command: list[str]) -> subprocess.Popen:
    return subprocess.Popen(
        command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1
    )


class Stack:
    def __init__(self, args: argparse.Namespace) -> None:
        self.master = start_master(args.backend_port)
        self.proxy = spawn(
            [
                args.proxy,
                "--listen", f"127.0.0.1:{PROXY_PORT}",
                "--master", f"http://127.0.0.1:{MASTER_PORT}",
                "--favicon", "none.png",
            ]
        )
        threading.Thread(target=self._drain, args=(self.proxy,), daemon=True).start()
        self.nodes = [f"127.0.0.1:{port}" for port in NODE_PORTS]
        self.relays = {}
        self.node_bytes: dict[str, tuple[int, int]] = {}
        for node in self.nodes:
            relay = spawn([args.fault_relay, "--listen", node, "--upstream", f"127.0.0.1:{PROXY_PORT}"])
            line = relay.stdout.readline()
            if "ready" not in line:
                raise RuntimeError(f"node did not start: {line!r}")
            self.node_bytes[node] = (0, 0)
            threading.Thread(target=self._read_relay, args=(node, relay), daemon=True).start()
            self.relays[node] = relay
        self.local = spawn([*shlex.split(args.local_proxy), "--listen", args.listen, "--nodes", ",".join(self.nodes)])
        line = self.local.stdout.readline()
        if "ready" not in line:
            raise RuntimeError(f"local proxy did not start: {line!r}")
        self.bound = line.split()[-1]
        self.events: list[str] = []
        self.events_lock = threading.Condition()
        self.game_session: Optional[str] = None
        threading.Thread(target=self._collect, daemon=True).start()

    @staticmethod
    def _drain(process: subprocess.Popen) -> None:
        for _ in process.stdout:
            pass

    def _read_relay(self, node: str, process: subprocess.Popen) -> None:
        for line in process.stdout:
            match = re.match(r"stats up=(\d+) down=(\d+)", line)
            if match:
                self.node_bytes[node] = (int(match.group(1)), int(match.group(2)))

    def total_bytes(self) -> tuple[int, int]:
        """Bytes forwarded so far by all nodes, towards the server and towards the game."""
        for node in self.nodes:
            self.fault(node, "stats")
        time.sleep(0.5)
        values = list(self.node_bytes.values())
        return sum(value[0] for value in values), sum(value[1] for value in values)

    def _collect(self) -> None:
        for line in self.local.stdout:
            with self.events_lock:
                self.events.append(line.strip())
                self.events_lock.notify_all()

    def fault(self, node: str, command: str) -> None:
        relay = self.relays[node]
        relay.stdin.write(command + "\n")
        relay.stdin.flush()

    def switch(self, node: str) -> None:
        self.local.stdin.write(f"switch {node}\n")
        self.local.stdin.flush()

    def event_count(self) -> int:
        with self.events_lock:
            return len(self.events)

    def wait_event(self, pattern: str, start: int, limit: float) -> Optional[str]:
        compiled = re.compile(pattern)
        deadline = time.monotonic() + limit
        with self.events_lock:
            while True:
                for line in self.events[start:]:
                    if compiled.search(line):
                        return line
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.events_lock.wait(remaining)

    def live_sessions(self) -> dict[str, str]:
        """Sessions that have not ended, with the node each is attached to."""
        with self.events_lock:
            lines = list(self.events)
        attached: dict[str, str] = {}
        for line in lines:
            match = re.search(r"session=(\d+) Attached \{ node: \"([^\"]+)\"", line)
            if match:
                attached[match.group(1)] = match.group(2)
            ended = re.search(r"session=(\d+) Ended", line)
            if ended:
                attached.pop(ended.group(1), None)
        return attached

    def current_node(self) -> Optional[str]:
        """The node carrying the player; other connections to the port come and go."""
        if self.game_session is not None:
            return self.live_sessions().get(self.game_session)
        live = self.live_sessions()
        return list(live.values())[-1] if live else None

    def close(self) -> None:
        for process in [self.local, *self.relays.values()]:
            try:
                process.stdin.close()
            except OSError:
                pass
        self.master.shutdown()
        self.proxy.terminate()


class GameControl:
    """Talks to the game's loopback control endpoint."""

    def __init__(self, port_file: str) -> None:
        self.port_file = port_file
        self.session: Optional[str] = None
        self.next_id = 1
        self.lock = threading.Lock()

    def _post(self, payload: dict, port: int, limit: float) -> tuple[dict, Optional[str]]:
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=limit)
        try:
            headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
            if self.session:
                headers["Mcp-Session-Id"] = self.session
            connection.request("POST", "/mcp", json.dumps(payload), headers)
            response = connection.getresponse()
            body = response.read().decode("utf-8", "replace")
            session = response.getheader("Mcp-Session-Id")
            return (json.loads(body) if body.strip() else {}), session
        finally:
            connection.close()

    def _port(self) -> int:
        with open(self.port_file, encoding="utf-8") as handle:
            return int(handle.read().strip())

    def connect(self) -> None:
        port = self._port()
        self.session = None
        _, session = self._post(
            {
                "jsonrpc": "2.0",
                "id": 0,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {"name": "resume-e2e", "version": "1"},
                },
            },
            port,
            5.0,
        )
        self.session = session
        self.next_id = 1

    def _tool(self, name: str, arguments: dict, limit: float) -> str:
        with self.lock:
            request_id = self.next_id
            self.next_id += 1
        reply, _ = self._post(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments},
            },
            self._port(),
            limit,
        )
        return json.dumps(reply, ensure_ascii=False)

    def command(self, text: str, wait_ms: int = 1000, limit: float = 12.0) -> str:
        return self._tool("command", {"command": text, "waitMs": wait_ms}, limit)

    def click_join_button(self) -> bool:
        """Walks to the same join button a player uses and presses it."""
        screen = json.loads(self._tool("screen_read", {}, 10.0))["result"]["content"][0]["text"]
        version = re.search(r"version (\d+)", screen)
        for label in ("进入地图", "进入房间", "多人游戏", "返回"):
            button = re.search(rf'#(\d+) Button "{label}', screen)
            if version and button:
                arguments = {"version": int(version.group(1)), "widget": int(button.group(1))}
                self._tool("screen_click", arguments, 10.0)
                return True
        return False


@dataclass
class Results:
    trials: list[dict] = field(default_factory=list)
    kicked: list[str] = field(default_factory=list)

    def summary(self, kind: str) -> dict:
        values = sorted(t["elapsed_ms"] for t in self.trials if t["kind"] == kind and t["ok"])
        failed = sum(1 for t in self.trials if t["kind"] == kind and not t["ok"])
        if not values:
            return {"count": 0, "failed": failed}
        return {
            "count": len(values),
            "failed": failed,
            "p50_ms": values[len(values) // 2],
            "p95_ms": values[min(len(values) - 1, len(values) * 95 // 100)],
            "max_ms": values[-1],
        }


def kick_lines(log_path: str, start: int) -> list[str]:
    with open(log_path, encoding="utf-8", errors="replace") as handle:
        return [line.strip() for line in handle.readlines()[start:] if KICK_PATTERN.search(line)]


def line_count(log_path: str) -> int:
    with open(log_path, encoding="utf-8", errors="replace") as handle:
        return sum(1 for _ in handle)


def wait_for_player(stack: Stack, game: GameControl, limit: float) -> None:
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        try:
            game.connect()
            if stack.current_node():
                reply = game.command("/time query daytime")
                if "The time is" in reply or "时间" in reply:
                    stack.game_session = list(stack.live_sessions())[-1]
                    return
            else:
                game.click_join_button()
        except (OSError, ValueError, KeyError, http.client.HTTPException):
            pass
        time.sleep(3)
    raise RuntimeError("player never reached the world")


class Loader:
    """Keeps the server sending lots of data while faults happen."""

    def __init__(self, game: GameControl, commands) -> None:
        self.game = game
        self.commands = commands
        self.stop = threading.Event()
        self.sent = 0
        self.errors = 0
        self.thread = threading.Thread(target=self._run, daemon=True)

    def _run(self) -> None:
        while not self.stop.is_set():
            for text in self.commands():
                if self.stop.is_set():
                    return
                try:
                    self.game.command(text, wait_ms=0, limit=20.0)
                    self.sent += 1
                except (OSError, ValueError, http.client.HTTPException):
                    self.errors += 1
                self.stop.wait(1.5)

    def __enter__(self) -> "Loader":
        self.thread.start()
        return self

    def __exit__(self, *_exc: object) -> None:
        self.stop.set()
        self.thread.join(25.0)


def process_stats(pid: int) -> str:
    """Peak memory and transferred bytes of a Windows helper, when it is one."""
    script = (
        f"Get-CimInstance Win32_Process -Filter \"ProcessId={pid}\" | "
        "ForEach-Object { \"peak_ws_kb=$($_.PeakWorkingSetSize) read_bytes=$($_.ReadTransferCount) "
        "write_bytes=$($_.WriteTransferCount) other_bytes=$($_.OtherTransferCount)\" }"
    )
    try:
        done = subprocess.run(
            ["pwsh.exe", "-NoProfile", "-Command", script], capture_output=True, text=True, timeout=30
        )
        return done.stdout.strip()
    except (OSError, subprocess.SubprocessError) as error:
        return f"unavailable: {error}"


def teleports(player: str):
    def generate():
        offset = random.randint(0, 200)
        return [f"/tp {player} {x + offset} 200 {z + offset}" for x, z in TP_RING]

    return generate


def recipe_bursts(player: str):
    return lambda: [f"/recipe give {player} *", f"/recipe take {player} *"]


def alive_check(game: GameControl) -> Optional[int]:
    """Milliseconds until the server answers a command, or None if it never does.

    One slow reply is not a failure: the server may be busy generating terrain. A
    connection that lost data would stay silent for the whole allowance.
    """
    started = time.monotonic()
    for _ in range(4):
        try:
            reply = game.command("/time query daytime", wait_ms=5000, limit=25.0)
        except (OSError, ValueError, http.client.HTTPException):
            continue
        if "The time is" in reply or "时间" in reply:
            return int((time.monotonic() - started) * 1000)
    return None


def run_fault(stack: Stack, game: GameControl, results: Results, load: str, fault: str, settle: float) -> None:
    current = stack.current_node()
    if current is None:
        results.trials.append({"kind": fault, "load": load, "ok": False, "elapsed_ms": 0, "error": "no session"})
        return
    start = stack.event_count()
    started = time.monotonic()
    stack.fault(current, fault)
    session = stack.game_session
    line = stack.wait_event(rf"session={session} Attached .*reason: Failover", start, FAILOVER_TIMEOUT)
    observed = time.monotonic() - started
    stack.fault(current, "heal")
    ended = stack.wait_event(rf"session={session} Ended", start, 0.0)
    if line is None:
        results.trials.append({"kind": fault, "load": load, "ok": False, "elapsed_ms": 0, "error": "no failover"})
        return
    elapsed = int(re.search(r"elapsed_ms: (\d+)", line).group(1))
    alive_ms = alive_check(game)
    ok = alive_ms is not None and ended is None
    results.trials.append(
        {
            "kind": fault,
            "load": load,
            "ok": ok,
            "elapsed_ms": elapsed,
            "observed_ms": int(observed * 1000),
            "alive_ms": alive_ms,
            "error": None if ok else f"game not responding after failover; ended={ended}",
        }
    )
    print(f"{load}/{fault}: {elapsed}ms (observed {int(observed * 1000)}ms) alive={alive_ms}ms ok={ok}", flush=True)
    time.sleep(settle)


def run_manual(stack: Stack, game: GameControl, results: Results, load: str, settle: float) -> None:
    current = stack.current_node()
    if current is None:
        results.trials.append({"kind": "manual", "load": load, "ok": False, "elapsed_ms": 0, "error": "no session"})
        return
    target = next(node for node in stack.nodes if node != current)
    start = stack.event_count()
    stack.switch(target)
    session = stack.game_session
    line = stack.wait_event(rf"session={session} Attached .*reason: Manual", start, FAILOVER_TIMEOUT)
    if line is None:
        results.trials.append({"kind": "manual", "load": load, "ok": False, "elapsed_ms": 0, "error": "no switch"})
        return
    lost = stack.wait_event(rf"session={session} PathLost", start, 0.0)
    elapsed = int(re.search(r"elapsed_ms: (\d+)", line).group(1))
    alive_ms = alive_check(game)
    ok = alive_ms is not None and lost is None
    results.trials.append({"kind": "manual", "load": load, "ok": ok, "elapsed_ms": elapsed, "alive_ms": alive_ms, "lost": lost})
    print(f"{load}/manual: {elapsed}ms alive={alive_ms}ms ok={ok} lost={lost}", flush=True)
    time.sleep(settle)


def announce(game: GameControl, player: str, text: str) -> None:
    """Tells the person playing what is about to happen; a missed message changes nothing.

    A system message, because /say needs a chat session that offline accounts do not have.
    """
    message = json.dumps({"text": f"[节点测试] {text}", "color": "yellow"}, ensure_ascii=False)
    try:
        game.command(f"/tellraw {player} {message}", wait_ms=0, limit=5.0)
    except (OSError, ValueError, http.client.HTTPException) as error:
        print(f"announce failed: {error}", flush=True)


def hold(stack: Stack, game: GameControl, results: Results, args: argparse.Namespace) -> int:
    """Keeps breaking nodes while a person plays, until the stop file appears or time runs out."""
    log_start = line_count(args.server_log)
    labels = {"kill": "节点进程退出", "freeze": "节点完全静默"}
    deadline = time.monotonic() + args.hold_seconds
    next_fault = time.monotonic() + args.hold_fault_every
    index = 0
    stopped_by = "time"
    print(f"holding; create {args.hold_stop_file} to stop", flush=True)
    announce(game, args.player, f"开始测试，每{args.hold_fault_every:.0f}秒断开一次当前节点")
    while time.monotonic() < deadline:
        if os.path.exists(args.hold_stop_file):
            stopped_by = "stop file"
            break
        if stack.wait_event(rf"session={stack.game_session} Ended", 0, 0.0):
            stopped_by = "session ended"
            print("session ended", flush=True)
            break
        if time.monotonic() < next_fault:
            time.sleep(0.5)
            continue
        fault = "kill" if index % 2 == 0 else "freeze"
        index += 1
        current = stack.current_node()
        if current is None:
            results.trials.append({"kind": fault, "ok": False, "elapsed_ms": 0, "error": "no session"})
            print(f"hold/{fault}: ok=False no session", flush=True)
            break
        announce(game, args.player, f"3秒后断开当前节点：{labels[fault]}")
        time.sleep(3)
        start = stack.event_count()
        stack.fault(current, fault)
        line = stack.wait_event(rf"session={stack.game_session} Attached .*reason: Failover", start, FAILOVER_TIMEOUT)
        stack.fault(current, "heal")
        if line is None:
            results.trials.append({"kind": fault, "ok": False, "elapsed_ms": 0, "error": "no failover"})
            print(f"hold/{fault}: ok=False no failover", flush=True)
        else:
            elapsed = int(re.search(r"elapsed_ms: (\d+)", line).group(1))
            results.trials.append({"kind": fault, "ok": True, "elapsed_ms": elapsed})
            print(f"hold/{fault}: {elapsed}ms ok=True", flush=True)
            announce(game, args.player, f"已切换节点，恢复用时{elapsed}ms")
        next_fault = time.monotonic() + args.hold_fault_every

    results.kicked = kick_lines(args.server_log, log_start)
    report = {
        "stopped_by": stopped_by,
        "kill": results.summary("kill"),
        "freeze": results.summary("freeze"),
        "failures": [trial for trial in results.trials if not trial["ok"]],
        "kicked": results.kicked[:20],
        "events": stack.event_count(),
    }
    with open(args.report, "w", encoding="utf-8") as handle:
        json.dump({"report": report, "trials": results.trials, "events": stack.events}, handle, ensure_ascii=False, indent=1)
    print(json.dumps(report, ensure_ascii=False, indent=1), flush=True)
    return 0 if not report["failures"] and not report["kicked"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--proxy", required=True)
    parser.add_argument("--local-proxy", required=True, help="command that runs the proxy; use / in Windows paths")
    parser.add_argument("--fault-relay", required=True)
    parser.add_argument("--listen", required=True, help="address the game client connects to")
    parser.add_argument("--backend-port", type=int, required=True)
    parser.add_argument("--player", required=True)
    parser.add_argument("--port-file", required=True, help="the client's standard_mcp_port.txt")
    parser.add_argument("--server-log", required=True)
    parser.add_argument("--per-fault", type=int, default=10, help="trials of each fault under each load")
    parser.add_argument("--manual", type=int, default=5)
    parser.add_argument("--settle", type=float, default=2.0)
    parser.add_argument("--join-timeout", type=float, default=900.0)
    parser.add_argument("--report", required=True)
    parser.add_argument("--hold-fault-every", type=float, default=0.0, help="seconds between faults while a person plays")
    parser.add_argument("--hold-seconds", type=float, default=1800.0)
    parser.add_argument("--hold-stop-file", default="resume_hold.stop")
    args = parser.parse_args()

    results = Results()
    stack = Stack(args)
    print(f"local proxy ready on {stack.bound}; start the game client now", flush=True)
    try:
        game = GameControl(args.port_file)
        wait_for_player(stack, game, args.join_timeout)
        print("player is in the world", flush=True)
        if args.hold_fault_every > 0:
            return hold(stack, game, results, args)
        log_start = line_count(args.server_log)
        game.command(f"/gamemode creative {args.player}")
        print("sample teleport reply:", game.command(f"/tp {args.player} 300 200 300", wait_ms=1000)[:300], flush=True)

        traffic: dict[str, dict[str, float]] = {}

        def scenario(load: str) -> None:
            start_up, start_down = stack.total_bytes()
            started = time.monotonic()
            for index in range(args.per_fault * 2):
                fault = "kill" if index % 2 == 0 else "freeze"
                run_fault(stack, game, results, load, fault, args.settle)
            for _ in range(args.manual):
                run_manual(stack, game, results, load, args.settle)
            up, down = stack.total_bytes()
            seconds = time.monotonic() - started
            traffic[load] = {
                "seconds": round(seconds, 1),
                "mib_to_server": round((up - start_up) / 1048576, 2),
                "mib_to_game": round((down - start_down) / 1048576, 2),
                "kib_per_second_to_game": round((down - start_down) / 1024 / seconds, 1),
            }
            print(f"{load} traffic: {traffic[load]}", flush=True)

        scenario("idle")
        with Loader(game, teleports(args.player)) as loader:
            scenario("chunks")
        print(f"chunk load commands: {loader.sent}, errors: {loader.errors}", flush=True)
        with Loader(game, recipe_bursts(args.player)) as loader:
            scenario("packets")
        print(f"packet load commands: {loader.sent}, errors: {loader.errors}", flush=True)

        results.kicked = kick_lines(args.server_log, log_start)
        session_ended = [line for line in stack.events if f"session={stack.game_session} Ended" in line]
        alive_times = sorted(t["alive_ms"] for t in results.trials if t.get("alive_ms") is not None)
        report = {
            "kill": results.summary("kill"),
            "freeze": results.summary("freeze"),
            "manual": results.summary("manual"),
            "failures": [trial for trial in results.trials if not trial["ok"]],
            "kicked": results.kicked[:20],
            "player_session_ended": session_ended[:20],
            "other_sessions": len(stack.live_sessions()) + sum(1 for line in stack.events if " Ended " in line),
            "alive_max_ms": alive_times[-1] if alive_times else None,
            "events": stack.event_count(),
            "traffic": traffic,
            "proxy_stats": process_stats(stack.proxy.pid),
            "local_proxy_stats": process_stats(stack.local.pid),
        }
        with open(args.report, "w", encoding="utf-8") as handle:
            json.dump({"report": report, "trials": results.trials, "events": stack.events}, handle, ensure_ascii=False, indent=1)
        print(json.dumps(report, ensure_ascii=False, indent=1), flush=True)
        return 0 if not report["failures"] and not report["kicked"] else 1
    finally:
        stack.close()


if __name__ == "__main__":
    raise SystemExit(main())
