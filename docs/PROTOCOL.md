# Wire protocol

Transport: UDP, phone → PC, default port **9876**. Discovery is a
separate JSON exchange on port **9877** (see below).

Every message is exactly **32 bytes**, little-endian, with a fixed
layout. There is no fragmentation, no length prefix, and no dynamic
encoding: a packet either parses completely or is dropped.

## Packet layout

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 | magic | ASCII `MGPD` |
| 4 | 1 | version | currently `1` |
| 5 | 1 | message type | see below |
| 6 | 2 | controller id | u16, random per streaming session |
| 8 | 4 | sequence | u32, increments on every packet sent |
| 12 | 8 | timestamp | i64, sender's Unix time in ms |
| 20 | 2 | buttons | u16 bit field |
| 22 | 2 | left stick X | i16 |
| 24 | 2 | left stick Y | i16 |
| 26 | 2 | right stick X | i16 |
| 28 | 2 | right stick Y | i16 |
| 30 | 1 | left trigger | u8 |
| 31 | 1 | right trigger | u8 |

### Message types

| Value | Name | Meaning |
|---|---|---|
| 1 | STATE | full controller state |
| 2 | PING | latency probe; receiver echoes it back as PONG |
| 3 | PONG | reply to PING, same sequence and timestamp |
| 4 | GOODBYE | streaming stopped; release everything now |

### Button bits

| Bit | Button | Bit | Button |
|---|---|---|---|
| 0 | A | 8 | Left thumb (LS click) |
| 1 | B | 9 | Right thumb (RS click) |
| 2 | X | 10 | D-pad up |
| 3 | Y | 11 | D-pad down |
| 4 | Left shoulder | 12 | D-pad left |
| 5 | Right shoulder | 13 | D-pad right |
| 6 | Back / Select | 14 | Guide |
| 7 | Start | 15 | reserved |

### Axis encoding

Sticks are `-32767..32767`; triggers are `0..255`. Both use the **Xbox
convention: up and right are positive**. Android reports stick Y as
negative-up, so the phone negates it before sending. Normalizing on the
phone keeps the receiver free of any per-device knowledge.

## Semantics

**Full state, not events.** Every STATE packet carries the complete
controller state. A receiver applies it wholesale, so a lost packet
costs at most one update rather than desynchronizing a button forever.

**Send on change plus heartbeat.** The phone sends immediately whenever
the state changes, and re-sends the current state every **50 ms**
regardless. Idle traffic is therefore ~20 packets/s of 32 bytes.

**Sequence numbers.** The receiver keeps the last accepted sequence and
rejects any packet whose sequence is not strictly newer, comparing with
wraparound-safe signed arithmetic (`(int)(seq - last) > 0`). This drops
duplicates and out-of-order deliveries. Gaps are counted as an estimate
of packet loss.

**Controller id.** If a packet arrives with a different controller id,
the receiver treats it as a new session and resets its sequence state,
so restarting the phone app does not require restarting the PC app.

**Disconnect safety.** The receiver resets the virtual pad to neutral
if no packet arrives for **500 ms** (10 missed heartbeats), and
immediately on GOODBYE. The phone sends GOODBYE when streaming stops.

**Timestamps.** Carried for diagnostics only. Phone and PC clocks are
not synchronized, so one-way latency derived from them is not
meaningful; use PING/PONG round-trip instead.

## Discovery (JSON, port 9877)

The phone broadcasts to `255.255.255.255:9877`:

```json
{"type": "mg_discovery_request", "timestamp": 1713912345678, "pairCode": "1234"}
```

The PC replies with:

```json
{"type": "mg_discovery_response", "host": "192.168.1.20", "port": 9876, "pairCode": "1234"}
```

If the PC has a pairing code configured, a request must carry a
matching one, otherwise it is ignored. The pairing code is a
convenience for picking the right PC on a shared network, not a
security mechanism — this protocol is unauthenticated and unencrypted
and is meant for a trusted local network only.

## Implementation status

- Phone sends STATE and GOODBYE; PC handles STATE, GOODBYE and answers
  PING with PONG.
- The phone does **not** yet send PING, so round-trip latency is not
  displayed anywhere. The PC side is in place for when it does.
