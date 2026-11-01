# Troubleshooting

## Build does not start

- Check **both** `java -version` and `javac -version`. A JRE alone is
  insufficient; point `JAVA_HOME` at JDK 21.
- On Windows, replace `./gradlew` with `gradlew.bat`.
- Wrapper/Maven downloads require Internet access. Check proxy/firewall
  settings; do not disable TLS verification.
- `npm ci` needs the included lockfile and a compatible Node version.
  Update Node for engine errors rather than blindly upgrading packages.
- The local Beat Link artifact `8.0.0-blm1` must be included in the clone.
  It is not available on Maven Central.

## Blank browser / no connection

1. Check backend output and whether its port is already occupied.
2. Open `http://127.0.0.1:17812/api/diagnostics`.
3. In development, start the backend and frontend separately;
   the frontend connects directly to backend port 17812.
4. Use `fatJarHeadless` for a headless build; a plain standard JAR is not a
   standalone package containing all dependencies.
5. Access from another computer requires deliberate LAN access configuration.
   Do not expose the server to the Internet as a workaround.

## No players or waveforms

- Check the Ethernet adapter, IP configuration and VLAN.
- Pro DJ Link discovery is not automatically forwarded through routers.
- Check the host firewall and other running Pro DJ Link applications;
  do not indiscriminately open required UDP ports globally.
- Do not reuse an occupied virtual player number.
- Check track analysis, media and device capabilities. Missing waveforms
  should not be confused with actual precise position data.
- Restart after changing the adapter or waveform format.

### Players drop out, tracks and waveforms stay "loading"

A banner below the top bar (and the **Pro DJ Link network** card in Diagnostics) shows what the application sees:

- **The network address changed.** Pro DJ Link stays bound to the address it started with. When the adapter gets a different
  address (a link-local `169.254.x.x` address is picked again whenever the link comes back, a DHCP lease changes, an adapter is
  replugged) the players can no longer be reached. Quit and reopen the application; it is not restarted automatically because
  restarting Pro DJ Link inside a running application once disturbed a real player's device-number claim. If the address keeps
  changing, fix the cause: cable, adapter or dock, switch ports, a second adapter or Wi-Fi in the same address range.
- **The connection to the players is unstable.** Devices vanished repeatedly within 10 minutes (the application treats a player as
  gone when it hears nothing from it for 10 s). Typical causes are a bad cable, adapter, hub or switch.
- **No track information for player N.** The player reports a loaded track but title and waveform did not arrive. When the
  banner says the player's database port does not answer, the TCP connection to the player fails while its UDP broadcasts still
  arrive: this is the network, not the track. The metadata subsystems are restarted automatically after 90 s, and the wait
  between restarts doubles up to 30 minutes while that does not help.

The log contains a `Pro DJ Link health` line every 5 minutes while something is wrong, and one `Network address changed` line.
Beat-Link's own repeated messages about loading tracks are only logged as errors by default; set the environment variable
`BEATLINK_LIBRARY_LOG_LEVEL=INFO` (or `DEBUG`) before starting the application to get them back.

## No output / unexpected console commands

- Is the output deliberately enabled? Are target host, port and adapter correct?
- Test grandMA credentials and templates in a test session.
- Carabiner must be running separately and reachable.
- Check master status and connection information in Diagnostics.
- When the console is unreachable, the connection is retried after 3 s, then 6, 12, 24 and every 30 s, and the log says so once
  (with a count of the repeats) instead of for every attempt. "Reconnect" in the MA Telnet tab connects again at once.
  A console that hangs up or disappears (cable, reboot) is noticed within about 25 s on macOS and Linux and reconnected.
- "Host is down", "Network is unreachable" or "No route to host" in the log come from the operating system: the Mac has no
  route or cannot resolve the console's address (ARP). Check cable, adapter, the address of the Mac in the console's network
  and which adapter is selected for the output.
- Disable the output immediately and use your fallback if behavior is unexpected.
  Do not experiment with templates during a show.

## Raspberry Pi

```sh
sudo systemctl status beatlinkmonitor
sudo journalctl -u beatlinkmonitor -n 100 --no-pager
```

If the loading screen persists, check the service first, then Chromium and
desktop autostart. Details: [Pi guide](../raspberrypi-package/README.md).

## Useful bug reports

Include version/commit, OS, JDK/Node versions, hardware/firmware, reproduction
steps and relevant sanitized log excerpts. Do not publish passwords, private
configuration, music files or unreviewed packet captures.
