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
| Deck state | `0x68` request (`0x24` bytes) → `0x69` reply (≥ `0x35`) | verified, all offsets but `0x31` (below) |
| Write one setting | `0x6b` with `0x24` = 1, byte `0x80 \| value` at the item's offset | verified (Slip Blink on player 6, 1→0→1, confirmed by read-back) |
| Remote buttons | `0x07` to UDP **50001**: connect (a=1), command (a=0), disconnect (a=2) | verified with Slip on a CDJ-3000 (flag toggled 0→1→0); PIN-protected, see below |

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

## Deck state (type `0x69` reply)

54 bytes; checked on four CDJ-3000 (firmware 3.22) by switching each function on the player, via the remote buttons, via the
CDJ settings above, or by the user at the device, and watching which byte changed:

| Offset | Meaning | How it was checked |
| --- | --- | --- |
| `0x24` | always 1 | not understood |
| `0x27` | tempo reset | TEMPO RESET pressed twice on a CDJ: 0→1→0 (and a deck at 0 % by fader did not set it) |
| `0x28`–`0x29` | track BPM × 100 (u16) | equals the BPM of all four decks (126.00, 128.00, 133.00, 130.00) |
| `0x2a`–`0x2b` | pitch % × 100 (signed 16 bit) | equals the pitch of the status packets (+0.05, 0, +2.45, −0.75 %) |
| `0x2c` | master tempo | MASTER TEMPO off/on at the CDJ and Master Tempo via remote |
| `0x2d` | key sync | KEY SYNC on/off at the CDJ |
| `0x2e` | sync | SYNC via remote: 0→1→0 |
| `0x2f` | tempo master | equals the tempo-master flag of the one master deck; the master was not changed to test it |
| `0x30` | vinyl mode | setting "Jog Mode Vinyl" written 1→0→1 |
| `0x31` | unknown | never changed (no change on jog touch or the settings tried) |
| `0x32` | slip | SLIP via remote 0→1→0 |
| `0x33` | auto cue | setting "Auto Cue" written 0→1→0 |
| `0x34` | quantize | QUANTIZE via remote 0→1→0 (twice) |
| `0x25`, `0x26`, `0x35` | unknown | always 0 |

Note: turning SYNC off does not return a deck to its fader tempo; the deck kept the synced tempo until the pitch fader was moved.

## PIN and confirmation for writes

Everything that writes to a DJM or CDJ (mixer settings, CDJ settings, remote buttons) acts on live equipment. Other devices
on the network therefore have to enter a PIN first, and every write has to be confirmed in a popup:

1. **The PIN is created when LAN access is turned on.** In Settings, General, ticking "Allow LAN access" and saving first asks
   the device the server runs on for a PIN (4 to 64 characters, no spaces; the dialog can be cancelled, then LAN access stays
   off). This happens every time LAN access is turned on: turning it off discards the PIN. The backend refuses to turn LAN
   access on without a PIN, and only the local device may change this setting. Starting the app never asks for a PIN. If LAN
   access is already on but no PIN exists (an existing configuration, or `application.json` edited by hand), other devices
   simply cannot write until the local device creates one under Settings, Mixer & CDJs, Safety, "Change PIN" (or turns LAN access
   off and on again).
2. **The PIN is not stored as text.** Only a salted PBKDF2-SHA256 hash (120,000 rounds) is kept, in
   `security/remote-pin.hash` in the data directory (owner-only permissions), so it cannot be looked up. A forgotten PIN is
   replaced by turning LAN access off and on again, or under Settings, Mixer & CDJs, Safety, "Change PIN". A changed PIN
   locks all other devices again. A plain `remote-pin.txt` of an earlier version is ignored and deleted when the PIN changes.
3. **The device the server runs on** (the Mac running the app, or the Pi with its screen) is the "local" device. It needs no PIN
   and is the only one that can create or change the PIN and the LAN setting. A request counts as local if it comes from a
   loopback or one of the machine's own addresses, has no proxy headers (`X-Forwarded-*`, `Forwarded`, `X-Real-IP`), names
   `localhost` or an IP address in the `Host` header and is not cross-origin. Opening the app by the machine's host name
   instead of an IP address therefore counts as a remote device.
4. **Every other device** has to enter the PIN, in the CDJs or Mixer tab, before it can write anything. The unlock belongs to
   that browser's WebSocket connection (identified by a random id the server hands out at connect): closing the page,
   reloading it or losing the connection locks it again, so **every device has to enter the PIN again after every
   connect**. "Lock again" locks it at once. The backend enforces this for all three kinds of writes (answer 403 otherwise);
   reading is not restricted.
5. Wrong PINs are limited: 5 failures from one address lock that address for 60 s, 15 failures from anywhere lock all attempts
   for 60 s. The PIN is compared in constant time.
6. **Confirmation popup.** In the web UI every settings write and every button press asks for confirmation first (Cancel is
   focused, Escape cancels). Under Settings, Mixer & CDJs, Safety, "Confirm every write and button press" can be switched off
   for the current session only: it is not saved and is on again after reloading the page. While confirmation is on, the CUE
   button is a single press; the hold behaviour (down on press, up on release) is available when it is off. The confirmation is
   a feature of the web UI, not of the API.

A connect frame is sent before the first press and repeated every 5 s while buttons are used; the session ends 60 s after the
last press. Codes: 20 play/pause, 21 cue (down/up), 13 sync, 12 master, 14 master tempo, 10 quantize, 11 slip, 22 reverse,
23 slip reverse, 70–77 hot cue A–H, 80–87 delete hot cue A–H, 30/31/32/34/35/36 loop functions, 40–55 beat loops, 90–103 beat
jumps. Which codes a given model accepts is unknown; slip (11) was tried on a CDJ-3000 and toggled the slip flag. The frame
carries the name `CDJ-2000NXS2`, as the reference application does. This protects against other devices on the network, it
is not a substitute for keeping the web UI off untrusted networks (`allowLanAccess`). Other application settings (outputs,
profiles, integration options) are not covered by the PIN.

## API

| Request | Purpose |
| --- | --- |
| `GET /api/cdj` | devices (settings by offset, flags) and link status |
| `POST /api/cdj/refresh` | read everything now |
| `POST /api/cdj/{player}/setting/{offset}` body `{"value": n}` | write one setting; needs an unlocked connection or the local device (otherwise 403); answers `CONFIRMED`, `MISMATCH`, `NO_REPLY`, `INVALID`, `NO_DEVICE` or `CONTROL_DISABLED` |
| `GET /api/cdj/remote` | PIN set? unlocked? local device? LAN access on? (header `X-Connection-Id`: the id from the `hello` WebSocket message) |
| `POST /api/cdj/remote/unlock` body `{"pin": "..."}` | unlock this connection (`UNLOCKED`, `WRONG_PIN`, `NO_PIN`, `LOCKED_OUT`, `UNKNOWN_CONNECTION`) |
| `POST /api/cdj/remote/pin` body `{"pin": "..."}` | create or change the PIN, local device only (otherwise 403); `SAVED`, `INVALID` (400) or `FAILED` (500) |
| `POST /api/cdj/remote/lock` | lock this connection again |
| `POST /api/cdj/{player}/button/{code}?press=click\|down\|up` | remote button; needs an unlocked connection or the local device (otherwise 403); answers `SENT` or an error |
| `POST /api/mixer/utility/{id}` | mixer write, same rule |
| `POST /api/config/application` | turning `allowLanAccess` on needs a PIN (409) and the local device (403) |

Writes and button presses are refused when the request carries a foreign `Origin` header.
