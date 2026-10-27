# Mixer integration

BeatLink Monitor can read a Pioneer DJ mixer's live EQ knob positions, let them
dim the matching parts of a player's waveform, and read/write the mixer's
"utility" settings. The protocol was first reconstructed by static analysis of
another application and has since been **checked against one real rig (DJM-V10
firmware 1.30, four CDJ-3000 firmware 3.22; one session on 2026-10-27)**:
receiving the live state, the version request and reading all 15 utility
settings work. Writing a setting was tried once with an unchanged value (the mixer
answered and confirmed); a real change has not been verified yet.
The integration is **on by default**, so all data of the real hardware is read; writes
only happen on an explicit request.

## What works today

| Feature | Verified with |
| --- | --- |
| Packet building/parsing (`mixer/MixerProtocol.kt`) | Unit tests, real DJM-V10 |
| Receiving the live state (`0x39`) | **Real DJM-V10**, but only with the third-party identity below |
| EQ → waveform brightness | **Real DJM-V10 + CDJ-3000** (EQ of channel N dims player N); calibration 0 = down, 128 = center, 255 = full confirmed |
| Firmware version request (`0x30`/`0x31`) | **Real DJM-V10** (`1.30`) |
| Utility read (`0x3a`/`0x3b`, obfuscated id accepted) | **Real DJM-V10**: items 0-12 valid, 13 and 14 answered "unavailable" |
| Utility write with read-back confirmation | Fake transport in tests; **real DJM-V10 once, with an unchanged value** (acknowledged within 3 ms, no change possible to observe) |

## Settings

Settings → Mixer (all on by default; changing the first needs a restart):

1. **Enable mixer integration.** The backend wraps the Pro DJ Link status socket (UDP 50002)
   of `beat-link` so that the mixer packet types `beat-link` ignores can be received, and
   requests can be sent from the same port.
2. **Announce as third-party device.** Needed for the mixer to send its live state, see below.
   It adds one more device to the Pro DJ Link network.
3. **Allow changing mixer settings.** Permits writes. A write is only sent when you press
   **Write** in the Mixer tab (or call the API), and counts as confirmed only after the mixer
   reports the new value back. Anyone who can reach the web interface can do that, so keep
   **Allow LAN Access** off on untrusted networks.
4. Optionally the mixer's IPv4 address; otherwise the mixer found by Pro DJ Link discovery
   (keep-alive category 3) is used. Only local, link-local and loopback addresses are accepted.

## EQ controls waveform brightness

With live data, channel N's EQ dims the waveform of player N:

| Band | RGB waveform | Three-band waveform |
| --- | --- | --- |
| Low | red | blue layer |
| Mid | green | amber layer |
| High | blue | white layer |

Brightness is 10 % (setting "Minimum brightness") with the knob fully down
and 100 % at its center. Above the center the band glows: the waveform of that
band is added once more on top, up to +40 % (setting "Boost above center",
0 = off) at raw 255. The waveform data only has three bands, so a 4-band mixer
cannot get one colour per knob: the mid layer follows the average of Hi-Mid and
Lo-Mid (see below). The classic blue waveform has no bands and is not
affected. Without live data (no mixer, or no packet for 5 s) waveforms are
drawn at full brightness.

The raw value scale was verified on a real DJM-V10 (0 = knob fully down, detent at exactly
`0x80`, 255 = fully up). The Mixer tab shows the raw values; Settings → Mixer lets you set
the raw values for "knob fully down" (default 0) and "knob at center" (default
128). The mid band follows Hi-Mid on 3-band mixers (they keep their single mid
knob there and leave Lo-Mid at 0 on all channels; detected automatically) and
the average of Hi-Mid and Lo-Mid on 4-band mixers; this can be forced.

## Audio levels

A DJM-V10 sends its meter data (type `0x58`, 512 bytes, UDP port 50001) about 30 times a second to peers it
knows, without being asked; BeatLink Monitor also sends the documented request (`0x57`, 40 bytes, from port 50001,
alternating plain and obfuscated device id every 3 s while no data arrives) as a fallback. Layout as checked on a
real DJM-V10: six clip bytes at `0x24`; eight groups of 30 big-endian words from `0x2c` with stride `0x3c`. The
first 15 words of each group carry values (probably a spectrum, the second 15 words stayed zero); the high byte
is the level (0..255, the Mixer tab shows -40..0 dB). Groups 0-3 are channels 1-4, groups 4 and 5 the master
left/right, groups 6 and 7 channels 5 and 6. The assignment matched the knob positions of the test rig (channels
with EQ at 0 showed low levels); what exactly the 15 words and the clip bytes represent is not verified.
`GET /api/mixer/levels` returns the latest frame.

## Reconstructed packets

All on UDP port 50002, all starting with the 10-byte Pro DJ Link magic header,
packet type at offset `0x0a`. Offsets are packet offsets.

### Live state, type `0x39` (mixer → us)

At least `0xf8` bytes; the payload starts at `0x24`. Payload offsets, raw
unsigned bytes:

- Six channel blocks of `0x18` bytes at `(channel - 1) * 0x18`: `+0` input,
  `+1` trim, `+2` comp, `+3` eqHi, `+4` eqHiMid, `+5` eqLoMid, `+6` eqLow,
  `+7` filter, `+8` send, `+9` cue (1 = on), `+0xa` cue2 (1 = on), `+0xb`
  fader (0–255), `+0xc` crossfader assign (1 = A, 2 = B, anything else THRU).
  Input: 0 USB A, 1 USB B, 2 Digital, 3 Line, 4 Phono, 5 Int, 6-8 Rtn 1-3, 9 Rtn All.
- Global: `0x90` crossfader, `0x93` master level, `0x94` master filter, `0x95`
  master cue, `0x97` isolator on, `0x98`–`0x9a` isolator hi/mid/low, `0x9b`
  booth, `0x9c`/`0x9d` booth hi/low, `0xa2`–`0xa4` FX frequency low/mid/hi,
  `0xa5` FX select, `0xa6` FX channel (0-5 = CH1-CH6, 6 Mic, 7 Master, 8 CRF A,
  9 CRF B), `0xa7` FX level, `0xa8` FX on, `0xbf` headphone mix, `0xc0`
  headphone level. Master cue, isolator on, FX on and the three FX frequency
  bytes are flags (1 = on). Eight payload bytes (0xa9, 0xab, 0xb2, 0xb3, 0xb7,
  0xbe, 0xc2, 0xc3) carry non-zero values that ShowKontrol's parser ignores;
  their meaning is unknown.

Not every mixer has all of these; model-specific layouts are not verified.

### Utility request, type `0x3a` (us → mixer), 40 bytes

| Offset | Content |
| --- | --- |
| `0x00`–`0x09` | Magic header |
| `0x0a` | `0x3a` |
| `0x0b`–`0x1e` | Sender name, up to 20 bytes |
| `0x1f` / `0x20` | `2` / `0` |
| `0x21` | Own device number, obfuscated (below) |
| `0x22`–`0x23` | Payload length `4`, big endian |
| `0x24` | `0` = read, `1` = write |
| `0x25` | Utility id 0–14 |
| `0x26` | Value (low 8 bits; `0` for reads) |
| `0x27` | `0` |

Obfuscated id: `((deviceNumber XOR (sum of packet bytes 0..63 except 0x21 +
mac[3] + mac[4] + mac[5])) & 0x7f) | 0x80`. This is not cryptography.

### Utility reply, type `0x3b` (mixer → us), at least 40 bytes

`0x24` result (0 = ok, otherwise the item is unavailable), `0x25` utility id,
`0x26` value. An unavailable item is never shown as a value of 0, and a write
is only reported as **confirmed** after a read-back reply carries the requested
value.

### Utility items

| Id | Key | Range |
| --- | --- | --- |
| 0 | balance | -128..127 |
| 1 | masterLimiter | 0/1 |
| 2 | masterAtt | 0..4 (0 to -12 dB) |
| 3 | masterMono | 0/1 |
| 4 | digitalLevel | 0..8 |
| 5 | digitalRate | 0..3 |
| 6 | digitalLimiter | 0/1 |
| 7 | digitalMono | 0/1 |
| 8 | boothAtt | 0..4 |
| 9 | boothMono | 0/1 |
| 10 | micLimiter | 0..6 |
| 11 | micToBooth | 0..7 |
| 12 | micToBoothLimiter | 0..6 |
| 13 | boothMuteOnMic | 0/1 |
| 14 | micToRecExclude | 0/1 |

Ranges are taken from another application's labels and are not validated per
mixer or firmware.

## Receiving the live state: the third-party identity (observed)

Beat Link's virtual CDJ announces itself as a CDJ (category byte 1, plain id).
Static analysis of ShowKontrol found that it receives `0x39` without any request
and without checking the sender, and that it announces itself differently: a
"third-party" participant with **category 5** and a device number of **54-57**
(`TCS-SHOWKONTROL`). The device simulator built into ShowKontrol sends `0x39` unicast to
`peer:50002` only to peers with category 5 or number >= 54, and the standard
mixer status `0x29` only to categories 1 (CDJ) and 7 (XDJ). **Hypothesis H1: a
real mixer behaves the same, so a CDJ-like identity never receives `0x39`.**
**Observed on the real rig:** with only Beat Link's CDJ identity (number 8, category
1) the DJM-V10 sent nothing to us for six minutes (no `0x39`, and no `0x29` either);
a few seconds after the third-party identity (number 54, category 5) came online
it streamed `0x39` at 4-6 Hz, answered `0x30`/`0x3a`, and each CDJ-3000 started
sending an additional status frame to that identity (see below).

Settings → Mixer → **Announce as third-party device (experimental)** therefore
makes Beat Link Monitor additionally announce, from a separate socket, like
ShowKontrol does (device number 54, the first free of 54-57):

1. a random back-off (0.3-3 s),
2. three claim requests (type `0x02`, category 5) 300 ms apart per candidate number,
3. a keep-alive (type `0x06`, category 5, flag byte `0x20`, device number
   obfuscated with the packet sum and the MAC) every 2 s on the announcement port,
4. a disconnect (type `0x08`) when switched off.

Utility requests are then signed with that identity. A version request (type
`0x30`, unobfuscated id) is sent to the mixer every 5 s until it answers with its
firmware version (`0x31`); the Mixer tab shows it. It is off by default because it
adds one more device to your network; the layouts are in
[PeerIdentityProtocol.kt](../backend/src/main/kotlin/com/beatlinkmonitor/mixer/PeerIdentityProtocol.kt).
Not sent: the stage-1/final claim frames (ShowKontrol does not send them either).
Observed to work on the real rig despite these differences: the identity coexists with
Beat Link's virtual CDJ on the same IP (the announcements come from a separate socket,
not from port 50000), and the claim sequence was accepted. Still unknown: whether a
mixer needs a valid obfuscated id in the keep-alive, and what the flag byte `0x20` means.

### What the devices do once a third-party identity exists

- The mixer sends `0x39` (248 bytes, header `0x1f=02 0x20=00`, payload length `0xd4`)
  unicast from its port 50002 to ours. Its device id at `0x21` is obfuscated (sender's own
  MAC, same scheme as above); answers to `0x3a` (`0x3b`, header `0x1f=02`, result byte
  0 = ok, 2 = unavailable) are signed the same way; the `0x31` version reply (44 bytes,
  header `0x1f=03 0x20=00`) uses a plain id.
- Each CDJ-3000 sends this identity an additional **status frame of 292 bytes** (`0x124`,
  header `0x1f=01 0x20=05`, payload length `0x100`, obfuscated id at `0x21`), next to the
  512-byte (`0x200`, subtype `0x20=08`, length field `0x045c`) frames that Beat Link receives.
  Beat Link would read the obfuscated id as a device number and create phantom players, so
  the socket wrapper removes frames with bit 7 set at `0x21` before Beat Link sees them.
- Decoding an obfuscated id needs the **sender's** MAC (taken from its keep-alive);
  this recovered the right device number for every frame in the recordings.
- Not part of the observations: any request that makes a device send these frames earlier;
  a category-1 identity with number 8 received none of them.

## Recording traffic for analysis

`POST /api/mixer/record/start` and `/stop` (and `GET /api/mixer/record`) record the traffic
of the Pro DJ Link status socket (what it receives and sends) into a pcap file under the
configuration directory (`recordings/`); the file opens in Wireshark. It only copies
traffic the application already handles and needs the mixer integration to be on. Ids in
the mixer's and the players' extra frames are obfuscated (see above); decode them with the
sender's MAC from its keep-alive.

## Limits and open questions

- **Remote control of physical faders/EQ knobs is not supported.** No network
  command for that was found; only the utility settings above are writable.
- Without the third-party identity a DJM-V10 did not stream `0x39` to us (observed).
  ShowKontrol never sends a request to start the stream; the identity is the trigger.
- It is unknown which device number and MAC address a mixer accepts in
  requests, and the Hz values behind `digitalRate`.
- The signedness of `balance` on the wire is unverified. The `0x39` layout was verified on
  one DJM-V10 for channel 1 and the global fields by moving every knob; the channel blocks
  of other channels, other mixer models and the unnamed bytes (`0xb4`/`0xb5`, `0xc2`,
  `0xc3` and eleven constant non-zero bytes) are not understood. All knobs read 0
  (fully down, kill) to 255 with a detent at exactly 128; the crossfader rests at 138.
- An unused channel with its knobs at the minimum reports 0 and therefore makes the matching
  player's waveform black while the EQ feature is on.
- Reading uses the same socket as beat-link by reflection on a private field;
  a beat-link update may break this (a unit test guards the field name).
