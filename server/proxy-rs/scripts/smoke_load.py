#!/usr/bin/env python3
"""Synthetic compatibility, admission, load, and memory check for proxy-rs.

This is deliberately a local-only test.  It uses a mock HTTP master and mock
TCP Minecraft backends; it does not start Minecraft and does not contact a
real master service.
"""

from __future__ import annotations

import argparse
from collections import deque
import http.server
import json
import os
import queue
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.parse
import uuid
from dataclasses import dataclass, field
from typing import Callable, Optional


READ_TIMEOUT = 4.0
CONNECT_TIMEOUT = 2.0
ROUTE_TIMEOUT = 7.0
MAX_FRAME = 4096
GENERIC_ROUTE_ERROR = "房间服务暂时不可用，请稍后重试"
PAUSED_ERROR = "无法连接房间，请稍后再试"
STOPPED_ERROR = "房间未启动，请前往房间后台，点击启动按钮"
STARTED_ERROR = "房间启动中，请稍等"


class CheckError(RuntimeError):
    pass


def check(condition: bool, message: str) -> None:
    if not condition:
        raise CheckError(message)


def put_varint(value: int) -> bytes:
    if value < 0:
        value &= 0xFFFFFFFF
    result = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        if value:
            result.append(part | 0x80)
        else:
            result.append(part)
            return bytes(result)


def get_varint(data: bytes, offset: int = 0) -> tuple[int, int]:
    value = 0
    shift = 0
    for index in range(offset, min(len(data), offset + 5)):
        byte = data[index]
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, index + 1
        shift += 7
    raise CheckError("truncated or oversized VarInt")


def frame(payload: bytes) -> bytes:
    return put_varint(len(payload)) + payload


def read_exact(sock: socket.socket, count: int, initial: bytes = b"") -> tuple[bytes, bytes]:
    data = bytearray(initial)
    while len(data) < count:
        part = sock.recv(count - len(data))
        if not part:
            raise CheckError(f"peer closed while reading {count} bytes")
        data.extend(part)
    return bytes(data[:count]), bytes(data[count:])


def read_frame(sock: socket.socket, initial: bytes = b"", limit: int = MAX_FRAME) -> tuple[bytes, bytes]:
    body, remaining, _raw = read_frame_raw(sock, initial, limit)
    return body, remaining


def read_frame_raw(
    sock: socket.socket, initial: bytes = b"", limit: int = MAX_FRAME
) -> tuple[bytes, bytes, bytes]:
    data = bytearray(initial)
    while True:
        try:
            length, end = get_varint(bytes(data))
            break
        except CheckError:
            if len(data) >= 5:
                raise CheckError("invalid frame length VarInt")
            part = sock.recv(1)
            if not part:
                raise CheckError("peer closed before frame length")
            data.extend(part)
    check(length <= limit, f"frame length {length} exceeds {limit}")
    body, remaining = read_exact(sock, length, bytes(data[end:]))
    return body, remaining, bytes(data[:end]) + body


def read_until_eof(sock: socket.socket, timeout: float = 2.0) -> bytes:
    sock.settimeout(timeout)
    result = bytearray()
    while True:
        try:
            part = sock.recv(65536)
        except socket.timeout:
            raise CheckError("timed out waiting for peer EOF")
        if not part:
            return bytes(result)
        result.extend(part)


def handshake(protocol: int, host: bytes, requested_port: int, next_state: int) -> bytes:
    body = (
        put_varint(0)
        + put_varint(protocol)
        + put_varint(len(host))
        + host
        + requested_port.to_bytes(2, "big")
        + put_varint(next_state)
    )
    return frame(body)


def status_request() -> bytes:
    return frame(put_varint(0))


def ping_request(payload: bytes) -> bytes:
    check(len(payload) == 8, "ping payload must be 8 bytes")
    return frame(put_varint(1) + payload)


class ThreadedTcpServer:
    def __init__(self, handler: Callable[[socket.socket, tuple[str, int]], None], name: str):
        self.handler = handler
        self.name = name
        self.sock: Optional[socket.socket] = None
        self.stop_event = threading.Event()
        self.threads: list[threading.Thread] = []
        self.errors: queue.Queue[BaseException] = queue.Queue()
        self.clients: set[socket.socket] = set()
        self.clients_lock = threading.Lock()

    @property
    def address(self) -> tuple[str, int]:
        check(self.sock is not None, f"{self.name} is not started")
        return self.sock.getsockname()[:2]

    def start(self) -> None:
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(128)
        self.sock.settimeout(0.2)
        thread = threading.Thread(target=self._accept, name=self.name, daemon=True)
        thread.start()
        self.threads.append(thread)

    def _accept(self) -> None:
        check(self.sock is not None, "server socket missing")
        while not self.stop_event.is_set():
            try:
                client, address = self.sock.accept()
            except socket.timeout:
                continue
            except OSError:
                if self.stop_event.is_set():
                    return
                self.errors.put(sys.exc_info()[1])
                return
            with self.clients_lock:
                self.clients.add(client)
            thread = threading.Thread(target=self._run, args=(client, address), daemon=True)
            thread.start()
            self.threads.append(thread)

    def _run(self, client: socket.socket, address: tuple[str, int]) -> None:
        try:
            self.handler(client, address)
        except BaseException as error:  # report to the foreground test
            if not self.stop_event.is_set():
                self.errors.put(error)
        finally:
            with self.clients_lock:
                self.clients.discard(client)
            try:
                client.close()
            except OSError:
                pass

    def close(self) -> None:
        self.stop_event.set()
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
        with self.clients_lock:
            clients = list(self.clients)
        for client in clients:
            try:
                client.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                client.close()
            except OSError:
                pass
        for thread in self.threads:
            if thread is not threading.current_thread():
                thread.join(1.0)

    def assert_no_errors(self) -> None:
        errors: list[BaseException] = []
        while True:
            try:
                errors.append(self.errors.get_nowait())
            except queue.Empty:
                break
        if not errors:
            return
        raise CheckError(f"{self.name} worker failed: {errors[0]}") from errors[0]


@dataclass
class BackendState:
    expected_handshakes: list[bytes] = field(default_factory=list)
    received_handshakes: list[bytes] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)


class MockBackend:
    def __init__(self) -> None:
        self.state = BackendState()
        self.server = ThreadedTcpServer(self._handle, "mock-backend")
        self.release_initial = threading.Event()

    @property
    def port(self) -> int:
        return self.server.address[1]

    def start(self) -> None:
        self.server.start()

    def expect(self, value: bytes) -> None:
        with self.state.lock:
            self.state.expected_handshakes.append(value)

    def _handle(self, sock: socket.socket, _address: tuple[str, int]) -> None:
        sock.settimeout(READ_TIMEOUT)
        body, remaining, actual = read_frame_raw(sock)
        with self.state.lock:
            self.state.received_handshakes.append(actual)
            try:
                expected_index = self.state.expected_handshakes.index(actual)
            except ValueError:
                expected_index = -1
            expected = (
                self.state.expected_handshakes.pop(expected_index)
                if expected_index >= 0
                else None
            )
        if expected is None:
            raise CheckError("backend received a non-identical handshake")
        # Keep every accepted load connection in setup until the clients have
        # all reached the load barrier.  This prevents the slow-reader burst
        # from blocking sequential connection setup.
        if not self.release_initial.wait(15.0):
            raise CheckError("load start gate was not released")
        # The slow-reader backend burst is intentionally larger than a socket
        # buffer; give its bounded send enough time to drain after the reader
        # pause without allowing an unbounded operation.
        sock.settimeout(20.0)
        # Initiate traffic from the backend as well as echoing client traffic;
        # this catches one-way relay implementations that only pass writes
        # originating at the frontend.
        noncanonical_outer_length = len(actual) >= 2 and actual[0] & 0x80 and actual[1] == 0
        initial_payload = BACKEND_SLOW_PAYLOAD if noncanonical_outer_length else BACKEND_INIT_PAYLOAD
        sock.sendall(initial_payload)
        if remaining:
            # Keep pipelined client bytes in the receive path.  They are echoed
            # below after the initiated backend payload.
            pending = remaining
        else:
            pending = b""
        if pending:
            sock.sendall(pending)
        while not self.server.stop_event.is_set():
            data = sock.recv(65536)
            if not data:
                return
            sock.sendall(data)

    def close(self) -> None:
        self.release_initial.set()
        self.server.close()

    def assert_ok(self) -> None:
        self.server.assert_no_errors()
        with self.state.lock:
            check(not self.state.errors, "; ".join(self.state.errors))


BACKEND_INIT_PAYLOAD = (b"proxy-rs-backend-init:") * 768
BACKEND_SLOW_PAYLOAD = (b"proxy-rs-backend-slow:") * 95000


class MasterHandler(http.server.BaseHTTPRequestHandler):
    server_version = "SyntheticMaster/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:  # noqa: N802 - stdlib callback name
        owner = self.server.owner  # type: ignore[attr-defined]
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/host/route":
            self._send(404, b"{}")
            return
        try:
            requested_port = int(urllib.parse.parse_qs(parsed.query).get("port", [""])[0])
        except ValueError:
            requested_port = -1
        with owner.request_lock:
            owner.requested_ports.append(requested_port)
        action = owner.routes.get(requested_port, "playable")
        if action == "timeout":
            time.sleep(3.5)
            self._send(200, owner.route_json("missing"))
        elif action == "malformed":
            self._send(200, b"{this is not json")
        elif action == "missing":
            self._send(200, owner.route_json("missing"))
        else:
            self._send(200, owner.route_json(action))

    def _send(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


class MockMaster:
    def __init__(self, backend_port: int) -> None:
        self.backend_port = backend_port
        self.dead_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.dead_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.dead_socket.bind(("127.0.0.1", 0))
        self.backend_down_port = self.dead_socket.getsockname()[1]
        self.routes: dict[int, str] = {}
        self.requested_ports: list[int] = []
        self.request_lock = threading.Lock()
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), MasterHandler)
        self.httpd.daemon_threads = True
        self.httpd.owner = self  # type: ignore[attr-defined]
        self.thread = threading.Thread(target=self.httpd.serve_forever, name="mock-master", daemon=True)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def start(self) -> None:
        self.thread.start()

    def route_json(self, action: str) -> bytes:
        statuses = {"stopped": "STOPPED", "started": "STARTED", "paused": "PAUSED"}
        if action in statuses:
            value = {"status": statuses[action], "backendHost": "127.0.0.1", "backendPort": self.backend_port}
            return json.dumps({"code": 0, "msg": "", "data": value}, separators=(",", ":")).encode()
        if action == "missing":
            return b'{"code":0,"msg":"missing route","data":null}'
        if action == "playable":
            value = {"status": "PLAYABLE", "backendHost": "127.0.0.1", "backendPort": self.backend_port}
            return json.dumps({"code": 0, "msg": "", "data": value}, separators=(",", ":")).encode()
        if action == "backend_down":
            # Keep a loopback port reserved but unlistened for the entire test
            # so this route cannot accidentally reach another local service.
            value = {"status": "PLAYABLE", "backendHost": "127.0.0.1", "backendPort": self.backend_down_port}
            return json.dumps({"code": 0, "msg": "", "data": value}, separators=(",", ":")).encode()
        raise CheckError(f"unknown mock route action {action}")

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(1.0)
        self.dead_socket.close()


class ProcessMemory:
    def __init__(self, pid: int) -> None:
        self.pid = pid
        self.available = sys.platform.startswith("linux")
        self.idle_rss_kb: Optional[int] = None
        self.peak_rss_kb: Optional[int] = None
        self.peak_hwm_kb: Optional[int] = None
        self.after_rss_kb: Optional[int] = None
        self.after_hwm_kb: Optional[int] = None
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._sample, name="proxy-memory", daemon=True)

    def read(self) -> Optional[tuple[int, int]]:
        if not self.available:
            return None
        try:
            values: dict[str, int] = {}
            with open(f"/proc/{self.pid}/status", encoding="utf-8") as source:
                for line in source:
                    if line.startswith(("VmRSS:", "VmHWM:")):
                        parts = line.split()
                        values[parts[0][:-1]] = int(parts[1])
            if "VmRSS" not in values or "VmHWM" not in values:
                return None
            return values["VmRSS"], values["VmHWM"]
        except (FileNotFoundError, PermissionError, ValueError, OSError):
            return None

    def start(self) -> None:
        current = self.read()
        if current:
            self.idle_rss_kb, self.peak_hwm_kb = current
            self.peak_rss_kb = current[0]
        self._thread.start()

    def mark_idle(self) -> None:
        current = self.read()
        if current:
            self.idle_rss_kb = current[0]
            self.peak_rss_kb = max(self.peak_rss_kb or current[0], current[0])
            self.peak_hwm_kb = max(self.peak_hwm_kb or current[1], current[1])

    def _sample(self) -> None:
        while not self._stop.wait(0.1):
            current = self.read()
            if current:
                rss, hwm = current
                self.peak_rss_kb = max(self.peak_rss_kb or rss, rss)
                self.peak_hwm_kb = max(self.peak_hwm_kb or hwm, hwm)

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(1.0)
        current = self.read()
        if current:
            self.after_rss_kb, self.after_hwm_kb = current
            self.peak_rss_kb = max(self.peak_rss_kb or current[0], current[0])
            self.peak_hwm_kb = max(self.peak_hwm_kb or current[1], current[1])


class ProxyProcess:
    def __init__(self, binary: str, master_url: str, cap: int, favicon: str) -> None:
        self.binary = os.path.abspath(binary)
        self.master_url = master_url
        self.cap = cap
        self.favicon = favicon
        self.listen = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listen.bind(("127.0.0.1", 0))
        self.port = self.listen.getsockname()[1]
        self.listen.close()
        self.stdout = tempfile.TemporaryFile(mode="w+b")
        self.stderr = tempfile.TemporaryFile(mode="w+b")
        command = [
            self.binary,
            "--listen",
            f"127.0.0.1:{self.port}",
            "--master",
            master_url,
            "--max-connections",
            str(cap),
            "--workers",
            "2",
            "--favicon",
            favicon,
        ]
        self.command = command
        self.process: Optional[subprocess.Popen[bytes]] = None
        self.memory: Optional[ProcessMemory] = None

    def start(self) -> None:
        self.process = subprocess.Popen(self.command, stdout=self.stdout, stderr=self.stderr)
        self.memory = ProcessMemory(self.process.pid)
        self.memory.start()
        deadline = time.monotonic() + 8.0
        last_error = ""
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise CheckError(f"proxy exited during startup ({self.process.returncode}): {self.diagnostics()}")
            try:
                with socket.create_connection(("127.0.0.1", self.port), CONNECT_TIMEOUT):
                    if self.memory is not None:
                        self.memory.mark_idle()
                    return
            except OSError as error:
                last_error = str(error)
                time.sleep(0.05)
        raise CheckError(f"proxy did not become ready: {last_error}; {self.diagnostics()}")

    def diagnostics(self) -> str:
        chunks = []
        for label, stream in (("stdout", self.stdout), ("stderr", self.stderr)):
            try:
                stream.seek(0)
                data = stream.read().decode("utf-8", "replace")
                if len(data) > 3000:
                    data = data[-3000:]
                chunks.append(f"{label}={data!r}")
            except OSError as error:
                chunks.append(f"{label}=<unavailable:{error}>")
        return " ".join(chunks)

    def close(self) -> None:
        if self.memory is not None:
            self.memory.stop()
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3.0)
            except subprocess.TimeoutExpired:
                self.process.kill()
                try:
                    self.process.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    pass
        self.stdout.close()
        self.stderr.close()


def connect_proxy(proxy: ProxyProcess, receive_buffer: Optional[int] = None) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if receive_buffer is not None:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, receive_buffer)
        sock.settimeout(CONNECT_TIMEOUT)
        sock.connect(("127.0.0.1", proxy.port))
        sock.settimeout(READ_TIMEOUT)
        return sock
    except BaseException:
        sock.close()
        raise


def assert_route_failure(proxy: ProxyProcess, port: int, message: str) -> None:
    sock = connect_proxy(proxy)
    try:
        sock.sendall(handshake(763, b"localhost\x00FML3\x00", port, 2))
        body, _ = read_frame(sock)
        packet_id, offset = get_varint(body)
        check(packet_id == 0, f"route failure packet id was {packet_id}")
        length, offset = get_varint(body, offset)
        text = body[offset : offset + length].decode("utf-8")
        check(json.loads(text).get("text") == message, f"unexpected route failure text: {text!r}")
    finally:
        sock.close()


def assert_status(proxy: ProxyProcess) -> None:
    sock = connect_proxy(proxy)
    try:
        # Use the deliberately unusual signed protocol value -1 and require
        # the status response to echo it exactly.
        sock.sendall(handshake(-1, b"localhost", 25565, 1) + status_request())
        body, _ = read_frame(sock, limit=1024 * 1024)
        packet_id, offset = get_varint(body)
        check(packet_id == 0, "status response packet id is not 0")
        length, offset = get_varint(body, offset)
        status = json.loads(body[offset : offset + length].decode("utf-8"))
        check(status["version"]["name"] == "RDI Proxy", "status version name mismatch")
        check(
            status["version"]["protocol"] == -1,
            f"status protocol mismatch: {status['version'].get('protocol')}",
        )
        check(status["players"]["max"] == 88888, "status player limit mismatch")
        check(status["players"]["online"] >= 1, "status did not report the active status client")
        sample = status["players"].get("sample")
        check(isinstance(sample, list) and sample, "status did not include an active player sample")
        check(uuid.UUID(sample[0]["id"]) is not None, "status player sample UUID is malformed")
        check(status["description"]["text"] == "RDI Universal Proxy Server", "status description mismatch")
        payload = b"sameconn"
        sock.sendall(ping_request(payload))
        ping_body, _ = read_frame(sock)
        ping_id, ping_offset = get_varint(ping_body)
        check(ping_id == 1 and ping_body[ping_offset:] == payload, "status-to-ping response mismatch")
        check(sock.recv(1) == b"", "status-to-ping connection did not close")
    finally:
        sock.close()


def assert_ping(proxy: ProxyProcess) -> None:
    payload = b"pingtest"
    sock = connect_proxy(proxy)
    try:
        sock.sendall(handshake(763, b"localhost", 25565, 1) + ping_request(payload))
        body, _ = read_frame(sock)
        packet_id, offset = get_varint(body)
        check(packet_id == 1 and body[offset:] == payload, "ping response did not echo payload")
        check(sock.recv(1) == b"", "ping connection did not close")
    finally:
        sock.close()


@dataclass
class LoadPair:
    client: socket.socket
    expected: deque[bytes] = field(default_factory=deque)
    expected_total: int = 0
    received_total: int = 0
    error: Optional[BaseException] = None

    def add_expected(self, payload: bytes) -> None:
        self.expected.append(payload)
        self.expected_total += len(payload)

    @property
    def pending(self) -> int:
        return self.expected_total - self.received_total

    def consume(self, actual: bytes) -> None:
        remaining = memoryview(actual)
        while remaining:
            check(self.expected, "received bytes without an expected payload")
            expected = self.expected[0]
            take = min(len(remaining), len(expected))
            check(
                remaining[:take].tobytes() == expected[:take],
                "load byte mismatch",
            )
            if take == len(expected):
                self.expected.popleft()
            else:
                self.expected[0] = expected[take:]
            self.received_total += take
            remaining = remaining[take:]


def run_load(proxy: ProxyProcess, backend: MockBackend, count: int, seconds: float) -> dict[str, object]:
    pairs: list[LoadPair] = []
    slow_started = threading.Event()
    slow_release_at: list[float] = []
    other_progress_at: list[float] = []
    try:
        for index in range(count):
            sock = connect_proxy(proxy, receive_buffer=64 * 1024 if index == 0 else None)
            requested_port = 27100
            hs = handshake(763, b"localhost\x00FML3\x00", requested_port, 2)
            if index == 0:
                # The noncanonical two-byte encoding is still a valid VarInt
                # for this length; the proxy must forward those raw bytes.
                length, offset = get_varint(hs)
                check(length < 128, "test handshake unexpectedly needs a multi-byte length")
                hs = bytes((length | 0x80, 0)) + hs[offset:]
            backend.expect(hs)
            # Fragment the handshake and pipeline a deterministic first payload.
            initial = bytes((index * 29 + n) & 0xFF for n in range(4096))
            sock.sendall(hs[:1])
            sock.sendall(hs[1:3])
            sock.sendall(hs[3:] + initial)
            pair = LoadPair(sock)
            pair.add_expected(BACKEND_SLOW_PAYLOAD if index == 0 else BACKEND_INIT_PAYLOAD)
            pair.add_expected(initial)
            pairs.append(pair)

        barrier = threading.Barrier(count)

        def exercise(pair_index: int, pair: LoadPair) -> None:
            try:
                barrier.wait(timeout=5.0)
                if pair_index == 0:
                    # Hold one client reader while the backend writes a fixed,
                    # multi-megabyte bounded burst.  The reduced receive
                    # buffer makes relay backpressure observable.
                    backend.release_initial.set()
                    slow_started.set()
                    slow_pause = min(1.5, max(1.0, seconds / 8.0))
                    slow_release_at.append(time.monotonic() + slow_pause)
                    time.sleep(slow_pause)
                elif pair_index == 1:
                    check(slow_started.wait(2.0), "slow-reader phase did not start")
                deadline = time.monotonic() + seconds
                sequence = 0
                while time.monotonic() < deadline:
                    size = 256 + ((sequence * 113 + pair_index * 17) % 4096)
                    payload = bytes((pair_index + sequence + n) & 0xFF for n in range(size))
                    pair.client.sendall(payload)
                    pair.add_expected(payload)
                    while pair.pending:
                        chunk = pair.client.recv(min(65536, pair.pending))
                        if not chunk:
                            raise CheckError("load backend closed a connection")
                        pair.consume(chunk)
                    if pair_index == 1 and not other_progress_at:
                        other_progress_at.append(time.monotonic())
                    sequence += 1
            except BaseException as error:
                pair.error = error

        threads = [threading.Thread(target=exercise, args=(i, p), daemon=True) for i, p in enumerate(pairs)]
        for thread in threads:
            thread.start()
        # Slow-reader phase: one socket pauses reads while the backend is given
        # bounded data, then it drains and catches up before the normal phase.
        time.sleep(min(0.25, max(0.05, seconds / 10.0)))
        for thread in threads:
            thread.join(seconds + 8.0)
        check(all(not thread.is_alive() for thread in threads), "load worker did not finish")
        for index, pair in enumerate(pairs):
            if pair.error:
                raise CheckError(f"load client {index} failed: {pair.error}") from pair.error
            check(pair.pending == 0 and not pair.expected, f"load client {index} received incomplete data")
        check(slow_started.is_set(), "slow-reader phase was not exercised")
        check(other_progress_at, "other connections made no progress during slow-reader phase")
        check(
            slow_release_at and other_progress_at[0] <= slow_release_at[0],
            "other connection did not progress while slow reader was paused "
            f"(other={other_progress_at!r}, release={slow_release_at!r})",
        )
        total_sent = sum(pair.expected_total for pair in pairs)
        total_received = sum(pair.received_total for pair in pairs)
        check(total_sent == total_received, "load byte totals differ")
        return {
            "completed_connections": len(pairs),
            "total_bytes": total_received,
            "slow_reader_payload_bytes": len(BACKEND_SLOW_PAYLOAD),
            "other_connection_progressed_during_slow_reader": True,
        }
    finally:
        backend.release_initial.set()
        for pair in pairs:
            try:
                pair.client.close()
            except OSError:
                pass
        backend.assert_ok()


def run_admission(proxy: ProxyProcess, cap: int) -> None:
    held: list[socket.socket] = []
    try:
        for _ in range(cap):
            held.append(connect_proxy(proxy))
        extra = connect_proxy(proxy)
        try:
            extra.settimeout(2.0)
            # Admission may accept then close, so a successful TCP connect is
            # not itself a failure.
            data = extra.recv(1)
            check(data == b"", "connection over admission cap remained open")
        except (ConnectionResetError, BrokenPipeError):
            pass
        finally:
            extra.close()
        held[0].close()
        held.pop(0)
        deadline = time.monotonic() + 5.0
        last_error: Optional[BaseException] = None
        while time.monotonic() < deadline:
            try:
                # A TCP connect alone can succeed while the proxy immediately
                # rejects the session.  Complete the local status/ping flow.
                assert_status(proxy)
                last_error = None
                break
            except (OSError, CheckError) as error:
                last_error = error
                time.sleep(0.1)
        check(last_error is None, f"admission recovery did not accept a complete session: {last_error}")
    finally:
        for sock in held:
            try:
                sock.close()
            except OSError:
                pass


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Synthetic local smoke/load test for proxy-rs")
    parser.add_argument("--binary", required=True, help="path to the proxy-rs executable")
    parser.add_argument("--connections", type=int, default=20)
    parser.add_argument("--seconds", type=float, default=10.0)
    parser.add_argument("--max-rss-mb", type=float, default=100.0, help="decimal MB RSS/HWM budget")
    parser.add_argument("--favicon", default=None, help="favicon path passed to proxy")
    return parser.parse_args(argv)


def run(args: argparse.Namespace) -> dict[str, object]:
    check(args.connections >= 2, "--connections must be at least 2 for slow-reader progress validation")
    check(args.seconds > 0, "--seconds must be positive")
    check(args.max_rss_mb > 0, "--max-rss-mb must be positive")
    favicon = args.favicon
    if favicon is None:
        favicon = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../proxy/favicon.png"))
    backend = MockBackend()
    master: Optional[MockMaster] = None
    proxy: Optional[ProxyProcess] = None
    phases: list[str] = []
    cap = args.connections + 4
    ports = {
        27100: "playable",
        27101: "stopped",
        27102: "started",
        27103: "paused",
        27104: "missing",
        27105: "malformed",
        27106: "timeout",
        27107: "backend_down",
    }
    try:
        backend.start()
        master = MockMaster(backend.port)
        master.routes.update(ports)
        master.start()
        proxy = ProxyProcess(args.binary, master.url, cap, favicon)
        proxy.start()
        phases.append("startup")
        with master.request_lock:
            master.requested_ports.clear()
        assert_status(proxy)
        assert_ping(proxy)
        with master.request_lock:
            check(25565 not in master.requested_ports, "status/ping unexpectedly queried the master route")
        phases.append("status_ping")
        assert_route_failure(proxy, 27101, STOPPED_ERROR)
        assert_route_failure(proxy, 27102, STARTED_ERROR)
        assert_route_failure(proxy, 27103, PAUSED_ERROR)
        assert_route_failure(proxy, 27104, GENERIC_ROUTE_ERROR)
        assert_route_failure(proxy, 27105, GENERIC_ROUTE_ERROR)
        assert_route_failure(proxy, 27106, GENERIC_ROUTE_ERROR)
        phases.append("route_statuses_and_failures")
        dead = connect_proxy(proxy)
        try:
            dead.sendall(handshake(763, b"localhost\x00FML3\x00", 27107, 2))
            check(read_until_eof(dead, 4.0) == b"", "backend connection failure did not close client")
        finally:
            dead.close()
        phases.append("backend_connect_failure")
        run_admission(proxy, cap)
        phases.append("admission_cap_and_recovery")
        load_report = run_load(proxy, backend, args.connections, args.seconds)
        phases.append("bidirectional_load_and_slow_reader")
        backend.assert_ok()
        check(not backend.state.expected_handshakes, "backend did not receive all expected handshakes")
        memory = proxy.memory
        check(memory is not None, "proxy memory sampler was not initialized")
        memory.stop()
        report_memory: dict[str, object] = {
            "measurement_available": memory.available,
            "idle_rss_kb": memory.idle_rss_kb,
            "peak_rss_kb": memory.peak_rss_kb,
            "peak_hwm_kb": memory.peak_hwm_kb,
            "after_rss_kb": memory.after_rss_kb,
            "after_hwm_kb": memory.after_hwm_kb,
            "max_rss_mb": args.max_rss_mb,
            "memory_pass": None,
        }
        if memory.available:
            check(memory.peak_hwm_kb is not None, "Linux VmHWM was unavailable")
            report_memory["memory_pass"] = memory.peak_hwm_kb * 1024 <= args.max_rss_mb * 1_000_000
            check(bool(report_memory["memory_pass"]), f"proxy VmHWM exceeded {args.max_rss_mb} MB")
        return {
            "ok": True,
            "synthetic": True,
            "protocol": "Minecraft handshake/status/ping plus TCP relay",
            "connections": args.connections,
            "seconds": args.seconds,
            "admission_cap": cap,
            "phases": phases,
            "load": load_report,
            "memory": report_memory,
        }
    except BaseException as error:
        if proxy is not None:
            raise CheckError(f"{error}; proxy diagnostics: {proxy.diagnostics()}") from error
        raise
    finally:
        if proxy is not None:
            proxy.close()
        if master is not None:
            master.close()
        backend.close()


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    try:
        report = run(args)
    except BaseException as error:
        report = {
            "ok": False,
            "synthetic": True,
            "error": str(error),
            "error_type": type(error).__name__,
            "traceback": traceback.format_exc(limit=4),
        }
        print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
        return 1
    print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
