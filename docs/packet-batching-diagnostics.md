# Packet batching diagnostics

The shared codec stays inactive until a peer negotiates batching. Forge enables batching for compatible player connections by default; long-window batching and packet capture have separate switches, and capture stays off unless one player name is configured. A capture records only that connection's successfully encoded outbound packet bytes.

## Server settings

Use these JVM properties on the 1.20.1 Forge server:

| Property | Default | Meaning |
| --- | --- | --- |
| `rdi.batch.enabled` | `true` | Enables batching for compatible players. Set `false` to send packets through the legacy per-packet path. |
| `rdi.batch.longWindow` | `false` | Allows only messages explicitly classified as safe to wait four ticks. All other eligible messages use one tick. |
| `rdi.batch.samplePlayer` | absent | Exact player name to capture. When absent, packet sampling is off. |
| `rdi.batch.sampleSeconds` | `30` | Capture duration, limited to `1`–`300` seconds. |
| `rdi.batch.sampleMiB` | `16` | In-memory capture limit, limited to `1`–`64` MiB. |

The selected connection stores packet bytes, packet identity, policy, threshold, global tick boundaries, barrier events, and monotonic event times in a bounded memory buffer. It performs no disk writes from Netty. Duration expiry creates a complete sample; hitting a memory or event limit marks the capture incomplete. Disabling compression ends the measurement window. The server exports captures off-thread under `rdi/batch-stream-<timestamp>-<uuid>.rdibatch`.

The capture keeps each message's safe candidate policy even while `rdi.batch.longWindow=false`, so the offline four-tick comparison can evaluate approved candidates without enabling that delay for live players.

## Replay

Run the test-source `calebxzau.rdi.mc.zstdcodec.ZstdStreamReplay` main class with the codec test runtime classpath and pass one `.rdibatch` path. In IntelliJ, use an Application configuration for `ZstdStreamReplay` and select the same test classpath used by the Forge codec tests. The replay reads only the file; it does not connect to a server.

Replay runs the identical ordered stream in three modes:

1. Legacy per-packet compression.
2. All eligible messages wait at most one global tick.
3. Recorded policies, including four-tick eligibility only for messages already marked safe.

It checks that every mode decodes to the same packet bytes in the same order. Output reports encoded inner-envelope bytes, bytes including the outer Minecraft frame-length prefixes, frame counts, average records per frame, elapsed time inside encoder writes and flushes, recorded batch wait time, and early barrier flushes. These are codec stream measurements; they do not measure network-interface bytes, server TPS, or player responsiveness.

The replay refuses incomplete samples. A real-stream saving is meaningful only when the capture came from the intended modpack and includes the representative gameplay being evaluated. Keep diagnostic sampling disabled for the separate runtime/TPS comparison so sampler overhead is not part of that measurement.
