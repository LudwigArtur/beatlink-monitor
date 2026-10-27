import { API_BASE, currentConnectionId } from '../websocket/connection';
import type {
  ApplicationConfig,
  ApplicationConfigSaveResult,
  AudioLevelStatus,
  BeatGridResponse,
  CdjRemoteResult,
  RemoteUnlockInfo,
  RemoteUnlockResult,
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

/** Remote CDJ calls identify the WebSocket connection whose PIN unlock they rely on. */
function remoteOptions(options: RequestInit = {}): RequestInit {
  return { ...options, headers: { 'Content-Type': 'application/json', 'X-Connection-Id': currentConnectionId ?? '' } };
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
    request<CdjRemoteResult>(`/api/cdj/${player}/button/${code}?press=${press}`, remoteOptions({ method: 'POST' })),
  cdjRemoteInfo: () => request<RemoteUnlockInfo>('/api/cdj/remote', remoteOptions()),
  cdjRemoteLock: () => request<unknown>('/api/cdj/remote/lock', remoteOptions({ method: 'POST' })),
  /** Unlike other calls this one also returns the body of a rejected PIN (403/409/429). */
  cdjRemoteUnlock: async (pin: string): Promise<RemoteUnlockResult> => {
    const response = await fetch(`${API_BASE}/api/cdj/remote/unlock`, remoteOptions({ method: 'POST', body: JSON.stringify({ pin }) }));
    return (await response.json()) as RemoteUnlockResult;
  },
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
