"""Developer framed-JSON client. Usage: python service-client.py -- command args...

Enter JSON request objects, one per line. Never enter credentials in a recorded
terminal session. EOF closes service stdin; responses are printed as JSON.
"""
import json
import struct
import subprocess
import sys
import threading

MAX_FRAME = 8 * 1024 * 1024


def read_exact(stream, count):
    result = bytearray()
    while len(result) < count:
        part = stream.read(count - len(result))
        if not part:
            raise EOFError("Service frame truncated")
        result.extend(part)
    return bytes(result)


def receive(stream):
    try:
        while True:
            first = stream.read(1)
            if not first:
                return
            size = struct.unpack(">I", first + read_exact(stream, 3))[0]
            if not 0 < size <= MAX_FRAME:
                raise ValueError("Invalid service frame length")
            message = json.loads(read_exact(stream, size).decode("utf-8"))
            print(json.dumps(message, ensure_ascii=False), flush=True)
    except (EOFError, ValueError, OSError) as error:
        print(f"Protocol error: {error}", file=sys.stderr)


def main():
    command = sys.argv[1:]
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        raise SystemExit("Usage: service-client.py -- command args...")
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        receiver = threading.Thread(target=receive, args=(process.stdout,), daemon=True)
        receiver.start()
        try:
            for line in sys.stdin:
                message = json.loads(line)
                if not isinstance(message, dict):
                    raise ValueError("Request must be an object")
                body = json.dumps(message, ensure_ascii=False).encode("utf-8")
                if not 0 < len(body) <= MAX_FRAME:
                    raise ValueError("Request too large")
                process.stdin.write(struct.pack(">I", len(body)) + body)
                process.stdin.flush()
        finally:
            process.stdin.close()
        try:
            result = process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            result = process.wait()
        receiver.join(timeout=2)
        raise SystemExit(result)


if __name__ == "__main__":
    main()
