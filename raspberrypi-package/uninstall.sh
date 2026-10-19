#!/usr/bin/env bash
#
# BeatLink Monitor - Raspberry Pi Uninstaller
#
# Removes the systemd service and kiosk autostart entry installed by
# install.sh. The application's own configuration/profile data
# (~/.config/beatlink-monitor) is deliberately left untouched unless
# --purge is given, in case of a later reinstall.
#
# Usage:
#   sudo ./uninstall.sh [--purge]

set -euo pipefail

SERVICE_NAME="beatlinkmonitor"
INSTALL_DIR="/opt/beatlinkmonitor"
PURGE=false
[ "${1:-}" = "--purge" ] && PURGE=true

if [ "$(id -u)" -ne 0 ]; then
  echo "Please run this with sudo:"
  echo "  sudo ./uninstall.sh"
  exit 1
fi

TARGET_USER="${SUDO_USER:-}"
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = "root" ]; then
  TARGET_USER="$(awk -F: '$3 >= 1000 && $3 < 65534 && $1 != "nobody" {print $1; exit}' /etc/passwd)"
fi
TARGET_HOME="$(getent passwd "$TARGET_USER" 2>/dev/null | cut -d: -f6 || true)"

echo "==> Stopping and disabling the systemd service"
systemctl stop "${SERVICE_NAME}.service" 2>/dev/null || true
systemctl disable "${SERVICE_NAME}.service" 2>/dev/null || true
rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload

if [ -n "$TARGET_HOME" ] && [ -f "$TARGET_HOME/.config/autostart/beatlinkmonitor-kiosk.desktop" ]; then
  echo "==> Removing kiosk autostart entry"
  rm -f "$TARGET_HOME/.config/autostart/beatlinkmonitor-kiosk.desktop"
fi

# --- Restore the previous Plymouth boot splash theme, if install.sh changed it ---
if command -v plymouth-set-default-theme >/dev/null 2>&1 && [ -f "$INSTALL_DIR/kiosk/.previous-plymouth-theme" ]; then
  PREVIOUS_PLYMOUTH_THEME="$(cat "$INSTALL_DIR/kiosk/.previous-plymouth-theme")"
  echo "==> Restoring previous Plymouth boot splash theme ($PREVIOUS_PLYMOUTH_THEME)"
  plymouth-set-default-theme "$PREVIOUS_PLYMOUTH_THEME" --rebuild-initrd || true
fi
rm -rf /usr/share/plymouth/themes/beatlinkmonitor

echo "==> Removing installed application files ($INSTALL_DIR)"
rm -rf "$INSTALL_DIR"

if [ "$PURGE" = true ] && [ -n "$TARGET_HOME" ]; then
  echo "==> --purge given: also removing all configuration/profile data"
  rm -rf "$TARGET_HOME/.config/beatlink-monitor"
fi

echo "==> Done. BeatLink Monitor has been uninstalled."
if [ "$PURGE" = false ]; then
  echo "    (Configuration/profiles were kept at ~/.config/beatlink-monitor - re-run with --purge to also remove them.)"
fi
