# DJI camera BLE protocol (reference)

This document describes the BLE/GATT protocol this app uses to talk to
DJI Osmo Action cameras: GATT layer, frame format, the commands the app
needs, and the behaviour that only shows up on real hardware.

**Source:** derived from DJI's official demo
(`dji-sdk/Osmo-GPS-Controller-Demo`) via `rhoenschrat/DJI-Remote`, and
verified against real DJI Osmo Action 4 cameras.

> ⚠️ There are **two separate DJI BLE protocols** on these cameras. This
> document covers only the **R SDK / DUML remote-control protocol**
> (service `0xFFF0`, SOF `0xAA`) — record start/stop, mode switch, status.
> A separate WiFi/RTMP pairing protocol (SOF `0x55`) exists for live
> streaming; it is not relevant here.

## BLE GATT layer

| What | UUID |
|---|---|
| Service | `0xFFF0` |
| Write characteristic (commands to the camera) | `0xFFF5` |
| Notify characteristic (status/replies from the camera) | `0xFFF4` |
| CCCD (enable/disable notify) | `0x2902` (standard) |

**Recognising a camera during a BLE scan:** in the advertisement
manufacturer data (AD type `0xFF`):
- byte 0 = `0xAA`, byte 1 = `0x08`, byte 4 = `0xFA` → this is a DJI camera.
- the device id is in bytes 2-3 (little-endian uint16).
- the device name comes from the regular Complete/Shortened Local Name AD
  fields (fall back to "DJI Camera" when there is none).

**No pairing/bonding needed.** The cameras work without BLE encryption.
Android sometimes asks for pairing on its own; do not force it.

**One active BLE link per camera.** While one device is connected to a
camera, nothing else (including the original DJI GPS Remote) can connect to
that same camera.

**Discovery order that works:** MTU exchange → discover **all** services
(do not filter on `0xFFF0` alone — some cameras/firmwares require a full
GATT database discovery before they allow notifications) → all
characteristics of the `0xFFF0` service → all descriptors of the notify
characteristic (for the CCCD handle) → write the CCCD (`0x0100`) to enable
notifications.

## Frame format (DUML-like)

Every command/reply/status push is one binary frame:

```
[SOF 1B][VER+LEN 2B][CMDTYPE 1B][ENC 1B][RES 3B][SEQ 2B LE][CRC16 2B][CMDSET 1B][CMDID 1B][DATA ...][CRC32 4B]
```

- **SOF**: always `0xAA`.
- **VER+LEN** (2 bytes): holds the total frame length; the lower bits are
  the length, the upper bits the protocol version.
- **CMDTYPE** (1 byte): frame type (command/reply/push).
- **ENC** (1 byte): encryption method, always `0x00` (none).
- **RES** (3 bytes): reserved, zeros.
- **SEQ** (2 bytes, little-endian): increasing sequence number, kept per
  connection by the sender.
- **CRC16** (2 bytes): covers bytes SOF up to and including SEQ (the header).
- **CMDSET** (1 byte) + **CMDID** (1 byte): which command this is (see the
  table below).
- **DATA**: the payload; its layout depends on CMDSET/CMDID.
- **CRC32** (4 bytes): covers bytes SOF up to the end of DATA (the whole
  frame minus the CRC32 itself).

**Important:** both CRCs are **DJI-specific variants** with init value
`0x3AA3` — they are not standard CRC16-CCITT or CRC32. Deriving the tables
yourself almost guarantees a silently ignored command. See
`ble/DjiCrc.kt` for the tables used here.

## Relevant commands (CmdSet `0x1D`)

| CmdID | Direction | Meaning | Payload |
|---|---|---|---|
| `0x03` | → camera | Record control | `device_id (u32)`, `record_ctrl (u8: 0=start, 1=stop)`, `reserved[4]` → reply: `ret_code (u8)` |
| `0x02` | ← camera (notify) | Status push | among others `camera_status`: `0x00`=screen off, `0x01`=live view, `0x02`=playback, `0x03`=**busy with photo/video** (= recording confirmation), `0x05`=pre-recording |
| `0x06` | ← camera (notify) | Status push (newer firmware) | extended mode info: ASCII mode name and parameters (resolution, fps, remaining time/capacity, battery, temperature state). Contains **no** `camera_status` |
| `0x04` | → camera | Camera mode switch | `device_id (u32)`, `mode (u8)`, `reserved[4]` |
| `0x00` | → camera | GPS data push | date/time (`YYYYMMDD` + `HHMMSS`, time zone **fixed +8**), GPS position, speed, accuracy, `satellite_number` — one-way traffic (to the camera); the camera's time/position cannot be read back |

**Main usage pattern** — start/stop recording on one or more cameras:
1. Send CmdID `0x03` with `record_ctrl=0` (start) or `record_ctrl=1` (stop).
2. Do **not** treat the direct reply to that command as confirmation that
   recording is running — wait for the next `0x1D02` status push with
   `camera_status == 0x03` (start), or anything other than `0x03` (stop).
   The command reply only confirms the command was received and parsed.
3. With multiple cameras, each has its own sequence number and must be
   confirmed separately — do not assume both react at the same time.

## Verified on real cameras

Everything below fails silently if you miss it.

- **A GATT link is not a protocol connection.** There is a handshake,
  `CmdSet 0x00 / CmdID 0x19`: you send a connection request, the camera
  replies, the camera then sends its own request with `verify_mode = 2`,
  and you must confirm that on **its** sequence number. Until the handshake
  is complete the camera accepts commands and executes none of them. The
  first time, the camera shows a four-digit code that is confirmed there
  (`verify_mode = 1`); afterwards `verify_mode = 0` is enough. Remember per
  camera that you are paired, or it will ask every time.
- **Subscribe to status pushes:** `0x1D05` with `push_mode = 3` and
  `push_freq = 20` (2 Hz, the only allowed value). The CCCD write only
  opens the channel; without a subscription the camera stays silent.
- **There are two different `device_id`s.** The one in the advertisement is
  the camera model — two Osmo Action 4s both report `0x0014`, so it cannot
  tell two cameras apart. The one in command payloads is **your own id from
  the handshake**. Mix them up and the command is accepted and ignored.
  Which camera responds is determined by the link you write on.
- **Recording state comes only from `0x1D02`**, offset 1. `0x05`
  (pre-recording) also counts as recording, not just `0x03`.
- **A GATT link carries one write at a time.** Stack a second one on top and
  it is rejected — and a rejected command is indistinguishable from an
  ignored one. Use a queue that waits for `onCharacteristicWrite`.
- **BLE delivers packets, not frames.** With a small MTU one frame arrives
  in pieces; buffer and cut on the length field.
- **Sleep is not power-off.** After inactivity `power_mode` goes to `3`; the
  BLE link stays up and the camera keeps pushing, but refuses everything
  except `0x00/0x1A` with `power_mode = 0`. That command wakes it.
- **Without an SD card the camera does not record** and the status stays
  `Idle`, with no error at all.
- **Two cameras cannot be made frame-synchronous.** Their sensors run on
  their own clocks with random phase, so even with a perfectly simultaneous
  command the frame grid is up to a full frame apart (33 ms at 30 fps).
  `requestConnectionPriority(HIGH)` narrows the spread of the command but
  does not solve this — a synchronisation point in the picture is needed.
