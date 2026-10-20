#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
OUTPUT="${1:-$ROOT_DIR/BeatLinkMonitor-RaspberryPi.tar.gz}"
if [[ "$OUTPUT" != /* ]]; then
  OUTPUT="$ROOT_DIR/$OUTPUT"
fi
OUTPUT_DIR="$(dirname "$OUTPUT")"
mkdir -p "$OUTPUT_DIR"
TEMP_DIR="$(mktemp -d)"
TEMP_ARCHIVE="$OUTPUT.tmp.$$"
trap 'rm -rf "$TEMP_DIR"; rm -f "$TEMP_ARCHIVE"' EXIT

(
  cd "$BACKEND_DIR"
  ./gradlew --no-daemon -PjavafxClassifier=linux-aarch64 fatJarHeadless
)

BUILT_JAR="$BACKEND_DIR/build/libs/beatlink-monitor-backend-0.1.0-headless.jar"
mkdir -p "$ROOT_DIR/raspberrypi-package/app"
cp "$BUILT_JAR" "$ROOT_DIR/raspberrypi-package/app/beatlink-monitor-backend.jar"

PACKAGE_DIR="$TEMP_DIR/raspberrypi-package"
mkdir -p "$PACKAGE_DIR/app" "$PACKAGE_DIR/licenses" "$PACKAGE_DIR/third_party/beat-link"
cp -R "$ROOT_DIR/raspberrypi-package/." "$PACKAGE_DIR/"
cp "$ROOT_DIR/LICENSE" "$ROOT_DIR/NOTICE.md" "$ROOT_DIR/THIRD_PARTY_NOTICES.md" \
  "$ROOT_DIR/AI_DISCLOSURE.md" "$ROOT_DIR/DISCLAIMER.md" "$PACKAGE_DIR/"
cp "$ROOT_DIR/licenses/"*.txt "$PACKAGE_DIR/licenses/"
cp "$ROOT_DIR/third_party/beat-link/README.md" \
  "$ROOT_DIR/third_party/beat-link/timefinder-null-guard.patch" \
  "$ROOT_DIR/third_party/beat-link/beat-link-8.0.0-source.tar.gz" \
  "$PACKAGE_DIR/third_party/beat-link/"
cp "$BACKEND_DIR/local-maven-repo/org/deepsymmetry/beat-link/8.0.0-blm1/beat-link-8.0.0-blm1.pom" \
  "$PACKAGE_DIR/third_party/beat-link/"

tar -czf "$TEMP_ARCHIVE" -C "$TEMP_DIR" raspberrypi-package
mv -f "$TEMP_ARCHIVE" "$OUTPUT"
printf 'Raspberry Pi package created: %s\n' "$OUTPUT"
