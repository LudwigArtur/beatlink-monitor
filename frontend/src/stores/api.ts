import { API_BASE } from '../websocket/connection';
import type {
  ApplicationConfig,
  ApplicationConfigSaveResult,
  AudioLevelStatus,
  BeatGridResponse,
  CdjRemoteResult,
  CdjSnapshot,
  CdjWriteResult,
  MixerSnapshot,
  NetworkInterfaceInfo,
  OutputsConfig,
  TelnetConsoleLine,
  UiProfile,
  UtilityWriteResult,
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

  // --- Mixer ---------------------------------------------------------------
  getMixer: () => request<MixerSnapshot>('/api/mixer'),
  mixerReadAll: () => request<{ started: boolean }>('/api/mixer/utility/read', { method: 'POST' }),
  mixerRead: (id: number) => request<{ sent: boolean }>(`/api/mixer/utility/${id}/read`, { method: 'POST' }),
  getMixerLevels: () => request<AudioLevelStatus>('/api/mixer/levels'),
  getCdj: () => request<CdjSnapshot>('/api/cdj'),
  cdjRefresh: () => request<unknown>('/api/cdj/refresh', { method: 'POST' }),
  cdjWriteSetting: (player: number, offset: number, value: number) =>
    request<CdjWriteResult>(`/api/cdj/${player}/setting/${offset}`, { method: 'POST', body: JSON.stringify({ value }) }),
  cdjButton: (player: number, code: number, press: 'click' | 'down' | 'up' = 'click') =>
    request<CdjRemoteResult>(`/api/cdj/${player}/button/${code}?press=${press}`, { method: 'POST' }),
  mixerWriteUtility: (id: number, value: number) =>
    request<UtilityWriteResult>(`/api/mixer/utility/${id}`, { method: 'POST', body: JSON.stringify({ value }) }),

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
