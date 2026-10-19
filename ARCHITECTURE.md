# Architecture

This document maps the implementation to
[`Docs/REQUIREMENTS.md`](Docs/REQUIREMENTS.md).

## Data flow (requirements specification §17.1, §20)

```
Pro DJ Link network            Simulator
        │                          │
        ▼                          ▼
  ProDjLinkAdapter ──────────────────┐
        │                            │
        └──────────► ApplicationState ◄──────────┘
                     (StateFlow + event bus)
                            │
        ┌───────────┬───────┴───────┬───────────────┐
        ▼           ▼               ▼               ▼
  WebSocket/API  GrandMaTelnetOutput AbletonLinkOutput DiagnosticsService
      │
      ▼
   Svelte UI
```

`ApplicationState` (`backend/.../state/ApplicationState.kt`) is the single
source of truth. Neither the UI, nor GrandMA2, nor Ableton Link ever query
`beat-link` or the simulator directly (coding guideline #1-#6) - both
sources feed the exact same `upsertPlayer`/`removePlayer`/`beatOccurred` API.

## Backend modules (`backend/src/main/kotlin/com/beatlinkmonitor/...`)

| Package | Responsibility |
|---|---|
| `model` | `PlayerState`, `MasterClockState`, `BpmMath` (high-precision BPM, §7/§8) |
| `prodjlink` | `ProDjLinkAdapter` - the only place touching `beat-link` directly; `NetworkInterfaceService` (§28) |
| `state` | `ApplicationState` (source of truth + event bus, §20/§21), `PlayerSlotResolver` (§5) |
| `waveform` | `WaveformCache` (§31), fallback chain (§9.3), simulator waveform generator |
| `simulator` | `SimulationService` - virtual players feeding the identical `ApplicationState` API (§16) |
| `outputs` | `OutputModule` interface (§14), `RateLimiter` (§13.5), `OutputManager` |
| `outputs.grandma` | `GrandMaTelnetOutput`, `CommandTemplateEngine` (§13) |
| `outputs.ableton` | `AbletonLinkOutput`, `CarabinerClient` (§12) |
| `api` | Ktor WebSocket + REST surface (§17.4) |
| `config` | `ConfigurationService`, versioned JSON config + UI profiles (§11, §26, §27) |
| `diagnostics` | `DiagnosticsService` (§25) |
| `app` | `AppContext` (DI wiring, coding guideline #7/#8); `Main.kt` - shared `bootstrapBackend()` + headless entry point; `DesktopApp.kt` - native control window and default-browser launcher |

### Threading (§19)

- `beat-link` callbacks run on their own threads and only call
  `ApplicationState` methods (backed by `MutableStateFlow`, safe for
  concurrent writers).
- `GrandMaTelnetOutput` and `AbletonLinkOutput` each own a dedicated
  `CoroutineScope(Dispatchers.IO)` - a stalled Telnet socket or missing
  Carabiner process can never block Pro DJ Link processing or the UI.
- The Pro DJ Link stack (`VirtualCdj` et al.) is started on its own thread
  from `Main.kt` because `beat-link` blocks for up to ~30s retrying device
  discovery when no network is present yet; the simulator and web UI must
  stay usable immediately regardless (§23, §43).
- The Ktor server pushes state to WebSocket clients via a `combine()` of the
  players/master `StateFlow`s, decoupled from both the network and output
  threads (§22).

### Network interface selection (§28)

`ApplicationConfig.networkInterfaceMode` (`AUTOMATIC`/`MANUAL`) and
`manualInterfaceName` actually change discovery behavior: in `MANUAL` mode,
`ProDjLinkAdapter.restrictToInterface` adds every *other* local interface's
addresses to `beat-link`'s `DeviceFinder` ignore list before starting
discovery, so Pro DJ Link traffic seen on an unrelated adapter (e.g. a Wi-Fi
uplink alongside a dedicated USB-Ethernet link, §28.2) is not picked up. It
tracks which addresses it added so switching back to `AUTOMATIC` (or to a
different interface) cleanly un-ignores them again. Saving a changed network
configuration via `POST /api/config/application` restarts the Pro DJ Link
adapter on a background thread without blocking the request or any other
subsystem (§28.3).

### Master clock (§8)

`MasterClockState` is derived *exclusively* from whichever player currently
reports `tempoMaster = true`. It is recomputed both on hand-over (a player's
`tempoMaster` flag flips) *and* whenever the current master's own update
changes (pitch rides, beat position) - so Ableton Link/GrandMA2 keep
following a pitch bend on an already-appointed master, not just hand-overs.

### GrandMA2 Telnet (§13)

`GrandMaTelnetOutput` runs its own reconnect loop keyed off a shared
`connected` status flag (not just raw `Socket.isConnected()`, which does not
reliably reflect a broken pipe) so that a dropped connection is retried
automatically without needing another BPM change to trigger it. Passwords are
always redacted before being logged (§29.2).

### Ableton Link (§12)

Rather than a hand-rolled JNI binding to the C++ Link library (out of scope
for this codebase), `AbletonLinkOutput` speaks Deep Symmetry's
[Carabiner](https://github.com/Deep-Symmetry/carabiner) plain-text TCP
protocol - the same mechanism `beat-link-trigger`/`beat-carabiner` use. A
local Carabiner process must be running; if it is not, the module reports
`disconnected` and logs a warning without affecting anything else in the
application.

Both **tempo and beat/bar phase** are synchronized, per §12.1's explicit
"not just tempo" requirement:

- `maybeSendTempo` sends `bpm <value>` whenever the master's effective BPM
  changes by more than a small epsilon, or immediately on a master hand-over.
- `maybeAlignPhase` sends `force-beat-at-time 0.0 <now> <quantum>` to align
  Link's bar boundary to the Pro DJ Link master's downbeat - immediately on
  hand-over, and periodically (at most once per ~8s, and only exactly on a
  downbeat) to correct drift, so Link's timeline is never yanked mid-bar and
  is not fighting the 50ms poll loop by re-sending every tick (§12.3: "Jitter
  minimieren").

> **Real bug found and fixed during manual verification against a mock
> Carabiner server:** `"%.4f".format(value)` is locale-sensitive in
> Kotlin/Java - under a German/European default locale it produces `"128,0000"`
> (comma decimal), which Carabiner's protocol rejects with `bad-bpm`. All
> Carabiner command formatting now goes through a small `fmt()` helper that
> forces `Locale.ROOT`, with a regression test
> (`AbletonLinkLocaleFormattingTest`) that temporarily switches the JVM's
> default locale to confirm it.

### Waveforms (§9, §31)

`WaveformCache` stores the actual cached `beat-link` `WaveformPreview`/
`WaveformDetail` objects (not raw bytes), keyed by `(trackId, player, slot)`.
Pixel decoding (`WaveformPixelDecoder`) reuses those objects' own
`segmentHeight`/`segmentColor` accessors - the same logic `beat-link`'s Swing
components use - rather than re-implementing Pioneer's byte layout by hand,
for **both** the classic monochrome and NXS2 color (RGB) formats. This is
validated in `WaveformPixelDecoderTest` against small, self-authored
synthetic byte buffers exercising the documented segment formats (no
copyrighted track data is bundled with this project). The mandatory fallback
chain (3-Band → RGB → Classic → None) is implemented in `WaveformFallback`
and unit-tested. 3-Band waveform data is not yet fetched from `beat-link`
(it requires a separate ANLZ tag lookup beyond `WaveformFinder`), so it
correctly falls back to RGB/Classic per the chain above rather
than inventing pixel data (coding guideline #13).

`SimulatedWaveformGenerator` renders proper 3-band RGB color data (bass =
blue, mids = green, highs = red/white - the same visual convention as
Pioneer's real NXS2 color waveforms and `beat-link-trigger`'s own test mode)
by sampling a single continuous, deterministic function of track-position,
rather than a flat single-color bar. Both the full-track overview
(`generate`/`generateColors`) and the zoomed-in scrolling "detail" window
(`generateDetailWindow`, used by the Live View below) sample from the exact
same function, so they are visually consistent for a given track/seed.
`WaveformPixelDecoder.decodeDetailWindow` is the real-hardware counterpart,
slicing a `WaveformDetail`'s half-frames (`Util.timeToHalfFrame`, 150/s) into
the same kind of time-windowed view.

### Live View (§9.4, dedicated fullscreen view)

A dedicated, chrome-less fullscreen view (`frontend/src/components/LiveView.svelte`,
reached via the **Live View** nav button, which also calls
`requestFullscreen()`) shows, for every visible player slot in the active
profile's configured order:

- a top row of compact cards with track name/artist/BPM/time and the
  full-track overview waveform, and
- one full-width row per track below with a large, continuously-scrolling
  **detail** waveform fetched from `GET /api/waveform/{device}/detail`.

The detail waveform's playhead is deliberately **not centered**: the
endpoint's default `beforeMs=5625`/`afterMs=9375` (totaling a 15s window)
places it at 3/8 of the width - "between the first and second quarter" -
so more upcoming track is visible than already-played track, closer to how
a CDJ's own detail display is read during a mix. Positions before the start
or after the end of the track render as silence (black) rather than
clamping/repeating.

## Frontend modules (`frontend/src/...`)

| Path | Responsibility |
|---|---|
| `websocket/connection.ts` | WebSocket client + Svelte stores (players, masterClock, outputs, diagnostics), auto-reconnect |
| `stores/api.ts` | REST client for config/profiles/simulator/outputs/waveforms |
| `stores/slotResolver.ts` | Client-side mirror of `PlayerSlotResolver` (§5) |
| `stores/layout.ts` | Active-profile store: switching, saving, drag-reorder, resize, scale/fullscreen/waveform/color setters (§10.4, §11) |
| `stores/profileDefaults.ts` | Default widget/slot/profile factories matching the backend defaults |
| `components/GlobalBar.svelte` | Master BPM/Player, network/Link/GrandMA2/simulator status; widget visibility + order driven by the active profile (§10.3, §10.4) |
| `components/PlayerGrid.svelte` | Widget-based, slot-driven player layout incl. drag-to-reorder (§10.1/§10.2/§10.4) |
| `components/LiveView.svelte`, `components/LiveDetailWaveform.svelte` | Dedicated fullscreen Live View with scrolling detail waveforms (§9.4) |
| `widgets/PlayerCard.svelte`, `widgets/WaveformView.svelte` | Per-player widgets incl. waveform + playback cursor + mouse resize handle (§9.4, §10.4) |
| `simulator/SimulatorPanel.svelte` | Full simulator controls (§16.4) |
| `settings/SettingsPanel.svelte` | General/Network/GrandMA2/Ableton Link/Layout/Profiles (§33) |
| `diagnostics/DiagnosticsView.svelte` | Diagnostics view (§25) |

> **Real bug found and fixed during manual verification:** `SimulatorPanel`
> computed each row's player via a plain function (`playerFor(device)`)
> called from inside `{#each}`/`{@const}`. Svelte 5's legacy-mode dependency
> tracking does not reliably re-invoke such a function on every prop update
> in this pattern, so newly-connected players, track loads, play/pause, and
> pitch changes only appeared once *something else* forced a re-render (e.g.
> switching to another tab and back) - exactly the symptom reported. Fixed
> the same way as the earlier `GlobalBar` widget-visibility bug: a top-level
> `$: playersByDevice = new Map(...)` reactive statement, read via simple
> `.get()` calls in the template, which Svelte's compiler *does* reliably
> track as a dependency of the `players` prop.

### Layout / display switching (§10.4, §11)

The active `UiProfile` lives in the `stores/layout.ts` Svelte store and drives
the *entire* display: visible global widgets and their order, player-slot
count/mapping/size/order, waveform height, global UI scale, background color
and fullscreen. Switching profiles (`applyProfileByName`, exposed via the
**Layout** dropdown in the Monitor toolbar and in Settings) re-applies all of
this immediately without a page reload - this is what drives "Umschalten der
Anzeige".

- **Drag & drop reordering**: player cards are `draggable` while **Edit
  Layout** is active; dropping one card onto another swaps their `order`
  values (`reorderSlots`).
- **Resize by mouse**: a corner handle on each card (visible only in edit
  mode) drives `PointerEvent`-based resizing, updating the slot's
  `width`/`height` live (`resizeSlot`).
- Changes are only written to the backend's `profiles/*.json` when the user
  presses **Save Layout** / **Save current layout** - browsing the display
  without saving never mutates persisted profiles.
- The backend exposes `GET /api/profiles/active` and
  `POST /api/profiles/active/{name}` so the frontend can resolve/set the
  `ApplicationConfig.activeProfile` pointer and immediately fetch the
  corresponding `UiProfile`.

> Reactivity note: Svelte's compiler only tracks *directly referenced*
> reactive identifiers in template expressions. `GlobalBar.svelte` originally
> called plain helper functions (`visible(id)`, `order(id)`) that closed over
> the `widgets` prop, which meant the widget list never actually reacted to
> profile switches or Settings edits. Fixed by deriving a `$: widgetMap` and
> calling functions that reference it directly in the template.

## Desktop packaging (requirements specification §18.1/§18.2, "an application like Showkontrol")

The desktop package provides **one application to launch**, not one combined
UI window. It starts the backend, displays a native control window and opens
the frontend in a **separate default-browser tab**:

```
DesktopApp (JavaFX)
    │
    ├─ init(): builds AppContext, calls bootstrapBackend() in-process
    │           (Pro DJ Link, simulator, outputs, Ktor server - all on
    │            their own threads/coroutines as before, §19)
    │
    └─ start(): shows the native control window; waits for /health,
                then opens http://127.0.0.1:<port>/ in the default browser.
                The control window offers Open in Browser and Quit.
```

- `com.beatlinkmonitor.app.bootstrapBackend(context)` (`Main.kt`) is the
  shared backend startup routine used by *both* entry points:
  - `com.beatlinkmonitor.app.MainKt` (`main()`) - headless, for
    server/Raspberry-Pi/Chromium-kiosk use (§18.3/§18.4).
  - `com.beatlinkmonitor.app.DesktopApp` (`DesktopAppKt.main()`) - the
    native control window/browser launcher, and the default `application.mainClass`.
- **Ktor serves the built frontend itself.** `Server.kt`'s routing adds
  `staticResources("/", "webapp")`, backed by `frontend/dist` copied into
  the backend's resources by the `copyFrontend` Gradle task (which in turn
  depends on `buildFrontend`/`npmInstall`). The backend serves the UI and API
  on **one port** with no separate frontend server. The browser is a separate
  process. JavaFX WebView is deliberately not used because its macOS rendering
  caused scrolling-waveform flicker in earlier versions.
- Window close / app quit triggers the same graceful shutdown sequence
  (`outputManager.stopAll()`, `simulationService.stop()`,
  `proDjLinkAdapter.stop()`, stopping Ktor) as the headless shutdown hook.
  Closing only the browser tab does not stop the backend. Quitting the native
  control window does not close the browser tab.

### macOS `.app` bundle via `jpackage`

The `jpackageMac` Gradle task produces a double-clickable
`BeatLinkMonitor.app` with its own bundled Java+JavaFX runtime (via
`jpackage --type app-image`), so end users need **no Java installation at
all** - exactly the "just works" experience of a commercial show-control
tool. See [README.md](README.md#running-the-desktop-application-macos)
for usage.

Two environment-specific issues were found and worked around while building
this:

1. **This sandbox's JVM processes have no outbound network access at all**
   (confirmed: even a bare `java.net.HttpURLConnection` to Maven Central
   fails with "Network is unreachable", while `curl` from the same shell
   succeeds instantly) - only Gradle's *already-cached* dependencies resolve.
   JavaFX's per-platform artifacts were therefore mirrored locally in
   `backend/local-maven-repo/` (declared ahead of `mavenCentral()`).
   That optional mirror is not included in the public source repository;
   normal builds resolve JavaFX from Maven Central. The patched Beat Link
   artifact in the same directory is required and is included with source.
2. **`codesign` (run internally by `jpackage`, since Apple-Silicon
   executables must be at least ad-hoc signed to launch) refuses to sign
   anything under this project's location** - an iCloud-Drive-synced
   `~/Desktop` folder, where macOS attaches extra `com.apple.FinderInfo`
   metadata to newly created files that `codesign` rejects outright. The
   `jpackageMac` task works around this by building into a plain temp
   directory and only copying the finished, already-signed `.app` back
   afterwards for convenience.
- The app icon (`backend/packaging/macos/AppIcon.icns`) was generated
  programmatically (a simple dark rounded-square mark with a waveform glyph,
  echoing the Monitor view's own styling) and is embedded via `jpackage
  --icon`.

## Known V1 limitations

- 3-Band waveform data is not fetched/decoded (RGB and Classic are); see
  above. This is a graceful, fallback-chain-compliant gap, not a broken
  feature.
- `LAST_MASTER` / `NEXT_ACTIVE_PLAYER` dynamic player-slot sources are not
  implemented (§5.2 only requires `AUTO` and `CURRENT_MASTER` for V1).
- Ableton Link requires an external Carabiner process; no JNI/Link-library
  embedding is included.
- Per-widget font size/color for player cards is modeled (`fontSize` field)
  but per-pixel absolute `x`/`y` widget positioning (as opposed to
  order-based flow layout) is not yet used by the UI.

These are documented, intentional scope boundaries for V1 per requirements specification
§42 ("Non-goals for version 1") and §48 (future extensibility), not
accidental gaps.
