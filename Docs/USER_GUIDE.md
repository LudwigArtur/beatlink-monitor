# User guide

## 1. Safe first use

Read the [warnings](../DISCLAIMER.md). Start with disabled outputs. The application monitors up to six players; supported features depend
on the model, firmware, network and available analysis information.

## 2. Monitor and Live View

- **Monitor** shows player information, tempo, master status and waveforms.
- **Live View** is the dedicated fullscreen waveform view.
  Leaving fullscreen returns to the Monitor view.
- If the Live View waveforms twitch, raise **Settings / Display / Waveform smoothness**. The levels
  Smoother and Smoothest ignore more position jitter and keep the playhead steady while a pitch fader
  moves; the cost is that a real jump (seeking in the track) is caught a little later.
- **Settings / Display / Glide jumps** (on by default) shows real jumps (seeking, hot cue, loop exit)
  as a short fluid movement of 0.3–0.8 s instead of snapping. Switch it off to see jumps instantly.
- Configure display slots in **Settings**: fixed player number, current tempo
  master or disabled.
- The current page is kept in the address (for example `#settings`), so reloading the
  browser returns to the same page.
- Adjust scaling, background and profiles for different displays.
  Profiles can be saved, loaded, imported and exported.

Missing metadata or waveforms do not necessarily indicate a network failure.
Not all data is available without analysis information.

## 3. Real Pro DJ Link devices

1. Connect the computer by Ethernet to an authorized DJ network.
2. Select automatic adapter selection or a specific manual network adapter in
   **Settings / General** (Network card).
3. Use automatic virtual player numbering or choose an unused number.
   Do not force a number that is already occupied.
4. After network or waveform format changes, follow restart notices and
   restart the application.
5. Check connection status and errors in **Diagnostics**.

RGB is the default preference; Three-Band requires compatible hardware.
A format preference does not guarantee that a device can provide it.

## 4. Mixer

The **Mixer** tab shows the live state of a DJM mixer (EQ, faders, crossfader, FX), its firmware
version and its utility settings (balance, limiters, output attenuation, mic routing ...). The
channel EQ also dims the matching part of the waveform of the player on the same channel number.
For the DJM-V10 and DJM-A9 the live state is drawn as a stylized top view of the mixer: channel strips
with trim, EQ, filter/color, cue lamps, crossfader assignment, fader and level meter, plus master level
and meters, booth, headphones, isolator (V10), beat FX and the crossfader. Hover a control to see its raw
value; the drawing scales with the window and scrolls sideways on very narrow screens. Other mixer models
show a table of the raw values instead.
Reading needs the mixer integration and the third-party identity (both on by default, see
**Settings / Mixer & CDJs** and [MIXER_CONTROL.md](MIXER_CONTROL.md)). A setting is only changed when you
press **Write** and counts as confirmed only after the mixer reports the new value back.
Anyone who can reach the web interface can change mixer settings, so keep **Allow LAN Access** off
on untrusted networks.

## 5. CDJs

The CDJs tab shows, per player, the 42 CDJ settings and the deck state (BPM, pitch, sync, key sync, master tempo, vinyl mode, auto cue,
quantize ...) as the CDJ reports them. "Write" changes one setting and only reports success after the CDJ
confirmed the new value. Remote buttons (play, cue, hot cues, loops, beat jumps) act on live playback immediately and are therefore
locked: create the file `remote-pin.txt` with a PIN (at least 4 characters) in the data directory of the backend
on the server, then enter that PIN in the CDJs tab. The unlock applies to this one connection only; every device has to
enter the PIN again after every (re)connect or page reload. See
[CDJ control](CDJ_CONTROL.md).

## 6. grandMA2 / MA Telnet

Fresh installations have this output disabled and no credentials configured.
Saved settings from existing installations are preserved.

1. Configure an authorized target, port (default 30000), username and password.
2. Configure the output network adapter independently of the DJ adapter.
3. Adapt the login and BPM command templates to the actual console.
   Placeholders: `{USERNAME}`, `{PASSWORD}`, `{BPM}`.
4. Set appropriate rate and minimum BPM change limits.
5. Test in a non-production session before deliberately enabling the output.

The default `SpecialMaster 3.1 At {BPM}` is only a template, not a universally
correct show configuration. **MA Telnet** provides console-specific
configuration and interaction. Telnet is unencrypted.

## 7. Ableton Link

Install and start Carabiner separately; it is not part of this package.
The default target is `127.0.0.1:17000`. Configure the output in Settings,
choose a quantum and position source, and enable it only after testing.
Check that the intended Link peers are participating on the correct network.
A tempo bridge does not provide guaranteed audio/timecode synchronization.

## 8. Saving, stopping and updating

Configuration is stored in the user profile, not in the source directory.
Back up configuration and profiles before updates; these may contain passwords.
Stop the desktop app using **Quit** or by closing the native control window.
Closing the separate frontend browser tab does not stop the server.
Stop the headless process with Ctrl+C in the terminal, or the Pi service with
`sudo systemctl stop beatlinkmonitor`.
Check the target console and enabled outputs before every return to service.

Continue with [configuration](CONFIGURATION.md) and
[troubleshooting](TROUBLESHOOTING.md).
