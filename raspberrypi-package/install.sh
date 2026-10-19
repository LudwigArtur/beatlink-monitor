#!/usr/bin/env bash
#
# BeatLink Monitor - Raspberry Pi Installer
#
# Installs the headless backend as a systemd service and configures a
# fullscreen, touch-operated Chromium kiosk showing the web UI - so the
# Raspberry Pi behaves like a dedicated, always-on monitoring appliance that
# starts automatically on every boot (requirements specification §18.3/§18.4: headless/
# Raspberry-Pi-Kiosk deployment).
#
# Usage:
#   sudo ./install.sh
#
# Safe to re-run (e.g. after copying a newer app/beatlink-monitor-backend.jar
# into this folder to update) - it overwrites its own files idempotently and
# never touches the application's own configuration/profile data.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/beatlinkmonitor"
SERVICE_NAME="beatlinkmonitor"
WEB_UI_PORT=17812

# --- Must run as root (systemd unit + /opt install dir) --------------------
if [ "$(id -u)" -ne 0 ]; then
  echo "Please run this installer with sudo:"
  echo "  sudo ./install.sh"
  exit 1
fi

# --- Determine the actual (non-root) desktop user --------------------------
# The installer is run via sudo, but the systemd service and the kiosk
# autostart entry must run as the real desktop user (not root) - both for
# correct file permissions in that user's own config/profile directory
# (~/.config/beatlink-monitor) and so the kiosk browser can actually open a
# window on that user's desktop session.
TARGET_USER="${SUDO_USER:-}"
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = "root" ]; then
  # Fall back to the first regular (non-system) user with a home directory,
  # covering the case of running this directly as root (e.g. via `sudo su`).
  TARGET_USER="$(awk -F: '$3 >= 1000 && $3 < 65534 && $1 != "nobody" {print $1; exit}' /etc/passwd)"
fi
if [ -z "$TARGET_USER" ]; then
  echo "Could not determine the desktop user to run BeatLink Monitor as."
  echo "Please run this script via 'sudo ./install.sh' from a normal user's login session, not as root directly."
  exit 1
fi
TARGET_HOME="$(getent passwd "$TARGET_USER" | cut -d: -f6)"
echo "==> Installing for user: $TARGET_USER (home: $TARGET_HOME)"

ARCH="$(uname -m)"
if [ "$ARCH" != "aarch64" ] && [ "$ARCH" != "arm64" ]; then
  echo "ERROR: BeatLink Monitor requires a 64-bit Raspberry Pi OS (aarch64/arm64)."
  echo "Detected architecture: $ARCH"
  echo "Please reflash with the 64-bit version of Raspberry Pi OS (Bookworm or newer, 'with desktop')."
  exit 1
fi

# --- 1. Java 21+ runtime -----------------------------------------------------
# Raspberry Pi OS (Bookworm, as of this writing) only ships OpenJDK 17 via
# apt - no Java 21 package is available there yet. We first check for any
# already-installed Java 21+ (so re-running this script, or a future OS
# release that *does* ship Java 21+, just reuses it), and only download our
# own private copy of Eclipse Temurin's official JRE 21 build otherwise -
# installed under $INSTALL_DIR so it can never conflict with the system's
# own Java installation or get removed by unrelated system updates.
JAVA_BIN=""
if command -v java >/dev/null 2>&1; then
  SYSTEM_JAVA_VERSION="$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || true)"
  if [ -n "$SYSTEM_JAVA_VERSION" ] && [ "$SYSTEM_JAVA_VERSION" -ge 21 ]; then
    JAVA_BIN="$(command -v java)"
    echo "==> Using existing system Java (version $SYSTEM_JAVA_VERSION): $JAVA_BIN"
  fi
fi

mkdir -p "$INSTALL_DIR"

if [ -z "$JAVA_BIN" ]; then
  if [ -x "$INSTALL_DIR/jre/bin/java" ]; then
    echo "==> Reusing previously installed private JRE at $INSTALL_DIR/jre"
    JAVA_BIN="$INSTALL_DIR/jre/bin/java"
  else
    echo "==> No suitable system Java found - downloading Eclipse Temurin JRE 21 (linux/aarch64)..."
    TMP_JRE_ARCHIVE="$(mktemp)"
    # Adoptium's "latest binary" endpoint always redirects to the current
    # newest Java 21 JRE build for this platform - no version number needs
    # to be hardcoded/maintained here.
    if ! curl -fL --retry 3 -o "$TMP_JRE_ARCHIVE" \
        "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse"; then
      echo "ERROR: Failed to download the Java runtime. Check the Pi's internet connection and try again."
      rm -f "$TMP_JRE_ARCHIVE"
      exit 1
    fi
    rm -rf "$INSTALL_DIR/jre"
    mkdir -p "$INSTALL_DIR/jre"
    tar xzf "$TMP_JRE_ARCHIVE" --strip-components=1 -C "$INSTALL_DIR/jre"
    rm -f "$TMP_JRE_ARCHIVE"
    JAVA_BIN="$INSTALL_DIR/jre/bin/java"
    echo "==> Installed private JRE 21 at $INSTALL_DIR/jre"
  fi
fi

# --- 2. Application files ----------------------------------------------------
echo "==> Copying application files to $INSTALL_DIR"
cp "$SCRIPT_DIR/app/beatlink-monitor-backend.jar" "$INSTALL_DIR/beatlink-monitor-backend.jar"
chown -R "$TARGET_USER":"$TARGET_USER" "$INSTALL_DIR"

# --- 3. systemd service -------------------------------------------------------
echo "==> Installing systemd service"
cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<EOF
[Unit]
Description=BeatLink Monitor (headless backend + web UI)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${TARGET_USER}
Group=${TARGET_USER}
Environment="HOME=${TARGET_HOME}"
WorkingDirectory=${INSTALL_DIR}
ExecStart=${JAVA_BIN} -jar ${INSTALL_DIR}/beatlink-monitor-backend.jar
Restart=on-failure
RestartSec=3
# Keep failures visible via 'journalctl -u beatlinkmonitor' rather than a
# separate log file location, matching standard systemd service conventions
# (the application's own file-based log, used for deep protocol debugging,
# still also gets written to ~/.config/beatlink-monitor/logs/ as usual).
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}.service"
systemctl restart "${SERVICE_NAME}.service"

echo "==> Waiting for the backend to come up..."
READY=false
for _ in $(seq 1 30); do
  if curl -fs "http://127.0.0.1:${WEB_UI_PORT}/health" >/dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 1
done
if [ "$READY" = true ]; then
  echo "==> Backend is running: http://127.0.0.1:${WEB_UI_PORT}/"
else
  echo "WARNING: Backend did not respond within 30s - check 'journalctl -u ${SERVICE_NAME} -e' for details."
fi

# --- 4. Disable screen blanking (kiosk displays must never go dark) ---------
if command -v raspi-config >/dev/null 2>&1; then
  echo "==> Disabling screen blanking (raspi-config)"
  raspi-config nonint do_blanking 1 || true
fi

# --- 5. Kiosk autostart (fullscreen Chromium, touch-operated) --------------
echo "==> Setting up kiosk autostart for user $TARGET_USER"
install -d -o "$TARGET_USER" -g "$TARGET_USER" "$TARGET_HOME/.config/autostart"
install -d -o "$TARGET_USER" -g "$TARGET_USER" "$INSTALL_DIR/kiosk"

# Local loading screen shown immediately on login, before the backend (a
# separately-started systemd service, which can still take a few seconds to
# boot its JVM) is actually ready. Chromium itself opens this *instead of*
# the real web UI right away (no shell-side wait beforehand) - that is what
# keeps the desktop from ever being visible on a normal boot: the previous
# approach waited (silently, on the bare desktop) for up to 60s *before*
# even launching Chromium, during which the desktop/wallpaper/panel was
# fully visible and usable. This page's own script polls the backend and
# hands off to the real UI *inside the same already-fullscreen Chromium
# window* the moment it becomes ready, so the desktop is covered within
# roughly one second of login (ordinary autostart-launch latency) rather
# than however long the backend takes to boot, and there is no visible
# flicker/second window when the handoff happens.
cat > "$INSTALL_DIR/kiosk/loading.html" <<'LOADING_EOF'
<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<title>BeatLink Monitor</title>
<style>
  html, body {
    margin: 0;
    width: 100%;
    height: 100%;
    background: #08090b;
    color: #eee;
    font-family: system-ui, sans-serif;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }
  .wrap { text-align: center; }
  .spinner {
    width: 56px;
    height: 56px;
    margin: 0 auto 24px;
    border-radius: 50%;
    border: 5px solid #24252b;
    border-top-color: #4fc3f7;
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .title { font-size: 20px; font-weight: 700; margin-bottom: 8px; }
  .status { font-size: 13px; color: #888; }
</style>
</head>
<body>
  <div class="wrap">
    <div class="spinner"></div>
    <div class="title">BeatLink Monitor</div>
    <div class="status" id="status">Starting...</div>
  </div>
  <script>
    // Polls the backend's health endpoint and hands off to the real web UI
    // (in this same window) the instant it responds - `location.replace`
    // rather than `location.href` so this loading page never ends up in
    // the kiosk's browser history (there is no "back" button to return
    // to it, since the kiosk has no visible browser chrome anyway, but
    // this also avoids it ever being reachable via Alt+Left on a keyboard).
    var target = 'http://127.0.0.1:17812/';
    var elapsedSeconds = 0;
    var statusEl = document.getElementById('status');
    function poll() {
      fetch(target + 'health', { cache: 'no-store' })
        .then(function (res) { if (res.ok) { location.replace(target); } else { scheduleNext(); } })
        .catch(scheduleNext);
    }
    function scheduleNext() {
      elapsedSeconds += 1;
      if (elapsedSeconds === 15) {
        statusEl.textContent = 'Starting... (this may take longer after an update)';
      }
      setTimeout(poll, 1000);
    }
    poll();
  </script>
</body>
</html>
LOADING_EOF
chown "$TARGET_USER":"$TARGET_USER" "$INSTALL_DIR/kiosk/loading.html"

cat > "$INSTALL_DIR/kiosk/start-kiosk.sh" <<KIOSK_EOF
#!/usr/bin/env bash
# Disables any screensaver/blanking for this session (belt-and-suspenders
# alongside raspi-config's system-wide setting, since some desktop sessions
# re-enable their own screen-saver independently), hides the mouse cursor
# (irrelevant on a touch-only kiosk), and immediately opens the local
# loading screen fullscreen with no browser chrome at all - like a
# dedicated appliance, not "a browser". The loading page itself (see
# loading.html above) waits for the backend and hands off to the real web
# UI in this same window once it is ready, so Chromium only ever needs to
# be launched once, right at session start - no waiting on the bare
# desktop beforehand.
set -u

# Best-effort screen-blanking disable for whichever session type is active;
# harmless (and silenced) if the relevant tool isn't present/applicable.
xset s off -dpms s noblank >/dev/null 2>&1 || true

if command -v unclutter >/dev/null 2>&1; then
  unclutter -idle 0.5 -root &
fi

BROWSER_BIN=""
for candidate in chromium-browser chromium; do
  if command -v "\$candidate" >/dev/null 2>&1; then
    BROWSER_BIN="\$candidate"
    break
  fi
done

if [ -z "\$BROWSER_BIN" ]; then
  echo "No Chromium browser found - please install it with: sudo apt install -y chromium-browser" >&2
  exit 1
fi

exec "\$BROWSER_BIN" \\
  --kiosk \\
  --app="file://${INSTALL_DIR}/kiosk/loading.html" \\
  --password-store=basic \\
  --noerrdialogs \\
  --disable-infobars \\
  --disable-session-crashed-bubble \\
  --disable-pinch \\
  --overscroll-history-navigation=0 \\
  --check-for-update-interval=31536000 \\
  --touch-events=enabled
KIOSK_EOF
chmod +x "$INSTALL_DIR/kiosk/start-kiosk.sh"
chown "$TARGET_USER":"$TARGET_USER" "$INSTALL_DIR/kiosk/start-kiosk.sh"

cat > "$TARGET_HOME/.config/autostart/beatlinkmonitor-kiosk.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=BeatLink Monitor Kiosk
Exec=${INSTALL_DIR}/kiosk/start-kiosk.sh
X-GNOME-Autostart-enabled=true
NoDisplay=false
EOF
chown "$TARGET_USER":"$TARGET_USER" "$TARGET_HOME/.config/autostart/beatlinkmonitor-kiosk.desktop"

# --- 6. Custom Plymouth boot splash (matches the kiosk's own loading
#        screen) ----------------------------------------------------------
# Replaces Raspberry Pi OS's default boot splash (the "pix" theme/raspberry
# logo) with one using the exact same dark background, spinner and
# wordmark as the kiosk's own in-browser loading.html above, so the display
# looks visually continuous through as much of the boot process as
# Raspberry Pi OS's standard boot architecture allows: from the very first
# splash frame (shown by Plymouth from early boot, well before any desktop
# session starts) through to the kiosk's own loading screen taking over
# once the desktop session/Chromium are up (requirements specification: kiosk must look
# like a dedicated appliance, not expose Linux boot/desktop internals).
#
# This Pi's boot configuration already has Plymouth's splash enabled
# (`quiet splash` in cmdline.txt, Raspberry Pi OS's default) - only the
# *theme* itself is swapped out here, nothing else about how/when Plymouth
# runs is touched. The remaining brief (~1-3s) gap between Plymouth handing
# off to the login/desktop session and this installer's kiosk Chromium
# actually appearing is inherent to Raspberry Pi OS's standard LightDM/
# labwc desktop-session startup sequence and is not something a theme or
# autostart entry alone can further close without deeper, far riskier
# changes to the system's display-manager/compositor startup - see the
# summary this script prints at the end for a full assessment.
if command -v plymouth-set-default-theme >/dev/null 2>&1; then
  echo "==> Installing custom Plymouth boot splash"
  PLYMOUTH_THEME_DIR="/usr/share/plymouth/themes/beatlinkmonitor"
  install -d "$PLYMOUTH_THEME_DIR"

  # Remember whichever theme was active before, so uninstall.sh can restore
  # it exactly rather than leaving the system permanently on this theme.
  PREVIOUS_PLYMOUTH_THEME="$(plymouth-set-default-theme 2>/dev/null || true)"
  if [ -n "$PREVIOUS_PLYMOUTH_THEME" ] && [ "$PREVIOUS_PLYMOUTH_THEME" != "beatlinkmonitor" ]; then
    echo "$PREVIOUS_PLYMOUTH_THEME" > "$INSTALL_DIR/kiosk/.previous-plymouth-theme"
  fi

  base64 -d > "$PLYMOUTH_THEME_DIR/spinner.png" <<'PNG_EOF'
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
PNG_EOF

  base64 -d > "$PLYMOUTH_THEME_DIR/title.png" <<'PNG_EOF'
iVBORw0KGgoAAAANSUhEUgAAAb4AAAA1CAYAAAA+jY5GAAANK0lEQVR42u2debSVVRXAfzzRxyAI2wEnyNQwwYUKTikiiXNO4JhjGiaILocMc4BWDiXL1JWQQqJGiorghBSSiBorHEDNAUuRxNAoH20CGWR6rz/Ood5i3W+697vf++69+7fW/eO9+91vOPt8Z5+9z977gGEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhmEYhpGMVtYERpao6onAcyGHXCIi4+2+DXsXrE+Vi9YlCG0l0D7BT5qANcAq4EvgU+Bj4B3geRFZaOJosRcwSpYDReQZa6mqk+sm5ojIYUWcvwPwGdAxxuEzROQ4k4pR0YqvSOuynf9sD+wOfLvZS/QucBswWUSaTDSBg81Y4NKQQ3YSkX9aSxkJOFRVe4vIWwl/d2FMpWfYWJAr6nJ0L72AScDTqtrORGMYmXJFwkG3FXC5NZthii8dTgEeNdEYRqacrarbJTj+aGAvazbDFF+Kyk9VTzXxGEZmtAEGl8tCNAxqdI0vKd8DLKCiyhCRaVg0cV4Zqqp3iMhGwt2cewAnWHPZu2AWHwUjAVs1/wDbAgcBDxDPlWIYRnZ0wy01RDEsx94iw8iXxSciCigwV1WXAdeGHN5OVbcRkeUkj3baETgKOBzoA2wHCFAPLAUagD8DM3GpFEuLuIYA3Tf77IqLWBWc66ge2ACswKVwLMKlb8wFnhORldYFKWfUWw/gXN8XuvqJ1zIvhz8AD4nIJ9ZSbO7CfCqkTdsDF2Uow27N3uX9vAzFj13L/HiyAJgNvCwib1qfqgkZDAb6A3v4TIEvgNtFZHTeXZ2PRyg+gG2A5QkapCfwI+AcYMuAw3b2n31x4dirVfVeYFRCBfjvmMdt4ZXhphSOI/3/V6nqJOAGEflXyDNdDoxOcF9LVDXs+7Yi8lUlJu3G/a2f+NwLDCxwTBf/ORgYrqo/E5GbM3ju73iFslXIYb8BBke5GlNiXcC99FfVfUTk/YDfnQ90KvD/tX6il1Z7HQAMBwb5d6gQO/pPj02WqqrOBe4ApsRJi2rJPpX0Xch6LMihDOr9eQt5HHb2SpBKDm75X5vEDa1W1Wu9FXdhiNIraFl6BTxfVQ/L8NnaAxf761pib3oD5oHeqh4Y4/B64Keqek8Gyj5K6d0DXJyR0gNY4mfohQhLUwj6blJKbbWFqt4MvA6cETLgBnEg8ATwrKp2rtY+Veb+mkcZ1ANTvUeirtRKZC2p+M6M+P4vcVyBqloHPOZnAqVYsDsAs1T19IzbYVvgSVXtY2qrZPoAL3pZJuEKVT2pjErvyQild4uIXNkChRuCrIfzVLVTgWcZAPQscHyjt4ZKbavWfnAbkcLYdBLwlqruUm19qsxKL48yAPgVcAyVmM6gqp1VtY+qjsO5JMN4KOZp7wDOSukWtwImqGqvjPtbO39di/AqjSFAhyJ/O6oM/f2kCKXXBFwjIiNbqL2eBj4P8EZclCCFYSquBGEag1ua0aK7AVNLLIiRqz6VAXmUwQnA90mx9nQ5Fd/TqtrU/ONdl/OAH0Tc5KtxfNmqejxwTcRhjwH9cOuFbXGLs2P9oBOkhCb7mU9cd+wjwFB/na7+Wq39uXbzghuHWwcJoidguYstx96qekiKSu9kYEqI0tuIW8+7m5YLp98A3Bfw9TDvTdn0PF/zM/gklmOS9hrkx4W06Y0rhVjxfSoDwySvMhhIDSSwvwIcHxWA4a2jWyPOdYmInCMis0VkhYh8JSLviMhQr6iC6A6cF3HuOV5R7SAi54vIWH+dz/y1NorIGhH5VESmi8gQXGDLOop3/xrx+BK3KL87Lrq2O/FccQNSVHqTQ5TeOuBsEXkwB23164AJ2R7A8TFSGD4QkVkltlcdECfAaNMktpOfxO7jB9SoYK0hKbjbWrRPZaD0KkEGAC8Bp+OCWeq9YTEAuDNuTAg5S2BvBK4UkTExjx/gZxJB/DZsSw8RGefX844KOOQ6XJRd0O8TB8KIyBxVneoFV4jDC/xmDDDGCtPG5j9APxF5r9n/FngLBuCyiJlpqQPIKV7pBQVYrQYGicgM8pFE3eCjiy+gsGvzd6raNsTVNDqF2zg2YO2wOdcUsI7nAzep6gzghZCo0jZe7jdWYp/KaCzIuwzARcD/fLP/feo/syrV4qsDfqmqD6tqF+L5fcO4M8Y5JoZ8901V3a0Mz7kg5LtdfH6gUTzXbTZAJRmkdy1R6Z0aofSWA8fkRenFaJdjVLU7Lm9NAp7n4ZQUX+gsP8wlLCKzcWv9pVwjl30qQ/Iug4kFlB7VUrKsDudiPEJVjxaRD4t0ITSIyLsxrvdRDKvygRgD3v7Acd7s7w7shAsQaJ8wtQI/wChGsekvYUFRHxKcvwZubbZYTvSTsSB5NwDHisjb5K901jxVfQ04pECwwDBconAhHhSRVSncQpQ7MI5L8T5vTQTFDuyvqp1FZFkF9aksybMMmoCbqIFanV2Baaq6X8iLFZasuL0PpimVvcNyXbx7aATw9RSfvTNGscwUkfUhA3yTrxjUJSS3khJ2FQliMRA1kcuD1VcoEGNogDJvwkUApkHU+/PHGMr7H6q6ENgzZFLdDVdppFL6VJbkWQavisiiSq7VuSVuUfJUon2ye+IWk4OSGbPoUNsFXL8j8DzwYMpKD1KsflGDvB/jmDWkEA5NMtd235wrPXAu2kLrQUEW7O9FZGFKiclh7/IaEfki5ukWFfM+V2CfSjuwJe8ymEslR3WKyAYRWSIiz4rIAEKCRzyXestqczqRXXJ50CBxlOmZiqz0sz7je1oDrKyAnQLW41JuSl0XTErUu5zElboqRgnEauhTaZN3GSyutnSG4bhoziC64HZmp0CUVRZsWWB2dCYpVhAwUmUt8aKHs6QX8EKhSig5ZFzMQfwjXEHmNFgeo7QfCcoAlnKtSulTaZN3GXxZVYpPRBqIrvjQo8Dv1iachaTJBUSHPo8A9gc6AnWbuXtvw6g1egMzvIs8z1bfElzSfRRj0iqv5vN1w97ltqq6Q4IqIWEsta5YkTJorMaNaOuKNI0/wUVRFmK+iOxTpvs9ImJ22FdE5qds6hv5ZyxuV5Ag5XYQMF1Vj835dlSjge9GzL4npHzNsHcZXML0lIh1qqjq/I3A362bmgxa3OLz+XpdizRzZxJeKmjnMtzv1sDWIYfMilB6mwbAUtgYo96okT1v4lIawgIdDsUlhLcjv7P/V/2zBDFBRFakfNkXI74fGuMcQwkPJHm7iDD6vJPmWFBTMqhrQaXXCvhFjHsIqjwwPeK5ri/yvtqr6nBVHRlQfaBoC9pve1Sq4lsZIxrWaBmlMRs4jfB1sn64or1tyLfVR5lTGJoTldB/pKpeHfJe9SW66P2MKuxyaY4FNSWDrHdnaK2qO/kKFy8RXQ9zA66odSFeAMKS1Iep6lUJ7m0vVb3Nm/yjKLwNifp7CuJbvphvofPvjqtzV+7IxVGqelCerYoqV37TcZVOGiOShZ/xYeR55HFcwn2hnLa/lknxRXlK7lLViaraV1U7qGq9qvZQ1VtwgTb1EUsQ91J7UcxJxoKakkHrMu/OUOo5XgkyjX3i6I0E79zbCrhbVc8CxuOKSi/GFVPtjMsn6YlL2u2P23cralBrVNU3cC6rQmwNzFTVEbhNPhtwrtzTgB+nlJwe1TkPwG0gSYH2P19EHslQluVca82z8pvsA1nGE16+aYqqDgpLkG6h+19bxP5zpVyv0XtYnow49Bz/Scp9IvJ5FXa11MaCWpNBnndgbwJGRrww03A7V4dxiB+APsCtF64HvvB/TwZ+GEfpNePRGO6Fx4DP/CznY29BplWR5bUIq9PIh/J4wPctIsqcTUqwBVY1t9dTwP1lOPVblFYYOc+kOhbUkgzyrPhuF5E5MY67GrfNfVbcj6vPVwzLgEklds6lwDRTLRUxmN8F3EL0XmMTAwo11BqXEb52n5RFwMkisrpK+1c5xoKakEFdTiOVfiIiN8R1kwBn4zakXZdBZ1uH25CzoQild5y3AEkh8X8lRiUMTiNjeCXOBCY03/i1Rttqg3+3bqX03K1pQJ8qdXGWbSyoFRnk6UVbD0wF9hORmxMKq8lvmdELV31idQn7bj2By2O6PuR6C7x7dDbxN9c9QETeSKlzLsBVj7G8pMrgKqLL850LjPfRzrWs/DaKyAjgYFzeWNLBd56fSJwsIloD7ZX6WFALMmjdQmWlVgIrgL/h1trmAc+VmuPhCwEPUdXrcZFzh+LSB3bErbF19Ap2lbfAPgEWAu8BfwLe9xZknGstBvqp6gCvKA/DFeBu463BJV7hTRaR18uRb6Wq3wDOwG2H0xtX4q1DjnfdqNXBvElVB/v+Nyjk0IuA9ao6JK3KKBXcZvOAM1S1G3A0bpPmfXFBaeL7+DJcZOMCPwl92f+u1tqqLGOBycAwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMMwDMP4P/8FWFVIDUOoP/sAAAAASUVORK5CYII=
PNG_EOF

  base64 -d > "$PLYMOUTH_THEME_DIR/status.png" <<'PNG_EOF'
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
PNG_EOF

  cat > "$PLYMOUTH_THEME_DIR/beatlinkmonitor.plymouth" <<'PLYMOUTH_DESC_EOF'
[Plymouth Theme]
Name=BeatLink Monitor
Description=BeatLink Monitor boot splash, matching the kiosk's own loading screen
ModuleName=script

[script]
ImageDir=/usr/share/plymouth/themes/beatlinkmonitor
ScriptFile=/usr/share/plymouth/themes/beatlinkmonitor/beatlinkmonitor.script
PLYMOUTH_DESC_EOF

  cat > "$PLYMOUTH_THEME_DIR/beatlinkmonitor.script" <<'PLYMOUTH_SCRIPT_EOF'
# BeatLink Monitor boot splash - shows the same dark background, spinner and
# wordmark as the kiosk's own in-browser loading screen (see
# raspberrypi-package/install.sh's loading.html), so the display looks
# visually continuous from the very first frame of the boot splash all the
# way through to the kiosk app taking over, with no jarring theme change in
# between.

Window.SetBackgroundTopColor(0.031373, 0.035294, 0.043137);
Window.SetBackgroundBottomColor(0.031373, 0.035294, 0.043137);

screen_width = Window.GetWidth();
screen_height = Window.GetHeight();

title_image = Image("title.png");
status_image = Image("status.png");
spinner_image = Image("spinner.png");

title_sprite = Sprite(title_image);
title_sprite.SetX(screen_width / 2 - title_image.GetWidth() / 2);
title_sprite.SetY(screen_height / 2 + 24);
title_sprite.SetZ(10);

status_sprite = Sprite(status_image);
status_sprite.SetX(screen_width / 2 - status_image.GetWidth() / 2);
status_sprite.SetY(screen_height / 2 + 84);
status_sprite.SetZ(10);

spinner_sprite = Sprite();
spinner_sprite.SetZ(10);

spinner_angle = 0;

fun refresh_callback() {
  spinner_angle += 0.12;
  rotated_image = spinner_image.Rotate(spinner_angle);
  spinner_sprite.SetImage(rotated_image);
  spinner_sprite.SetX(screen_width / 2 - rotated_image.GetWidth() / 2);
  spinner_sprite.SetY(screen_height / 2 - rotated_image.GetHeight() / 2 - 60);
}

Plymouth.SetRefreshFunction(refresh_callback);
PLYMOUTH_SCRIPT_EOF

  if plymouth-set-default-theme beatlinkmonitor --rebuild-initrd; then
    echo "==> Plymouth boot splash installed (takes effect on next reboot)"
  else
    echo "WARNING: Failed to set the custom Plymouth boot splash - the default Raspberry Pi OS splash will keep being used. This does not affect the kiosk itself." >&2
  fi
else
  echo "==> Plymouth not found - skipping custom boot splash (default Raspberry Pi OS boot screen will be used; the kiosk itself is unaffected)"
fi


echo ""
echo "============================================================"
echo " BeatLink Monitor installed successfully."
echo ""
echo " - Backend service: systemctl status ${SERVICE_NAME}"
echo " - Web UI (on this Pi):  http://127.0.0.1:${WEB_UI_PORT}/"
echo " - Kiosk display starts automatically on the next desktop login/reboot."
echo " - Boot splash: a matching dark loading screen now covers startup from"
echo "   early boot through to the kiosk app opening its own loading screen"
echo "   in the exact same style. A brief (~1-3s) glimpse of the plain"
echo "   desktop background may still be visible right as the desktop"
echo "   session itself starts, before the kiosk app launches - this is an"
echo "   inherent characteristic of Raspberry Pi OS's standard desktop"
echo "   startup sequence, not something this installer can fully close."
echo ""
echo " Reboot now to see the full kiosk experience:"
echo "   sudo reboot"
echo "============================================================"
