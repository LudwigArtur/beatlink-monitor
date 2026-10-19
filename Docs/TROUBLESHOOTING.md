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
- Test the simulator separately to distinguish rendering from network problems.

## No output / unexpected console commands

- Is the output deliberately enabled? Are target host, port and adapter correct?
- Test grandMA credentials and templates in a test session.
- Carabiner must be running separately and reachable.
- Check master status and connection information in Diagnostics.
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
