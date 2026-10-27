# CDJ control

BeatLink Monitor can read the per-player settings and deck flags of every CDJ and change a setting on request.
Checked on one rig: four CDJ-3000 (firmware 3.22) with a DJM-V10, one session. Other models and firmware
versions may differ. The frame layouts come from a static analysis of another Pro DJ Link application
(see [SHOWKONTROL_REPLACEMENT.md](SHOWKONTROL_REPLACEMENT.md)) and were confirmed against the real devices as noted below.

Needs the socket hook of the mixer integration (`cdjIntegrationEnabled`, see [CONFIGURATION.md](CONFIGURATION.md)).
Requests are signed with the identity of the app (the third-party identity 54 if announced, otherwise the virtual CDJ).

## What works

| Function | Frames (UDP 50002 unless noted) | Status |
| --- | --- | --- |
| Read all settings | `0x6b` request (`0x74` bytes, `0x24` = 0) → `0x6c` reply | verified, all 4 CDJs, every 5 s |
| Deck flags | `0x68` request (`0x24` bytes) → `0x69` reply (≥ `0x35`) | verified; names partly verified (below) |
| Write one setting | `0x6b` with `0x24` = 1, byte `0x80 \| value` at the item's offset | verified (Slip Blink on player 6, 1→0→1, confirmed by read-back) |
| Remote buttons | `0x07` to UDP **50001**: connect (a=1), command (a=0), disconnect (a=2) | implemented and unit-tested, **not tried on hardware** |

All values are big endian; the sender id of `0x68`/`0x6b` is plain, the one of `0x07` is obfuscated like in the
mixer utility frames (see [MIXER_CONTROL.md](MIXER_CONTROL.md)). The settings reply carries the 75 bytes `0x29..0x73`;
the value of each item is the low seven bits of its byte. There is no acknowledgement for a write: the app sends a
read request about 150 ms later and reports `CONFIRMED` only if the CDJ returns the requested value.

## Settings

42 items with their offsets, ranges and keys are listed in `CdjProtocol.SETTINGS`
([source](../backend/src/main/kotlin/com/beatlinkmonitor/cdj/CdjProtocol.kt)): screen saver, auto standby,
output attenuation, on-air display, LCD brightness, quantize, auto cue level, language, jacket display, jog ring
brightness/flashing, slip blink, track info, playlist view, HID setting, disc slot brightness, eject/load lock, sync,
file play mode, quantize beat, hot cue auto load/colour, 2nd screen brightness, logo illumination, needle lock, time mode,
jog mode vinyl, auto cue, master tempo, tempo range, phase meter, vinyl speed adjust, jog display mode, pad brightness,
jog LCD brightness, waveform divisions, gate cue, smart cue, rotary selector brightness, waveform/phase meter, jog display
artwork and beat jump beats. The ranges are those the reference application uses; an out-of-range value is rejected
before anything is sent. The real CDJ-3000s returned a value for every item.

## Deck flags

| Offset | Name | Verification |
| --- | --- | --- |
| `0x2c` | master tempo | equals the setting "Master Tempo" on all four CDJs |
| `0x30` | vinyl mode | equals "Jog Mode Vinyl" |
| `0x33` | auto cue | equals "Auto Cue" |
| `0x34` | quantize | equals "Quantize" |
| `0x27`, `0x2a`, `0x2d`, `0x31`, `0x32` | tempo reset, tempo ratio, key sync, vinyl speed, slip | names inferred from the field order only; the bytes around `0x28..0x2b` change with the players and may be the pitch value |

## Remote buttons

Off by default (`cdjRemoteEnabled`). A connect frame is sent before the first press and repeated every 5 s while buttons are
used; the session ends 60 s after the last press. Codes: 20 play/pause, 21 cue (down/up), 13 sync, 12 master, 14 master tempo,
10 quantize, 11 slip, 22 reverse, 23 slip reverse, 70–77 hot cue A–H, 80–87 delete hot cue A–H, 30/31/32/34/35/36 loop
functions, 40–55 beat loops, 90–103 beat jumps. Which codes a given model accepts is unknown. The frame carries the name
`CDJ-2000NXS2`, as the reference application does. A press acts on live playback immediately; with `allowLanAccess` enabled
anyone who can reach the web UI could use it, which is why it is separate from the settings switch.

## API

| Request | Purpose |
| --- | --- |
| `GET /api/cdj` | devices (settings by offset, flags) and link status |
| `POST /api/cdj/refresh` | read everything now |
| `POST /api/cdj/{player}/setting/{offset}` body `{"value": n}` | write one setting; answers `CONFIRMED`, `MISMATCH`, `NO_REPLY`, `INVALID`, `NO_DEVICE` or `CONTROL_DISABLED` |
| `POST /api/cdj/{player}/button/{code}?press=click\|down\|up` | remote button; answers `SENT` or an error |

Writes and button presses are refused when the request carries a foreign `Origin` header.
