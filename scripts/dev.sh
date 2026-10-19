#!/usr/bin/env bash
# Runs backend and frontend together for local development.
# Requires: JDK 21, Node.js 20.19+ or 22.12+, and optionally Carabiner
# for Ableton Link (see ARCHITECTURE.md).
set -euo pipefail
cd "$(dirname "$0")/.."

(cd backend && ./gradlew runHeadless) &
BACKEND_PID=$!

(cd frontend && npm ci --silent && npm run dev) &
FRONTEND_PID=$!

trap 'kill $BACKEND_PID $FRONTEND_PID 2>/dev/null' EXIT
wait
