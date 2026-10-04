# Packet references (repeated packet dedup)

When the server sends a client a packet whose exact bytes it sent recently on the
same connection, it can send a small reference to the earlier copy instead. The
client restores the original bytes before the vanilla packet decoder runs, so mods
and vanilla code receive byte-identical packets.

Scope of the first version:

- Server-to-client only, Forge1.20.1 only. NeoForge1.21.1 and Fabric compile the
  shared codec but never enable it.
- Packets inside batch blocks are recorded but never replaced by references.
- Off by default; enable it per server with `-Drdi.pktref.enabled=true`.

## Negotiation

Both sides register the presence-only channel `rdi:pktref`, version `1`, with
`acceptMissingOr`, so peers without it still connect. It is separate from
`rdi:batch` because older peers refuse a bumped `rdi:batch` version.

The server enables references for a player only when all of these hold:

| Check | Otherwise |
| --- | --- |
| `rdi.pktref.enabled=true` | Nothing happens. |
| Not a memory (integrated server) connection | Nothing happens. |
| The client announced `rdi:pktref` | Logs `rdi:pktref is absent`. |
| Network compression is on | Logs `network compression is off`. |
| The compression encoder is RDI's | Logs an error with the actual handlers. |

## Wire format, version 1

Both frames reuse the leading VarInt of the legacy compression envelope, which
never declares a negative size. `0`, positive sizes and `-1` (batch block) keep
their meanings.

| Leading VarInt | Frame | Rest |
| --- | --- | --- |
| `-2` | Reference | VarInt slot, then a 4-byte check (big-endian) |
| `-3` | Control | 1-byte opcode; opcode `1` is START: VarInt version, VarInt slots, VarInt max entry bytes |

A reference costs 11 bytes on the wire for slots 0-127 and 12 bytes from slot 128,
including the outer length prefix. A negative VarInt always takes 5 bytes; a
compact 1-byte tag would save about 4 bytes per reference and is left for a later
version.

Examples, without the outer prefix:

```text
START v1, 256 slots, 1024 bytes   fd ff ff ff 0f 01 01 80 02 80 08
reference to slot 0               fe ff ff ff 0f 00 <check: 4 bytes>
```

The entry hash is FNV-1a 64 finalized with MurmurHash3 `fmix64`. The check is its
low 32 bits. `PacketRefFormatTest` locks hash and frame bytes against values from
an independent implementation; changing either needs a new protocol version.

## The packet tables

The server encoder and the client decoder each keep a `PacketRefCache` and apply
the same sequence of packets to it.

| Setting | Default | Accepted range |
| --- | --- | --- |
| Slots | 256 | 1-1024 |
| Max entry bytes | 1024 | 8-2048 |

- Only packets of 8 to max-entry bytes (uncompressed packet bytes) are recorded.
- A plain packet that exactly matches an entry (hash, then byte comparison) only
  moves that entry to newest. Any other eligible packet takes the lowest
  never-used slot, or else evicts the least recently used slot and reuses its
  number.
- A reference moves its slot to newest, the same transition as a matching plain
  packet. The server therefore chooses freely on a hit: it sends a reference only
  when the reference frame is smaller than the uncompressed legacy frame
  (`size + 1 + VarInt(size + 1)` bytes); packets at or above the compression
  threshold always become references.

Memory per connection and side is at most slots times max entry bytes of content:
256KB by default, 2MB at the limits, plus small index arrays. The server total
grows with the number of online players.

## Alignment with START

The client prepares its decoder when the login packet arrives on the Netty thread
(and again, harmlessly, at the Forge login event). The server enables references in
the player-joined event, after the login packet was written, so earlier plain
packets may already be on the wire. START is the common starting point:

1. The server flushes its batch buffer, writes START and starts an empty table
   before flushing START, so a packet that a completed write's listener sends
   during that flush already lands in the new table. If the flush or the START
   write fails, the connection closes and references never turn on.
2. The client accepts START only once prepared, resets its table and records from
   the next frame on. Frames before START are not recorded on either side.
3. Another START resets both tables together. There is no stop frame; turning the
   feature off takes a server restart.

| Client state | Event | Result |
| --- | --- | --- |
| Idle | prepare | Ready |
| Ready | START | Active, empty table |
| Active | START | Active, empty table |
| Ready or Active | prepare | No change |
| Any | leave | Idle, table dropped |
| Idle | START | Disconnect |
| Idle or Ready | reference | Disconnect |

## Which packets become references

| Encoder exit | Handling |
| --- | --- |
| Direct single packet (`writeLegacy`) | The only exit that sends references. |
| Batch flush with one record | Recorded as plain. |
| Batch falling back to several legacy frames | Each recorded as plain. |
| Batch block | Each record recorded as plain. |

Every packet updates the table exactly once, synchronously while its frame is
built, in wire order. Today only attribute updates and verified L2 Tabs messages
are batched; everything else goes through the direct path.

## Failures

The client disconnects with a `DecoderException` for a START or reference in the
wrong state, an empty or out-of-range slot, a check mismatch (`packet tables are
out of sync`), START parameters outside the accepted range, an unknown opcode or
version, and truncated, overlong-VarInt or trailing-byte frames. Trailing bytes
must fail: Netty's `ByteToMessageDecoder` would otherwise decode them as another
frame, and for the same reason a rejected extension frame drops its remaining
bytes. The server routes every encoder error after a table update, including
allocation failures, through `failConnection`, because its table may already
include a packet the client will never receive.

The 32-bit check makes an undetected desync very unlikely, but it cannot rule one
out.

## Settings

| Property | Default | Meaning |
| --- | --- | --- |
| `rdi.pktref.enabled` | `false` | Enable packet references for negotiated clients. |
| `rdi.pktref.slots` | `256` | Table slots, clamped to 1-1024 with a warning. |
| `rdi.pktref.maxEntryBytes` | `1024` | Largest recorded packet, clamped to 8-2048 with a warning. |

## Measuring the savings

Reference frames appear in `packet_batch_frame_totals` with `frame_kind = 'Ref'`.
Their `sum_payload_bytes` is the original packet size, `sum_frame_bytes` and
`sum_outer_prefix_bytes` describe the reference frames, and
`sum_replaced_frame_bytes` is the legacy frame each reference replaced, including
its outer prefix.

```sql
SELECT SUM(sum_replaced_frame_bytes - sum_frame_bytes - sum_outer_prefix_bytes)
FROM packet_batch_frame_totals WHERE frame_kind = 'Ref';
```

The result is the Minecraft frame bytes saved by replacing packets with
references. It excludes START frames and is not the network-card traffic saving.
`sum_payload_bytes - sum_frame_bytes` is not a saving: the original packet might
have been compressed anyway.

For packets at or above the compression threshold, the server compresses an
entry once to learn its replaced size while metrics run, keeps that size with the
entry, and forgets it when the threshold changes. If that measurement fails, the
entry keeps the reference's own size and counts as no saving. `sum_compression_nanos`
of `Ref` rows is the lookup and frame-building time; with references on, `Legacy`
rows from the direct path include the lookup as well. Rows from batch flushes
exclude table recording.

## Expected savings and their limits

Simulated on the W5 capture (12.7 hours): about 84% of mod packets would hit,
saving about 110MB, roughly 7.5% of W5 downlink, with the 4-byte check. These are
estimates: the baseline approximates Zstd with zlib at an assumed threshold of 256,
and W5 only captured custom payload and attribute packets. On a live server every
eligible vanilla packet also takes slots, which may lower the mod hit rate, and
its own savings are unknown. Measure on a test server with the SQL above.

## Rollout and rollback

1. Deploy both jars; enable `-Drdi.pktref.enabled=true` on a test server only and
   check that the log shows `packet references requested` for each player.
2. Play through joining, dimension changes, death and respawn, reconnecting and two
   players at once; check Epic Fight combat, Xaero's minimap and Jade; run for at
   least two hours without reference-related disconnects.
3. Compare the measured savings with total downlink, and compare server network
   thread CPU with the feature on and off.
4. Roll back by removing the property and restarting the server; clients need no
   change.
