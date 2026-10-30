# Towards replacing ShowKontrol

Long-term goal: Beat Link Monitor should cover what the commercial application
ShowKontrol is used for. This page records what is known, what exists today and
a proposed order of work. It is a planning document, not a promise.

## Basis and limits

- The protocol facts below come from public sources (Deep Symmetry's Pro DJ Link
  analysis, Beat Link) and from **static, interoperability-oriented analysis** of the
  installed ShowKontrol R27 binary: packet layouts, ports, sequences. No code,
  strings or assets of ShowKontrol are included in this repository; licensing,
  activation and online-account functions were not analyzed and are out of scope.
  The detailed notes are kept outside the repository.
- Nothing from the vendors' encrypted firmware images is used.
- **Nothing below has been verified against real hardware yet.** Anything that
  writes to a real device needs a hardware check (the app can record the status
  socket traffic, see [MIXER_CONTROL.md](MIXER_CONTROL.md)) before it is enabled by default.

## Status per capability

| Capability | ShowKontrol | Beat Link Monitor today |
| --- | --- | --- |
| Players: status, BPM, master, on-air, beats, position | yes | yes (beat-link) |
| Track metadata, beat grid, waveforms (blue / RGB / 3-band), cues | yes | yes (beat-link); phrase analysis (PSSI) not used |
| Mixer live state (faders, EQ, FX, crossfader) | yes (`0x39`) | works on a real DJM-V10 and DJM-A9 with the third-party identity, see [MIXER_CONTROL.md](MIXER_CONTROL.md) |
| Mixer utility settings read/write | yes (`0x3a`/`0x3b`) | read and write (read-back confirmed) verified on a real DJM-V10 and DJM-A9, see [MIXER_CONTROL.md](MIXER_CONTROL.md); on by default |
| Mixer firmware version | yes (`0x30`/`0x31`) | yes (probe while the integration is on) |
| Mixer audio levels | yes (`0x57`/`0x58`) | works on a real DJM-V10 and DJM-A9 (streams unasked, 512-byte frames), meters in the Mixer tab |
| CDJ settings read (42 items) / write | yes (`0x6b`/`0x6c`, UDP 50002) | read and write verified on CDJ-3000s, see [CDJ_CONTROL.md](CDJ_CONTROL.md) |
| CDJ deck state (BPM, pitch, sync, key sync, slip, quantize, ...) | yes (`0x68`/`0x69`) | yes, all offsets but one identified on CDJ-3000s |
| Remote buttons (play, cue, hot cues, loops, beat jump) | yes (`0x07`, UDP 50001) | yes; other devices need a PIN (also for all mixer/CDJ writes), every write is confirmed in a popup; slip tried on a CDJ-3000 |
| Outputs | 36 types: MA, OSC, MIDI, Art-Net, sACN, TCNet, ... | grandMA2 Telnet, Ableton Link |
| Other inputs | StagelinQ, TCNet, LTC/MTC/Art-Net timecode, PSN, DMX in | none |
| Show logic | node patch editor, cue lists, set lists, tracker | none |

## Protocol facts found (summary)

- **Joining the network:** ShowKontrol announces as a third-party participant
  (category 5, device number 54-57) with a claim sequence and a 2 s keep-alive. A real
  DJM-V10 and real CDJ-3000s sent their rich frames (mixer `0x39`, a second CDJ status
  form of 292 bytes) only after such an identity existed (observed, see
  [MIXER_CONTROL.md](MIXER_CONTROL.md)). ShowKontrol receives them without checking the
  sender and never requests the stream.
- **Device-number obfuscation:** requests that carry the sender's id obfuscate it
  with the packet sum and the last three MAC bytes; the receiver decodes it with the
  MAC it has registered for the sender's IP.
- **CDJ settings:** a `0x6b` request reads a 75-byte settings block (`0x6c` reply);
  a write sends one byte `0x80 | value` at an absolute offset. 26 of 42 items match
  what Deep Symmetry documents; 16 are not documented publicly (screen saver, auto
  standby, output attenuation, jacket display, gate cue, smart cue, beat jump
  beats, ...).
- **Remote buttons:** a connect / command / disconnect exchange with 6-byte entries
  (button code, state); codes for play/pause, cue, sync, master, quantize, slip,
  hot cues A-H set/delete, loops and beat jump are known.
- **Audio levels:** a `0x57` request (re-sent every 3 s) is answered with a
  `0x58` frame of 16-bit level words per channel (a DJM-V10 streams it unasked, 512 bytes).

## Proposed order

1. ~~Verify the existing mixer path on real mixers~~ Done on a DJM-V10 and a DJM-A9: live state, version, utility
   reads and a utility write, audio levels. **Still open:** other mixer models, the unnamed `0x39` bytes
   (payload `0x92`, `0xb4`, `0xb5`, `0xc2`, `0xc3`).
2. ~~Read-only extras~~ Done: CDJ settings, deck state and mixer audio levels. The CDJ's second
   status form (`0x124`) still arrives and is only filtered.
3. **Outputs:** pick the ones actually needed from ShowKontrol's list (OSC and MIDI
   are the cheapest and most generic; Art-Net/sACN next), driven by derived data keys
   like `deck.*`, `mixer.*`.
4. **Writes to devices:** mixer utility settings, CDJ settings and remote buttons are done
   (remote buttons behind a PIN); load track is not implemented.
5. **Show logic and extra inputs** (timecode, StagelinQ, cue lists): scope to be
   decided; these are the largest part of ShowKontrol.

Which features of ShowKontrol are really used decides steps 3 and 5.
