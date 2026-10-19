# Requirements Specification – Pro DJ Link Monitor & Sync

> This is a requirements and design specification, not a guarantee that every feature is implemented. Implementation status and current startup behavior are documented in the root README.

## 1. Document Information

**Project name:** Pro DJ Link Monitor & Sync  
**Working title:** BeatLink Monitor  
**Document type:** Requirements specification  
**Target platforms:** macOS, Windows, Raspberry Pi / Linux  
**Technical basis:** Java/Kotlin, Deep Symmetry `beat-link`, local web UI with TypeScript  
**Primary target audience:** Lighting operators, DJs, technicians, and show operators

---

## 2. Project Goal

Cross-platform software is to be developed that monitors a Pioneer/AlphaTheta Pro DJ Link network and visualizes the relevant states of up to six players in real time.

In particular, the software is to provide the following information:

- Waveforms
- Track BPM
- Effective BPM, taking the pitch value into account
- Pitch as a percentage
- On-Air status
- Tempo Master status
- Player/device status
- Track ID
- Track title
- Artist
- Playback position
- Beat and bar information

Additionally, the software shall:

1. detect the BPM of the current Pro DJ Link Tempo Master,
2. process this BPM with high internal precision,
3. synchronize Ableton Link to tempo, beat phase, and bar phase,
4. output the Master BPM via GrandMA2 Telnet using freely definable commands,
5. provide a freely customizable user interface,
6. be fully testable in simulation mode without connected DJ hardware.

The software is not intended to fork Beat Link Trigger as a complete application; instead, it should build as directly as possible on the underlying `beat-link` library.

---

# 3. Supported Hardware

## 3.1 Players

At least the following players must be supported:

- Pioneer CDJ-2000NXS2
- Pioneer CDJ-3000

In each case, the latest firmware should generally be assumed.

The software must be able to process Pro DJ Link devices with player numbers 1 to 6.

## 3.2 Mixers

The application should be usable with the following mixers:

- Pioneer DJM-900NXS2
- AlphaTheta / Pioneer DJM-A9
- Pioneer DJM-V10
- Pioneer DJM-V10-LF

Support for On-Air status and channel assignments must be implemented to the extent that it is available through `beat-link` or the Pro DJ Link protocol.

Hardware-dependent differences must be handled cleanly.

If a particular feature is unavailable for a device, this must not cause the entire application to fail.

---

# 4. Functional Requirements

## 4.1 Device Discovery

### MUST

The software must:

- automatically detect existing Pro DJ Link devices,
- detect newly connected devices at runtime,
- distinguish disconnected players by their device/player number,
- detect loss of connection,
- handle reconnection automatically.

### SHOULD

The device view should display at least the following information:

- Device name
- Player number
- Device type
- Connection status
- IP address, if available
- Firmware/device information, if provided by the protocol

---

# 5. Player Assignment

The user interface must not be permanently tied to physical player numbers.

## 5.1 Manual Assignment

A display slot must be manually configurable to:

- Player 1
- Player 2
- Player 3
- Player 4
- Player 5
- Player 6

## 5.2 Automatic Assignment

At least one automatic mode must also be available.

Possible dynamic sources:

- Auto
- Current Master
- On-Air Player 1
- On-Air Player 2
- Last Master
- Next Active Player

For version 1, at least the following dynamic sources are mandatory:

- Auto
- Current Master

Additional dynamic sources may be added later.

---

# 6. Player Data Model

A central state must be maintained internally for each player.

Recommended data model:

```text
PlayerState
├── deviceNumber
├── deviceName
├── deviceType
├── connected
├── playing
├── paused
├── onAir
├── tempoMaster
├── rekordboxTrackId
├── title
├── artist
├── originalBpm
├── effectiveBpm
├── pitchPercent
├── beatNumber
├── beatWithinBar
├── playbackPosition
├── trackDuration
├── waveformPreview
├── waveformDetail
├── waveformType
├── beatGrid
├── lastUpdateTimestamp
└── dataAvailability
```

The data model must be able to explicitly represent missing or unsupported fields.

Missing information must not be replaced with invented or estimated values.

---

# 7. BPM Processing

## 7.1 Original BPM

The track BPM must be taken from metadata provided by Pro DJ Link or `beat-link`.

## 7.2 Effective BPM

The effective BPM must take the currently set pitch into account.

The highest available precision should be used internally.

### Example

```text
Track BPM:       140.000000
Pitch:           +0.730 %
Effective BPM:   141.022...
```

The internal calculation must not be rounded prematurely.

## 7.3 Display

The standard display should use:

```text
141.02 BPM
```

The number of decimal places should be configurable later.

Default:

```text
2 decimal places
```

---

# 8. Master BPM

The Master BPM may only be derived from the current Pro DJ Link Tempo Master.

In version 1, no alternative master may be estimated based on:

- On-Air
- Play
- Volume
- Most recently started deck
- Mixer states

Recommended state:

```text
MasterClockState
├── masterPlayerNumber
├── trackBpm
├── effectiveBpm
├── beatNumber
├── beatWithinBar
├── phase
├── lastUpdateTimestamp
└── valid
```

If there is no valid Pro DJ Link master:

```text
valid = false
```

Output modules must detect this state and respond accordingly.

---

# 9. Waveforms

## 9.1 Display

The following displays must be supported:

- Track overview waveform
- Scrolling detail waveform

The user interface must allow:

- Overview only
- Detail only
- Overview + detail

## 9.2 Waveform Modes

The software should support the following modes:

- RGB
- 3-band
- Classic / monochrome

## 9.3 Fallback

If the requested waveform type is unavailable:

```text
3-Band
  ↓
RGB
  ↓
Classic
  ↓
No Waveform
```

The user must be able to tell which mode is currently in use.

## 9.4 Playback Cursor

The current playback position must be visible within the detail waveform.

Optionally, the position within the overview waveform should also be marked.

---

# 10. User Interface

## 10.1 Basic Principle

The user interface must be built in a modular, widget-based manner.

The UI must not be hardwired for a specific number of players.

It must support at least 1 to 6 visible player slots.

## 10.2 Player Widgets

Possible player widgets:

- Player number
- Device name
- Track title
- Artist
- Track ID
- Track BPM
- Effective BPM
- Pitch
- Playing
- On-Air
- Master
- Track duration
- Elapsed time
- Remaining time
- Beat number
- Beat within bar
- Overview waveform
- Detail waveform
- Connection status

## 10.3 Global Widgets

At least the following global widgets should be available:

- Master BPM
- Master Player
- Ableton Link status
- GrandMA Telnet status
- Pro DJ Link network status
- Diagnostic status

## 10.4 Customization

The user must be able to configure at least:

- Widget visible / hidden
- Position
- Size
- Order
- Font size
- Spacing
- Colors
- Scaling
- Waveform height
- Waveform mode
- Player assignment
- Global UI scaling
- Fullscreen

### SHOULD

Drag and drop and resizing with the mouse should be supported.

---

# 11. UI Profiles

The application must support multiple user profiles.

Example:

```text
Rocket – Main
Rocket – Compact
Laptop
External Display
Raspberry Pi
Debug
```

A UI profile should save at least:

- Layout
- Visible widgets
- Widget positions
- Widget sizes
- Player assignments
- Dynamic sources
- Colors
- Font sizes
- Scaling
- Waveform settings
- Fullscreen status

## 11.1 Import / Export

UI profiles must be exportable and importable.

Proposed format:

```text
JSON
```

Example:

```text
rocket-main.json
```

---

# 12. Ableton Link

## 12.1 Goal

The software should connect the current Pro DJ Link master to Ableton Link.

It should transmit more than just tempo.

The following information is relevant:

- Tempo
- Beat phase
- Bar phase / quantum

## 12.2 Master Source

The sole source is:

```text
Pro DJ Link Tempo Master
```

## 12.3 Timing

A separate timing pipeline must be provided for Link.

It must not depend directly on UI rendering.

The timing pipeline must:

- minimize jitter
- tolerate brief packet loss
- handle master changes
- accurately track pitch changes
- ignore UI delays

## 12.4 CDJ-3000 Position Data

The CDJ-3000 can provide particularly precise position data.

The software should have an abstracted position source:

```text
PositionSource
├── AUTO
├── PRECISE
└── SMOOTHED
```

Default:

```text
AUTO
```

For Link, a smoothed path may be used if high-precision raw data causes visible or audible jitter.

An independently more precise position source may be used for the waveform.

---

# 13. GrandMA2 Telnet

## 13.1 Connection

GrandMA2 should be accessed over TCP/Telnet.

Default port:

```text
30000
```

The user must be able to configure:

- Host / IP
- Port
- Username
- Password
- Login command
- Command template

## 13.2 Command Template

Example:

```text
SpecialMaster 3.1 At {BPM}
```

or:

```text
SetVar $MasterBPM = "{BPM}"
```

## 13.3 Placeholders

At least the following placeholders should be provided:

```text
{BPM}
{BPM_1}
{BPM_INT}
{MASTER_PLAYER}
{TRACK_ID}
{TITLE}
{ARTIST}
```

Definition:

```text
{BPM}          = BPM with 2 decimal places
{BPM_1}        = BPM with 1 decimal place
{BPM_INT}      = integer BPM
```

## 13.4 Send Logic

Telnet output must be configurable:

```text
Send on change
Minimum BPM change
Maximum send rate
Reconnect
Keepalive
```

Defaults:

```text
Send on change:       true
Minimum BPM change:   0.01 BPM
Maximum send rate:    10 Hz
Reconnect:            true
```

## 13.5 Rate Limiting

The application must not send a Telnet command for every incoming Pro DJ Link packet.

A rate limiter must be provided.

## 13.6 Connection Failure

On connection loss:

- Log the error
- Update the UI status
- Do not block the application
- Attempt automatic reconnection
- Continue Pro DJ Link processing

## 13.7 Test Function

The user interface must provide a Telnet test.

Example:

```text
TEST BPM = 140.00
```

The test function should be usable without Pro DJ Link hardware.

---

# 14. Output System

Outputs should be fully modular.

Proposed interface:

```text
OutputModule
├── start()
├── stop()
├── onMasterStateChanged()
├── onPlayerStateChanged()
├── onTrackChanged()
├── getStatus()
└── getConfiguration()
```

Version 1 requires:

- Ableton Link Output
- GrandMA2 Telnet Output

The architecture should allow for later modules:

- OSC
- MIDI
- Art-Net
- sACN
- WebSocket API
- HTTP API

---

# 15. Future Event API

The internal architecture should allow for future event outputs.

These do not necessarily need to be output in version 1, but the architecture must not preclude them:

```text
Master BPM
Master Beat
Bar
Phrase
Track Change
On-Air Change
Master Change
Player Connect
Player Disconnect
```

Possible future GrandMA triggers:

```text
On Beat
On Bar
On Phrase
On Drop
```

These features are explicitly not required for version 1.

---

# 16. Simulation Mode

Simulation mode is a required component.

The application must be fully testable without real DJ hardware.

## 16.1 Quantity

Simulatable:

- 1 to 6 players

## 16.2 Simulatable States

At least:

- Connected
- Disconnected
- Playing
- Paused
- On-Air
- Master
- Track loaded
- Track changed

## 16.3 Simulatable Parameters

At least:

- Track title
- Artist
- Track ID
- Original BPM
- Pitch
- Effective BPM
- Track length
- Playback position
- Beat
- Beat within bar

## 16.4 Controls

The simulator should at least enable:

```text
PLAY
PAUSE
MASTER
ON AIR
LOAD TRACK
DISCONNECT
RECONNECT
SEEK
```

Pitch must be adjustable.

Example:

```text
-10 % ... 0 % ... +10 %
```

## 16.5 Master Handover

The simulator must be able to produce master changes between two players.

Example:

```text
Player 1 MASTER
→ Player 2 MASTER
```

Ableton Link and GrandMA2 output must respond to this.

## 16.6 Simulated Waveforms

At least one artificial test waveform must be available.

Optional:

- RGB test waveform
- 3-band test waveform

---

# 17. Software Architecture

## 17.1 Basic Structure

Recommended architecture:

```text
Pro DJ Link Network
       │
       ▼
beat-link Adapter
       │
       ▼
Application State
       │
 ┌─────┼────────────┐
 ▼     ▼            ▼
UI   Ableton Link  GrandMA Telnet
```

## 17.2 Backend

Recommended:

```text
Java 21 or a current compatible LTS JDK
Kotlin optional or preferred
```

Core library:

```text
Deep Symmetry beat-link
```

## 17.3 Frontend

Recommended:

```text
TypeScript
React or Svelte
```

Svelte is particularly attractive for Raspberry Pi due to its lower complexity and smaller runtime.

The specific choice must remain consistent across the project.

## 17.4 Communication

The backend and frontend should communicate through a local API.

Recommended:

```text
WebSocket
```

Optionally, for configuration, additionally:

```text
REST / HTTP
```

---

# 18. Platform Model

## 18.1 macOS

Primary platform.

The application should run on Apple Silicon Macs.

Intel Mac support is optional as well.

## 18.2 Windows

The application must run on current 64-bit Windows versions.

## 18.3 Raspberry Pi

Target:

```text
Raspberry Pi 4 or newer
64-bit Linux
```

Preferred:

```text
Raspberry Pi 5
```

The UI may run there in Chromium kiosk mode.

## 18.4 Headless Operation

The backend should eventually be able to start without a local UI.

This must at least be anticipated architecturally.

---

# 19. Threading and Real-Time Processing

The application must not run network reception, UI, and outputs on a single thread.

Proposed logical areas:

```text
Thread / Executor A:
Pro DJ Link receive / beat-link callbacks

Thread / Executor B:
Application State updates

Thread / Executor C:
Ableton Link timing

Thread / Executor D:
GrandMA Telnet

Thread / Executor E:
UI / WebSocket updates

Thread / Executor F:
Logging / persistence
```

The implementation may differ technically, as long as the same decoupling is ensured.

---

# 20. State Model

The central state should be the application's source of truth.

Outputs must not query `beat-link` directly.

Correct:

```text
beat-link
    ↓
Application State
    ↓
Output Module
```

Not:

```text
beat-link → UI
beat-link → Telnet
beat-link → Link
beat-link → Simulator
```

---

# 21. Event Model

State changes should be processed in an event-driven manner.

Examples:

```text
PlayerConnected
PlayerDisconnected
PlayerStateUpdated
TrackLoaded
TrackChanged
TempoChanged
MasterChanged
OnAirChanged
PlaybackPositionChanged
BeatOccurred
BarOccurred
```

Outputs should subscribe only to relevant events.

---

# 22. UI Update Rate

The user interface does not need to update for every packet.

Recommended range:

```text
20–60 FPS
```

Waveforms may be prioritized higher than slowly changing text values.

Example:

```text
Playback cursor: 30–60 Hz
BPM text:        on change
Track title:     on change
Device status:   on change
```

---

# 23. Error Handling

The application must robustly handle the following error cases:

- Pro DJ Link network unavailable
- Incorrect network adapter
- Player is powered off
- Mixer is powered off
- Network interruption
- Track metadata unavailable
- Waveform unavailable
- 3-band unavailable
- Master disappears
- Master change
- Ableton Link unavailable
- GrandMA2 unreachable
- Telnet login fails
- Command template invalid
- Configuration file corrupted
- Unknown player
- Unsupported hardware data

A single faulty subsystem must not terminate the application.

---

# 24. Logging

Logging is mandatory.

Log levels:

```text
TRACE
DEBUG
INFO
WARN
ERROR
```

Default:

```text
INFO
```

## 24.1 Events to Log

At least:

- App start
- App version
- Operating system
- Detected network interfaces
- Pro DJ Link start
- Device Connect
- Device Disconnect
- Master Change
- Track Change
- Ableton Link start
- Ableton Link error
- Telnet Connect
- Telnet Disconnect
- Login Error
- Command Error
- Configuration error
- Uncaught exceptions

## 24.2 Debug Mode

Debug mode should be able to output additional Pro DJ Link information.

---

# 25. Diagnostics View

A dedicated diagnostics view must be provided.

At least, display:

```text
Application version
Java version
Operating system
Network interface
Local IP
Pro DJ Link running
Discovered devices
Current master
Master BPM
Ableton Link status
Telnet status
Last Telnet command
Last received update
UI WebSocket status
Simulator status
```

Optional:

```text
Packet counters
Update rate
Dropped updates
Jitter
Latency
Memory usage
CPU usage
```

---

# 26. Persistence

Configuration should be stored locally and persistently.

At least:

```text
application.json
outputs.json
profiles/*.json
```

No hard-coded user-specific paths.

Use platform-appropriate application support directories.

---

# 27. Configuration Versioning

Configuration files must have a version number.

Example:

```json
{
  "configVersion": 1
}
```

Migrations should be possible when changes are made in the future.

---

# 28. Networking

## 28.1 Interface Selection

Since Pro DJ Link often runs on a dedicated Ethernet interface, the network interface to use must be selectable.

Options:

```text
Automatic
Manual
```

Manual:

```text
en0
en5
Ethernet
USB Ethernet
...
```

## 28.2 Multiple Interfaces

The application must not assume that only one network adapter is present.

The following example must be supported:

```text
Wi-Fi → Internet
USB Ethernet → Pro DJ Link
```

## 28.3 Network Changes

If the active interface is lost:

- Display a warning
- Keep the application running
- Allow reinitialization

---

# 29. Security

The application is primarily intended for local show networks.

## 29.1 Web UI

By default, the web UI should only be accessible locally.

Preferred:

```text
127.0.0.1
```

The user should optionally be able to enable access from the LAN.

## 29.2 Passwords

GrandMA Telnet passwords must not accidentally be written to the normal log.

## 29.3 Remote Access

Remote access must be explicitly enabled.

---

# 30. Performance

The software should remain usable on a Raspberry Pi 5 with up to six players.

Goals:

- Smooth UI
- Network processing without significant packet loss
- Ableton Link timing independent of UI load
- Telnet independent of waveform rendering
- No unnecessary full-UI updates

Waveform data should be cached.

---

# 31. Caching

The following information should be cached as needed:

- Track metadata
- Waveform preview
- Waveform detail
- Beat grid

Cache key preferably based on:

```text
Track ID
Source Player
Source Slot / Media Identifier
```

The cache strategy must correctly detect a track change.

---

# 32. Development Mode

There should be a Development Mode.

Options:

- Automatically start the simulator
- Load demo players
- Enable debug logging
- Disable GrandMA output
- Disable Ableton Link
- Generate fake waveforms

---

# 33. Settings

Recommended sections:

```text
General
Network
Players
Display
Waveforms
Ableton Link
GrandMA2
Simulator
Logging
Advanced
```

---

# 34. GrandMA2 Configuration Example

```json
{
  "enabled": true,
  "host": "192.0.2.10",
  "port": 30000,
  "username": "user",
  "password": "",
  "loginTemplate": "login \"{USERNAME}\" \"{PASSWORD}\"",
  "commandTemplate": "SpecialMaster 3.1 At {BPM}",
  "sendOnChange": true,
  "minimumChange": 0.01,
  "maximumRateHz": 10,
  "reconnect": true,
  "keepalive": true
}
```

---

# 35. Player Slot Configuration Example

```json
{
  "slot": 1,
  "sourceType": "PLAYER",
  "playerNumber": 1,
  "visible": true,
  "waveformMode": "THREE_BAND",
  "showOverview": true,
  "showDetail": true
}
```

Dynamic:

```json
{
  "slot": 1,
  "sourceType": "CURRENT_MASTER",
  "visible": true
}
```

---

# 36. Internal API Model

Recommended:

```text
PlayerService
MasterClockService
WaveformService
MetadataService
SimulationService
ConfigurationService
OutputManager
AbletonLinkOutput
GrandMaTelnetOutput
WebSocketServer
UiStateService
```

---

# 37. Recommended Project Structure

```text
project-root/
│
├── backend/
│   ├── src/main/kotlin/
│   │   ├── app/
│   │   ├── prodjlink/
│   │   ├── model/
│   │   ├── state/
│   │   ├── timing/
│   │   ├── waveform/
│   │   ├── simulator/
│   │   ├── outputs/
│   │   │   ├── ableton/
│   │   │   └── grandma/
│   │   ├── api/
│   │   ├── config/
│   │   ├── logging/
│   │   └── diagnostics/
│   │
│   └── src/test/
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── widgets/
│   │   ├── layouts/
│   │   ├── stores/
│   │   ├── websocket/
│   │   ├── simulator/
│   │   ├── settings/
│   │   └── diagnostics/
│   │
│   └── tests/
│
├── config/
├── docs/
├── scripts/
├── README.md
├── ARCHITECTURE.md
└── build/
```

---

# 38. Development Sequence

Recommended implementation sequence:

## Phase 1 – Base Project

- Java/Kotlin project
- `beat-link` dependency
- Logging
- Configuration
- Device discovery

## Phase 2 – Player State

- Players 1–6
- BPM
- Pitch
- Playing
- Master
- On-Air
- Track ID
- Title
- Artist

## Phase 3 – Simulator

- Virtual players
- Master change
- Pitch
- Playback
- Disconnect

## Phase 4 – Web API

- WebSocket
- State events
- Browser test view

## Phase 5 – UI

- Player cards
- Master BPM
- Status displays
- Configurable slots

## Phase 6 – Waveforms

- Preview
- Detail
- Cursor
- RGB
- 3-band
- Fallback

## Phase 7 – GrandMA2

- Telnet
- Login
- Command template
- Placeholders
- Rate limit
- Reconnect

## Phase 8 – Ableton Link

- BPM
- Beat phase
- Quantum
- Timing smoothing

## Phase 9 – Layout Engine

- Widgets
- Drag
- Resize
- Profiles
- Import / Export

## Phase 10 – Packaging

- macOS
- Windows
- Raspberry Pi

---

# 39. Tests

## 39.1 Unit Tests

At least:

- BPM rounding
- Effective BPM
- Command template substitution
- Rate limiting
- Player mapping
- Master change
- Configuration parsing
- Profile import
- Waveform fallback

## 39.2 Simulator Tests

Scenarios:

### Scenario A

```text
Player 1
140 BPM
0 % Pitch
Master
On-Air
```

Expected:

```text
Master BPM = 140.00
```

### Scenario B

```text
Player 1
140 BPM
+1 %
Master
```

Expected:

```text
Effective BPM ≈ 141.40
```

### Scenario C

```text
Player 1 Master
→ Player 2 Master
```

Expected:

- UI updates the master
- Link follows Player 2
- MA output uses Player 2

### Scenario D

The master player is disconnected.

Expected:

```text
MasterClockState.valid = false
```

Until a new master is detected.

### Scenario E

3-band waveform is missing.

Expected:

```text
Fallback to RGB
```

### Scenario F

GrandMA is unreachable.

Expected:

- UI remains functional
- Link remains functional
- Reconnection attempts
- Error message only in the GrandMA module

---

# 40. Hardware Tests

Tests with at least:

- 1 × CDJ-2000NXS2
- 1 × CDJ-3000
- 2 players simultaneously
- 4 players
- 6 players, if possible

Mixer:

- DJM-900NXS2
- DJM-A9
- DJM-V10 or V10-LF

To test:

- On-Air
- Master
- Pitch
- Track change
- BPM
- Waveforms
- Master handover
- Network disconnect

---

# 41. Acceptance Criteria V1

Version 1 is considered functionally complete when the following points are met:

## Pro DJ Link

- At least four players work simultaneously
- Six players are architecturally supported
- CDJ-2000NXS2 works
- CDJ-3000 works
- Players are automatically detected
- Manual player assignment works

## Player Data

Correct display of:

- Title
- Artist
- Track ID
- BPM
- Pitch
- Effective BPM
- Playing
- On-Air
- Master

## Waveforms

- Overview visible
- Detail visible
- Playback cursor visible
- At least RGB works
- 3-band works when data is available
- Fallback works

## Master BPM

- Master changes are detected
- Effective BPM is used
- Display uses two decimal places
- Internal values are not limited to two decimal places

## GrandMA2

- Connection works
- Login works
- Freely definable command template
- `{BPM}` works
- Rate limiting works
- Reconnection works

## Ableton Link

- Tempo follows the Pro DJ Link master
- Master changes work
- Beat phase remains sufficiently stable
- Link processing is independent of UI FPS

## UI

- At least 4 player slots
- Up to 6 slots possible
- Players can be assigned manually
- Current Master is available as a dynamic source
- Widgets can be shown/hidden
- Profiles can be saved
- Profiles can be loaded

## Simulator

- At least 4 virtual players
- Up to 6 possible
- Master change
- Pitch
- Playback
- Track change
- Disconnect

---

# 42. Non-Goals for Version 1

Not mandatory:

- Automatic drop detection
- Phrase detection
- Automatic lighting control
- Beat-to-MA commands
- Bar-to-MA commands
- OSC
- MIDI
- Art-Net output
- sACN output
- Cloud connection
- User accounts
- rekordbox database management
- Track browser
- DJ performance control
- CDJ remote control

These items may be added later.

---

# 43. Quality Goals

The application should:

- be robust against network errors
- be startable without connected hardware
- avoid UI blocking caused by network access
- be extensible in a modular manner
- be easy to test
- log errors visibly
- encapsulate hardware differences
- run particularly reliably on macOS
- be executable on Windows and Raspberry Pi without fundamental code changes

---

# 44. Coding Guidelines

The coding AI should follow these rules:

1. No network logic directly in UI components.
2. Do not send GrandMA commands directly from UI components.
3. Do not put Ableton Link logic directly in UI components.
4. Use a central Application State.
5. Encapsulate hardware access behind interfaces.
6. Connect the simulator through the same interfaces as real hardware.
7. Pass dependencies through dependency injection or clear constructors.
8. Avoid globally mutable singleton states wherever possible.
9. Network threads must not block the UI.
10. Catch exceptions at module boundaries and log them appropriately.
11. Write unit tests for critical calculations.
12. Do not round BPM prematurely.
13. Never invent missing Pro DJ Link data.
14. Implement features as configurable wherever possible.
15. Implement new output protocols as separate modules.

---

# 45. Priorities

## Priority A – Critical

- Pro DJ Link discovery
- Player state
- Master detection
- BPM
- Pitch
- Effective BPM
- On-Air
- Waveforms
- Simulator
- GrandMA2 Telnet
- Ableton Link

## Priority B – Important

- UI profiles
- Free widget arrangement
- Diagnostics view
- Import / Export
- Raspberry Pi compatibility

## Priority C – Later

- Phrase detection
- MA Beat Events
- OSC
- MIDI
- Art-Net
- Remote API

---

# 46. Recommended First Coding Task

The first implementation stage should deliberately be kept small.

Goal:

```text
1. The project starts.
2. beat-link initializes.
3. The network interface can be selected.
4. Pro DJ Link devices are detected.
5. Players 1–6 are maintained as PlayerState.
6. Changes are logged.
7. The simulator can alternatively feed the same PlayerState objects.
8. A WebSocket outputs the current state as JSON.
```

No elaborate UI yet.

Only afterwards:

```text
Waveforms
→ GrandMA
→ Ableton Link
→ Layout Engine
```

---

# 47. Definition of Done

A feature is considered complete only when:

- Implementation is present
- Error cases are handled
- Logging is present
- Configuration is documented
- At least one test is present
- The simulator can reproduce the relevant case, where appropriate
- No other core function is blocked as a result
- Platform dependencies are documented

---

# 48. Long-Term Extensibility

The software should be extensible in the long term into a general show-control bridge for Pro DJ Link.

Possible future architecture:

```text
Pro DJ Link
     │
     ▼
Unified DJ State
     │
     ├── UI
     ├── Ableton Link
     ├── GrandMA2
     ├── GrandMA3
     ├── OSC
     ├── MIDI
     ├── Art-Net
     ├── sACN
     └── Web API
```

The central Unified DJ State should therefore remain independent of the respective output protocol from the beginning.

---

# End of Requirements Specification
