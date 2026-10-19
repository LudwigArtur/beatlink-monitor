import { writable } from 'svelte/store';
import type { DiagnosticsSnapshot, MasterClockState, OutputStatus, PlayerState } from '../stores/types';

/**
 * Resolves the backend API base at runtime: in dev (Vite on 5173) it points
 * at the default backend port; in production the UI is expected to be served
 * by (or alongside) the backend itself, so relative URLs are used.
 */
export const API_BASE =
  import.meta.env.DEV ? 'http://127.0.0.1:17812' : '';

export const WS_URL = (API_BASE || window.location.origin).replace(/^http/, 'ws') + '/ws';

export const players = writable<PlayerState[]>([]);
export const masterClock = writable<MasterClockState>({
  masterPlayerNumber: null,
  trackBpm: null,
  effectiveBpm: null,
  beatNumber: null,
  beatWithinBar: null,
  phase: 0,
  lastUpdateTimestamp: 0,
  valid: false,
});
export const outputs = writable<OutputStatus[]>([]);
export const diagnostics = writable<DiagnosticsSnapshot | null>(null);
export const connectionStatus = writable<'connecting' | 'open' | 'closed'>('connecting');

let socket: WebSocket | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * Connects to the backend's state-push WebSocket and reconnects
 * automatically on disconnect (mirrors the resilience required of the
 * backend's own outputs, requirements specification §23).
 */
export function connectWebSocket(): void {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;

  connectionStatus.set('connecting');
  socket = new WebSocket(WS_URL);

  socket.onopen = () => connectionStatus.set('open');

  socket.onmessage = (event) => {
    try {
      const message = JSON.parse(event.data);
      if (message.type === 'state') {
        players.set(message.players);
        masterClock.set(message.masterClock);
        outputs.set(message.outputs);
      } else if (message.type === 'diagnostics') {
        diagnostics.set(message.diagnostics);
      }
    } catch (e) {
      console.error('Failed to parse WS message', e);
    }
  };

  socket.onclose = () => {
    connectionStatus.set('closed');
    scheduleReconnect();
  };

  socket.onerror = () => {
    socket?.close();
  };
}

function scheduleReconnect(): void {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectWebSocket();
  }, 2000);
}
