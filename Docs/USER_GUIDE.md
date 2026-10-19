# User guide

## 1. Safe first use

Read the [warnings](../DISCLAIMER.md). Start with the simulator and disabled
outputs. The application monitors up to six players; supported features depend
on the model, firmware, network and available analysis information.

## 2. Monitor and Live View

- **Monitor** shows player information, tempo, master status and waveforms.
- **Live View** is the dedicated fullscreen waveform view.
  Leaving fullscreen returns to the Monitor view.
- Configure display slots in **Settings**: fixed player number, current tempo
  master or disabled.
- Adjust scaling, background and profiles for different displays.
  Profiles can be saved, loaded, imported and exported.

Missing metadata or waveforms do not necessarily indicate a network failure.
Not all data is available without analysis information.

## 3. Real Pro DJ Link devices

1. Connect the computer by Ethernet to an authorized DJ network.
2. Select automatic adapter selection or a specific manual network adapter in
   **Settings / Network**.
3. Use automatic virtual player numbering or choose an unused number.
   Do not force a number that is already occupied.
4. After network or waveform format changes, follow restart notices and
   restart the application.
5. Check connection status and errors in **Diagnostics**.

RGB is the default preference; Three-Band requires compatible hardware.
A format preference does not guarantee that a device can provide it.

## 4. Simulator

Click **Start Simulator** in **Simulator**; alternatively save Auto-Start in Settings and
restart. Connect players, load a demo track and try Play/Pause and master
changes. Two sample tracks include reused analysis data, but no audio.
Other simulated waveforms can be generated procedurally.
Simulation does not validate real network behavior or actual console commands.

## 5. grandMA2 / MA Telnet

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

## 6. Ableton Link

Install and start Carabiner separately; it is not part of this package.
The default target is `127.0.0.1:17000`. Configure the output in Settings,
choose a quantum and position source, and enable it only after testing.
Check that the intended Link peers are participating on the correct network.
A tempo bridge does not provide guaranteed audio/timecode synchronization.

## 7. Saving, stopping and updating

Configuration is stored in the user profile, not in the source directory.
Back up configuration and profiles before updates; these may contain passwords.
Stop the desktop app using **Quit** or by closing the native control window.
Closing the separate frontend browser tab does not stop the server.
Stop the headless process with Ctrl+C in the terminal, or the Pi service with
`sudo systemctl stop beatlinkmonitor`.
Check the target console and enabled outputs before every return to service.

Continue with [configuration](CONFIGURATION.md) and
[troubleshooting](TROUBLESHOOTING.md).
