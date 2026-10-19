# BeatLink Monitor - Raspberry Pi touchscreen kiosk

> **Source repository:** The ready-built JAR and old local TAR archive are not
> distributed through Git. Before installation, build the headless JAR for
> Linux ARM64 and copy it to `app/beatlink-monitor-backend.jar`.
> See the [build guide](../Docs/DEVELOPMENT.md#raspberry-pi-package-from-source).
> This directory is not a complete installation package without that JAR.
>
> **AI-generated code. Use at your own risk.** Read the
> [disclosure](../AI_DISCLOSURE.md), [warnings](../DISCLAIMER.md) and
> [license obligations](../THIRD_PARTY_NOTICES.md).

This package installs BeatLink Monitor as a standalone, automatically starting
monitoring appliance on a Raspberry Pi. The web interface runs fullscreen on
a connected touchscreen and can be operated by touch without a keyboard or
mouse during normal operation.

## 1. Requirements

- **Raspberry Pi 4 or 5**, recommended because browser waveform rendering is
  CPU-intensive. A Pi 3 may be limited and complex waveforms may stutter.
- **64-bit Raspberry Pi OS, Bookworm or newer, with desktop**, not Lite:
  kiosk mode requires a graphical session. The 32-bit variant is unsupported.
- A **touchscreen**, such as the official 7-inch display or an HDMI display
  with a USB touch controller. Check its Raspberry Pi OS driver compatibility.
- A **wired Ethernet connection** to the CDJ/mixer Pro DJ Link network.
- Internet access during installation to download a suitable Java runtime
  if needed, and desktop packages. Normal application operation can be offline.

## 2. Prepare the Raspberry Pi

1. Write Raspberry Pi OS 64-bit with desktop to an SD card using the official
   [Raspberry Pi Imager](https://www.raspberrypi.com/software/).
   Configure a username/password and optionally SSH in the image settings.
2. Boot the Pi and log in to its desktop, or connect over SSH.
3. Connect the touchscreen and verify touch input, for example in the file manager.

## 3. Install

1. Build and populate `app/beatlink-monitor-backend.jar` as described above.
   Transfer this entire directory to the Pi, for example using a USB drive or:

   ```sh
   scp -r raspberrypi-package <USER>@<PI-IP>:~/
   ```

   If you received a separately prepared release archive:

   ```sh
   scp BeatLinkMonitor-RaspberryPi.tar.gz <USER>@<PI-IP>:~/
   ssh <USER>@<PI-IP>
   tar xzf BeatLinkMonitor-RaspberryPi.tar.gz
   cd raspberrypi-package
   ```

2. Enter the transferred directory, make the scripts executable and install:

   ```sh
   chmod +x install.sh uninstall.sh
   sudo ./install.sh
   ```

3. The script:
   - checks for Java 21 or newer and, if necessary, downloads an Eclipse
     Temurin 21 runtime into `/opt/beatlinkmonitor/jre`, without replacing
     system Java;
   - installs the application under `/opt/beatlinkmonitor`;
   - creates a **systemd service** that starts the application at boot under
     the regular user account;
   - disables screen blanking/power saving;
   - configures **Chromium kiosk autostart** without an address bar, using
     `--password-store=basic` to avoid a keyring prompt;
   - supplies a local dark loading screen with a spinner and application name;
     it polls the backend and switches to the web UI when ready, in the same
     fullscreen browser window;
   - configures a matching **Plymouth boot splash**, where supported, for a
     consistent appearance during startup. A brief view of the desktop during
     graphical-session startup may still occur because of LightDM/labwc ordering.

4. After a successful installation:

   ```sh
   sudo reboot
   ```

   The touchscreen web interface should start fullscreen after reboot.
   Verify this on the actual OS/display combination; desktop startup behavior
   can vary by OS version.

## 4. Initial configuration

Select the correct **Pro DJ Link network interface** under Settings / Network,
especially if the Pi has Wi-Fi plus a separate Ethernet adapter.

Two ways to configure:

- **On the touchscreen:** open Settings. Text entry, such as console credentials,
  may need an on-screen keyboard:

  ```sh
  sudo apt install -y squeekboard   # Wayland/labwc
  # Or for an X11 desktop:
  sudo apt install -y matchbox-keyboard
  ```

- **From another trusted device on the same network:** deliberately enable
  **Allow LAN Access** in Settings / General, restart if required and open
  `http://<PI-IP>:17812/`. This interface has no built-in authentication or TLS;
  do not use an untrusted network or expose it to the Internet.

For small touchscreens, adjust **Scale** under Settings / Display. Around
1.3-1.5x can make buttons easier to operate on a 7-inch display.
Check the resulting layout rather than assuming one value suits every panel.

## 5. Administration and troubleshooting

```sh
# Check the backend service
sudo systemctl status beatlinkmonitor

# Follow service logs
sudo journalctl -u beatlinkmonitor -f

# Restart the service
sudo systemctl restart beatlinkmonitor

# Application logs, under the service user's home directory
cat ~/.config/beatlink-monitor/logs/beatlink-monitor.log
```

**Loading screen never finishes:** The page waits for the backend health
endpoint. Check the service and logs first; a system update may increase startup
time. If the service is healthy, restart the kiosk from a graphical session:

```sh
/opt/beatlinkmonitor/kiosk/start-kiosk.sh
```

**Chromium missing:** Install the Chromium package available for your OS, such
as `chromium` or `chromium-browser`.

**No Pro DJ Link devices:** Check the selected Ethernet adapter and network
configuration. See [troubleshooting](../Docs/TROUBLESHOOTING.md).

## 6. Update

Back up user configuration and profiles first; they can contain passwords.
Replace `app/beatlink-monitor-backend.jar` with the newly built JAR and run:

```sh
sudo ./install.sh
```

The installer is designed to be rerun and preserves application configuration
and profiles under `~/.config/beatlink-monitor`. Inspect the actual result
after updates. Saved output settings remain active; new safe defaults do not
overwrite them.

## 7. Uninstall

```sh
sudo ./uninstall.sh
```

This removes the service, kiosk autostart and program files, while preserving
configuration/profiles for possible reinstallation. For complete removal of
the application settings as well:

```sh
sudo ./uninstall.sh --purge
```

Only use purge after backing up anything you intend to retain.

## 8. Technical background

- The backend runs **headless**, without the native desktop control window,
  using `MainKt` / `runHeadless` behavior as a systemd service under a regular
  user account, not root.
- The web UI is served on local port 17812 at `http://127.0.0.1:17812/`.
  Chromium provides the kiosk frontend.
- This setup uses a service and installation script rather than a native
  `.deb` package. It modifies desktop/boot configuration; review the scripts
  before installing and validate compatibility with your OS version.
- Source/binary license obligations still apply to redistributed packages.
  Keep required runtime legal files and corresponding source available.
