// Mirrors the backend's serialization DTOs
// (backend/src/main/kotlin/com/beatlinkmonitor/model, .../api/Messages.kt).

export type Availability = 'AVAILABLE' | 'UNKNOWN' | 'UNSUPPORTED';
export type DeviceType = 'CDJ' | 'MIXER' | 'REKORDBOX' | 'UNKNOWN';
export type WaveformType = 'RGB' | 'THREE_BAND' | 'CLASSIC' | 'NONE';
export type PlayerSource = 'REAL' | 'SIMULATED';

export interface PlayerState {
  deviceNumber: number;
  deviceName: string;
  deviceType: DeviceType;
  ipAddress: string | null;
  firmwareVersion: string | null;
  connected: boolean;
  playing: boolean;
  paused: boolean;
  onAir: boolean;
  looping: boolean;
  loopStartMs: number | null;
  loopEndMs: number | null;
  tempoMaster: boolean;
  rekordboxTrackId: number | null;
  title: string | null;
  artist: string | null;
  originalBpm: number | null;
  pitchPercent: number | null;
  effectiveBpm: number | null;
  beatNumber: number | null;
  beatWithinBar: number | null;
  playbackPositionMs: number | null;
  trackDurationMs: number | null;
  waveformType: WaveformType;
  hasWaveformPreview: boolean;
  hasWaveformDetail: boolean;
  hasBeatGrid: boolean;
  lastUpdateTimestamp: number;
  dataAvailability: Record<string, Availability>;
  source: PlayerSource;
}

export interface MasterClockState {
  masterPlayerNumber: number | null;
  trackBpm: number | null;
  effectiveBpm: number | null;
  beatNumber: number | null;
  beatWithinBar: number | null;
  phase: number;
  lastUpdateTimestamp: number;
  valid: boolean;
}

export interface OutputStatus {
  name: string;
  enabled: boolean;
  connected: boolean;
  lastError: string | null;
  lastSentAt: number | null;
  lastSentPayload: string | null;
  detail: string | null;
}

export interface StateSnapshotMessage {
  type: 'state';
  players: PlayerState[];
  masterClock: MasterClockState;
  outputs: OutputStatus[];
}

export interface DiagnosticsSnapshot {
  applicationVersion: string;
  javaVersion: string;
  operatingSystem: string;
  networkInterfaces: string[];
  localIp: string | null;
  proDjLinkRunning: boolean;
  discoveredDevices: number;
  currentMasterPlayer: number | null;
  masterBpm: number | null;
  abletonLinkConnected: boolean;
  telnetConnected: boolean;
  lastTelnetCommand: string | null;
  uiWebSocketConnections: number;
  simulatorRunning: boolean;
  logDirectory: string;
  ownDeviceNumber: number | null;
}

export interface DiagnosticsMessage {
  type: 'diagnostics';
  diagnostics: DiagnosticsSnapshot;
}

export interface SimulatedTrack {
  title: string;
  artist: string;
  trackId: number;
  originalBpm: number;
  durationMs: number;
}

export interface GrandMaConfig {
  enabled: boolean;
  host: string;
  port: number;
  username: string;
  password: string;
  loginTemplate: string;
  commandTemplate: string;
  sendOnChange: boolean;
  minimumChange: number;
  maximumRateHz: number;
  reconnect: boolean;
  keepalive: boolean;
  networkInterfaceMode: 'AUTOMATIC' | 'MANUAL';
  manualInterfaceName: string | null;
}

export type LinkPositionSource = 'AUTO' | 'PRECISE' | 'SMOOTHED';

export interface AbletonLinkConfig {
  enabled: boolean;
  carabinerHost: string;
  carabinerPort: number;
  quantum: number;
  positionSource: LinkPositionSource;
  networkInterfaceMode: 'AUTOMATIC' | 'MANUAL';
  manualInterfaceName: string | null;
}

export interface OutputsConfig {
  configVersion: number;
  grandMa: GrandMaConfig;
  abletonLink: AbletonLinkConfig;
}

export interface ApplicationConfig {
  configVersion: number;
  networkInterfaceMode: 'AUTOMATIC' | 'MANUAL';
  manualInterfaceName: string | null;
  preferredDeviceNumber: number;
  waveformStylePreference: 'RGB' | 'THREE_BAND';
  webUiPort: number;
  allowLanAccess: boolean;
  bpmDecimalPlaces: number;
  developmentMode: boolean;
  simulatorAutoStart: boolean;
  logLevel: string;
  activeProfile: string;
}

export interface ApplicationConfigSaveResult {
  restartRequired: boolean;
}

export interface WidgetConfig {
  id: string;
  type: string;
  visible: boolean;
  x: number;
  y: number;
  width: number;
  height: number;
  order: number;
  fontSize: number;
  color: string | null;
}

/**
 * Simplified per-slot configuration (requirements specification-driven redesign): a
 * single choice of what each of the (always exactly six) Monitor/Live View
 * slots shows - a fixed player number, the current tempo master, or
 * disabled entirely. Sizing/ordering is no longer configurable - the
 * Monitor page auto-arranges itself based on how many slots are enabled.
 */
export interface PlayerSlotConfig {
  slot: number;
  sourceType: 'PLAYER' | 'CURRENT_MASTER';
  playerNumber: number | null;
  visible: boolean;
}

export interface UiProfile {
  configVersion: number;
  name: string;
  widgets: WidgetConfig[];
  playerSlots: PlayerSlotConfig[];
  globalScale: number;
  fullscreen: boolean;
  backgroundColor: string;
  inactiveDeckBrightnessPercent: number;
  waveformSmoothingFactor: number;
}


export interface NetworkInterfaceInfo {
  name: string;
  displayName: string;
  addresses: string[];
}

export interface WaveformResponse {
  deviceNumber: number;
  type: WaveformType;
  available: boolean;
  heights: number[];
  colors: string[] | null;
  // Populated instead of heights/colors when type === 'THREE_BAND' (a
  // CDJ-3000's native waveform style) - independent per-frequency-band
  // heights (0-31), rendered as three overlapping colored layers.
  lowHeights?: number[] | null;
  midHeights?: number[] | null;
  highHeights?: number[] | null;
  note: string | null;
  columnIntervalMs: number;
}

export interface BeatGridResponse {
  deviceNumber: number;
  available: boolean;
  beatTimesMs: number[];
  beatsWithinBar: number[];
  note: string | null;
}

export interface TelnetConsoleLine {
  timestamp: number;
  direction: 'SENT' | 'RECEIVED' | 'INFO';
  text: string;
}
