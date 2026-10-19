# Pro DJ Link Monitor & Sync (BeatLink Monitor)

A cross-platform application that monitors a Pioneer/AlphaTheta Pro DJ Link
network (up to 6 players) in real time and bridges its tempo master to
Ableton Link and a grandMA2 console over Telnet. Implemented against
[`Docs/REQUIREMENTS.md`](Docs/REQUIREMENTS.md).

> **AI-generated code:** The application-specific code
> was generated with AI assistance. Third-party libraries and upstream data
> are not claimed as AI-generated. See [AI disclosure](AI_DISCLOSURE.md).
>
> **Use at your own risk.** This is experimental
> show-control software, not a safety system. Test offline before connecting
> outputs to a live setup. See [operational warnings](DISCLAIMER.md).

## Foundation, attribution and license

**Beat Link Trigger by Deep Symmetry is the conceptual and technical foundation
of this project.** Its sample waveform/beat-grid data is reused by the simulator.
The Kotlin backend directly uses Deep Symmetry's **Beat Link** library; this is
an independent application, not an official Beat Link Trigger release.

- [Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger)
  and [Beat Link](https://github.com/Deep-Symmetry/beat-link):
  Copyright 2016-2025 Deep Symmetry, LLC; EPL-2.0.
- Application-specific contributions: **EPL-2.0**, see [LICENSE](LICENSE).
- Third-party material retains its original licenses and ownership.
  See [NOTICE](NOTICE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
- The patched Beat Link dependency's source and exact change are supplied in
  [third_party/beat-link](third_party/beat-link/README.md).

No endorsement by Deep Symmetry, AlphaTheta/Pioneer DJ, Ableton, or MA Lighting
is claimed. Their names are used to describe compatibility.

## Documentation

| Document | Contents |
| --- | --- |
| [Quick start](Docs/QUICKSTART.md) | Installation and first simulator run |
| [User guide](Docs/USER_GUIDE.md) | Monitor, Live View, outputs and profiles |
| [Configuration](Docs/CONFIGURATION.md) | Files, network settings and credentials |
| [Troubleshooting](Docs/TROUBLESHOOTING.md) | Build, network, kiosk and output problems |
| [Development and releases](Docs/DEVELOPMENT.md) | Builds, checks and release checklist |
| [Raspberry Pi](raspberrypi-package/README.md) | Kiosk installation and removal |
| [Contributing](CONTRIBUTING.md) | Bug reports and contributions |
| [Safety and disclosure](SECURITY.md) | Trust boundaries and private reporting |

**Desktop launcher:** start one application without a terminal; it opens a
small native control window **and a separate browser tab** for the web UI. See
[Running the desktop application](#running-the-desktop-application-macos)
below.

## Project layout

```
backend/    Kotlin/Gradle application (beat-link, state, outputs, web API, desktop launcher)
frontend/   Svelte + TypeScript web UI (Vite)
config/     Example configuration files
scripts/    Convenience scripts (local dev)
Docs/       User/developer guides and requirements specification
third_party/ Corresponding source and patch for the vendored Beat Link library
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full design.

## Requirements

- JDK 21 (backend; a JRE alone is insufficient for building)
- Node.js 20.19+ on the 20.x line, or 22.12+ (frontend; Node 24 recommended)
- A Pro DJ Link network, *or* nothing at all - the built-in simulator lets
  you run and test everything without any CDJs/mixer attached.
- Optional: [Carabiner](https://github.com/Deep-Symmetry/carabiner) running
  locally, for the Ableton Link output.

## Running the desktop application (macOS)

This is the normal way to use the finished application: one double-clickable
`BeatLinkMonitor.app`, with its own bundled Java/JavaFX runtime (end users do
**not** need Java installed). The app starts the backend, shows a small native
control window and automatically opens the frontend in your default browser.
The frontend is **not** embedded in the native window. This avoids rendering
issues previously observed with JavaFX WebView.

### Build it

```bash
cd backend
./gradlew jpackageMac
```

This automatically builds the frontend, bundles it into the backend, and
produces the app bundle. The task prints its exact location, e.g.:

```
BeatLinkMonitor.app created at: /var/folders/.../beatlinkmonitor-jpackage/BeatLinkMonitor.app
```

> **Note for projects stored under an iCloud-Drive-synced Desktop folder:**
> macOS attaches extra metadata to files created there that Apple's code
> signing tool refuses to sign, so the app is deliberately built in a plain
> temp directory and only *copied back* into `backend/build/jpackage/` for
> convenience afterwards. Either location works - but if you intend to keep
> using the app, move/copy it outside of Desktop, e.g.:
>
> ```bash
> mkdir -p ~/Applications
> cp -R "$(./gradlew -q jpackageMac | grep -o '/.*\.app$')" ~/Applications/
> ```

### Run it

Double-click `BeatLinkMonitor.app` (e.g. in `~/Applications`), exactly like
any other Mac application. It will:

1. Start the backend (Pro DJ Link discovery, simulator, outputs, Web UI API) in-process.
2. Show a native control window with the local URL, **Open in Browser** and **Quit**.
3. Open the full web UI in your default browser once the embedded server is ready.
4. Stop the backend when you close the control window or quit the app.

Closing only the browser tab does **not** stop the backend. Use **Open in Browser**
to reopen it. Conversely, quitting the application does not automatically close
the browser tab; the frontend then loses its backend connection.

### Rebuilding after code changes

Just re-run `./gradlew jpackageMac` - it rebuilds the frontend, backend, and
app bundle from scratch each time.

## Running in development (separate backend + frontend, any OS)

```bash
./scripts/dev.sh
```

This starts:

- the backend on `http://127.0.0.1:17812` (WebSocket at `/ws`, REST under `/api`)
- the frontend dev server on `http://localhost:5173`

Or run them separately:

```bash
cd backend && ./gradlew runHeadless   # backend only, no native window
cd frontend && npm ci && npm run dev
```

### Building a runnable backend jar (headless, no GUI window)

For server/Raspberry-Pi/Chromium-kiosk deployments (requirements specification §18.3/§18.4):

```bash
cd backend && ./gradlew fatJarHeadless
java -jar build/libs/beatlink-monitor-backend-0.1.0-headless.jar
```

The frontend is served by this same jar on the configured port (default
`17812`) - open that URL in any browser (or point Chromium's kiosk mode at
it on a Raspberry Pi).

### Building the frontend standalone

```bash
cd frontend && npm run build   # outputs to frontend/dist
```

(`./gradlew jpackageMac`/`fatJar`/`fatJarHeadless` all do this automatically
as part of the backend build - you only need this directly for frontend-only
work with `npm run dev`.)

## Testing without any hardware

1. Start the application and use the **Start Simulator** control, or enable
   **Auto-Start Simulator** in **Settings** and restart.
   Simulator auto-start is disabled by default; see
   `config/application.example.json`.
2. Go to the **Simulator** tab.
3. Connect one or more virtual players, load a demo track, press **Play**,
   and use **Master** to test tempo-master hand-over between players.
4. Watch the **Monitor** tab for live player cards / waveforms / master BPM,
   the **Live View** tab for a dedicated fullscreen multi-track display with
   scrolling detail waveforms, and **Diagnostics** for overall system health.

## Configuration

Configuration is stored in the platform's application-support directory
(never in a hardcoded user path):

- macOS: `~/Library/Application Support/BeatLinkMonitor/`
- Windows: `%APPDATA%\BeatLinkMonitor\`
- Linux: `$XDG_CONFIG_HOME/beatlink-monitor/` (or `~/.config/beatlink-monitor/`)

Files: `application.json`, `outputs.json`, `profiles/*.json`, all versioned
via a `configVersion` field (see `config/*.example.json`).

## Tests

```bash
cd backend && ./gradlew test
```

Covers BPM/effective-BPM math, GrandMA2 command-template rendering, output
rate limiting, master hand-over/disconnect scenarios, player-slot mapping,
waveform fallback, Ableton Link locale-safe number formatting, and
configuration persistence/versioning - the scenarios required by
requirements specification §39.
