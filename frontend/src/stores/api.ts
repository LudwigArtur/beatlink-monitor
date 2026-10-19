import { API_BASE } from '../websocket/connection';
import type {
  ApplicationConfig,
  ApplicationConfigSaveResult,
  BeatGridResponse,
  NetworkInterfaceInfo,
  OutputsConfig,
  SimulatedTrack,
  TelnetConsoleLine,
  UiProfile,
  WaveformResponse,
} from '../stores/types';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!response.ok) {
    throw new Error(`Request to ${path} failed with status ${response.status}`);
  }
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export const api = {
  // --- Configuration ---------------------------------------------------
  getApplicationConfig: () => request<ApplicationConfig>('/api/config/application'),
  saveApplicationConfig: (config: ApplicationConfig) =>
    request<ApplicationConfigSaveResult>('/api/config/application', { method: 'POST', body: JSON.stringify(config) }),
  getOutputsConfig: () => request<OutputsConfig>('/api/config/outputs'),
  saveOutputsConfig: (config: OutputsConfig) =>
    request<void>('/api/config/outputs', { method: 'POST', body: JSON.stringify(config) }),
  getNetworkInterfaces: () => request<NetworkInterfaceInfo[]>('/api/network/interfaces'),

  // --- Profiles ----------------------------------------------------------
  listProfiles: () => request<string[]>('/api/profiles'),
  getActiveProfile: () => request<UiProfile>('/api/profiles/active'),
  setActiveProfile: (name: string) => request<UiProfile>(`/api/profiles/active/${encodeURIComponent(name)}`, { method: 'POST' }),
  getProfile: (name: string) => request<UiProfile>(`/api/profiles/${encodeURIComponent(name)}`),
  saveProfile: (profile: UiProfile) => request<void>('/api/profiles', { method: 'POST', body: JSON.stringify(profile) }),
  deleteProfile: (name: string) => request<void>(`/api/profiles/${encodeURIComponent(name)}`, { method: 'DELETE' }),

  // --- Simulator -----------------------------------------------------------
  simulatorStart: () => request<void>('/api/simulator/start', { method: 'POST' }),
  simulatorStop: () => request<void>('/api/simulator/stop', { method: 'POST' }),
  simulatorTracks: () => request<SimulatedTrack[]>('/api/simulator/tracks'),
  simulatorConnect: (device: number) => request<void>(`/api/simulator/${device}/connect`, { method: 'POST' }),
  simulatorDisconnect: (device: number) => request<void>(`/api/simulator/${device}/disconnect`, { method: 'POST' }),
  simulatorPlay: (device: number) => request<void>(`/api/simulator/${device}/play`, { method: 'POST' }),
  simulatorPause: (device: number) => request<void>(`/api/simulator/${device}/pause`, { method: 'POST' }),
  simulatorMaster: (device: number) => request<void>(`/api/simulator/${device}/master`, { method: 'POST' }),
  simulatorOnAir: (device: number, on: boolean) =>
    request<void>(`/api/simulator/${device}/onair`, { method: 'POST', body: JSON.stringify({ action: 'onair', boolValue: on }) }),
  simulatorPitch: (device: number, percent: number) =>
    request<void>(`/api/simulator/${device}/pitch`, { method: 'POST', body: JSON.stringify({ action: 'pitch', value: percent }) }),
  simulatorSeek: (device: number, positionMs: number) =>
    request<void>(`/api/simulator/${device}/seek`, { method: 'POST', body: JSON.stringify({ action: 'seek', positionMs }) }),
  simulatorLoad: (device: number, trackIndex: number) =>
    request<void>(`/api/simulator/${device}/load/${trackIndex}`, { method: 'POST' }),

  // --- Outputs -------------------------------------------------------------
  grandMaTest: (bpm: number) =>
    request<void>('/api/outputs/grandma/test', { method: 'POST', body: JSON.stringify({ action: 'test', value: bpm }) }),
  getTelnetConsoleHistory: () => request<TelnetConsoleLine[]>('/api/outputs/grandma/console'),
  sendTelnetCommand: (command: string) =>
    request<void>('/api/outputs/grandma/send', { method: 'POST', body: JSON.stringify({ command }) }),

  // --- Waveform --------------------------------------------------------------
  getWaveform: (device: number) => request<WaveformResponse>(`/api/waveform/${device}`),
  getWaveformDetail: (device: number, beforeMs: number, afterMs: number, columns: number) =>
    request<WaveformResponse>(
      `/api/waveform/${device}/detail?beforeMs=${beforeMs}&afterMs=${afterMs}&columns=${columns}`,
    ),
  getWaveformDetailFull: (device: number) => request<WaveformResponse>(`/api/waveform/${device}/detail/full`),
  getBeatGrid: (device: number) => request<BeatGridResponse>(`/api/waveform/${device}/beatgrid`),
};
