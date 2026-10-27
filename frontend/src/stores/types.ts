// Mirrors the backend's serialization DTOs
// (backend/src/main/kotlin/com/beatlinkmonitor/model, .../api/Messages.kt).

export type Availability = 'AVAILABLE' | 'UNKNOWN' | 'UNSUPPORTED';
export type DeviceType = 'CDJ' | 'MIXER' | 'REKORDBOX' | 'UNKNOWN';
export type WaveformType = 'RGB' | 'THREE_BAND' | 'CLASSIC' | 'NONE';

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
  logDirectory: string;
  ownDeviceNumber: number | null;
}

export interface DiagnosticsMessage {
  type: 'diagnostics';
  diagnostics: DiagnosticsSnapshot;
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
  logLevel: string;
  activeProfile: string;
  mixerIntegrationEnabled: boolean;
  mixerControlEnabled: boolean;
  mixerAddress: string | null;
  mixerAnnounceIdentity: boolean;
  cdjIntegrationEnabled: boolean;
  cdjControlEnabled: boolean;
  mixerIdentityName: string;
}

export interface ApplicationConfigSaveResult {
  restartRequired: boolean;
}

export interface MixerChannelState {
  channel: number;
  input: number;
  trim: number;
  comp: number;
  eqHi: number;
  eqHiMid: number;
  eqLoMid: number;
  eqLow: number;
  filter: number;
  send: number;
  cue: boolean;
  cue2: number;
  fader: number;
  crossfaderAssign: number;
}

export interface MixerLiveState {
  channels: MixerChannelState[];
  crossfader: number;
  masterLevel: number;
  masterFilter: number;
  masterCue: number;
  isolatorOn: number;
  isoHi: number;
  isoMid: number;
  isoLow: number;
  booth: number;
  boothHi: number;
  boothLow: number;
  fxFreqLow: number;
  fxFreqMid: number;
  fxFreqHi: number;
  fxSelect: number;
  fxChannel: number;
  fxLevel: number;
  fxOn: number;
  hpMix: number;
  hpLevel: number;
}

export interface UtilityValue {
  id: number;
  value: number | null;
  timestampMs: number;
}

export interface MixerState {
  live: MixerLiveState | null;
  liveTimestampMs: number;
  utilities: UtilityValue[];
  version: string | null;
}

export interface UtilityItem {
  id: number;
  key: string;
  label: string;
  min: number;
  max: number;
  signed: boolean;
  note: string | null;
}

export interface MixerLinkStatus {
  integrationEnabled: boolean;
  controlEnabled: boolean;
  transportAvailable: boolean;
  mixerAddress: string | null;
  liveFramesReceived: number;
  utilityRepliesReceived: number;
  lastLiveFrameAgeMs: number | null;
  lastError: string | null;
  announcer: { state: 'OFF' | 'WAITING' | 'CLAIMING' | 'ONLINE' | 'NETWORK_FULL'; deviceNumber: number | null; name: string } | null;
  utilityItems: UtilityItem[];
}

export interface MixerSnapshot {
  state: MixerState;
  link: MixerLinkStatus;
}

export type UtilityWriteStatus =
  | 'CONFIRMED'
  | 'MISMATCH'
  | 'REJECTED'
  | 'NO_REPLY'
  | 'CONTROL_DISABLED'
  | 'NO_MIXER'
  | 'INVALID';

export interface UtilityWriteResult {
  status: UtilityWriteStatus;
  id: number;
  requestedValue: number;
  reportedValue: number | null;
  message: string | null;
}

export interface ChannelLevel {
  channel: number;
  left: number;
  right: number;
  bands: number[];
}

export interface AudioLevelStatus {
  active: boolean;
  framesReceived: number;
  requestsSent: number;
  levels: { channels: ChannelLevel[]; masterLeft: number; masterRight: number; clip: number[]; timestampMs: number } | null;
}

export interface CdjSettingItem {
  offset: number;
  key: string;
  label: string;
  min: number;
  max: number;
  note: string | null;
}

export interface CdjRemoteButton {
  code: number;
  key: string;
  label: string;
  group: string;
}

export interface CdjDeckFlags {
  tempoReset: boolean;
  trackBpm: number;
  pitchPercent: number;
  masterTempo: boolean;
  keySync: boolean;
  sync: boolean;
  tempoMaster: boolean;
  vinylMode: boolean;
  unknown31: number;
  slip: boolean;
  autoCue: boolean;
  quantize: boolean;
}

export interface CdjDeviceState {
  deviceNumber: number;
  settings: Record<string, number>;
  settingsTimestampMs: number;
  flags: CdjDeckFlags | null;
  flagsTimestampMs: number;
}

export interface CdjLinkStatus {
  integrationEnabled: boolean;
  controlEnabled: boolean;
  transportAvailable: boolean;
  settingsRepliesReceived: number;
  statusRepliesReceived: number;
  lastError: string | null;
  settingItems: CdjSettingItem[];
  buttons: CdjRemoteButton[];
}

export interface CdjSnapshot {
  devices: CdjDeviceState[];
  link: CdjLinkStatus;
}

export interface CdjWriteResult {
  status: 'CONFIRMED' | 'MISMATCH' | 'NO_REPLY' | 'INVALID' | 'NO_DEVICE' | 'CONTROL_DISABLED';
  deviceNumber: number;
  offset: number;
  requestedValue: number;
  reportedValue: number | null;
  message: string | null;
}

export interface RemoteUnlockInfo {
  pinFileName: string;
  pinFilePresent: boolean;
  unlocked: boolean;
}

export interface RemoteUnlockResult {
  status: 'UNLOCKED' | 'WRONG_PIN' | 'NO_PIN_FILE' | 'INVALID_PIN_FILE' | 'LOCKED_OUT' | 'UNKNOWN_CONNECTION';
  retryAfterSeconds: number | null;
}

export interface CdjRemoteResult {
  status: 'SENT' | 'NO_DEVICE' | 'INVALID' | 'FAILED';
  message: string | null;
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
  eqWaveformEnabled: boolean;
  eqRawMin: number;
  eqRawCenter: number;
  eqMinBrightness: number;
  eqBoostGlow: number;
  eqMidSource: 'AUTO' | 'AVERAGE' | 'HI_MID' | 'LO_MID';
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
