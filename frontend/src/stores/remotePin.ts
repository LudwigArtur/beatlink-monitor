import { derived, writable } from 'svelte/store';
import { api } from './api';
import type { RemoteUnlockInfo } from './types';

/** What the backend says about the remote-button PIN for this device; null until the backend answered. */
export const remoteInfo = writable<RemoteUnlockInfo | null>(null);

export async function refreshRemoteInfo(): Promise<RemoteUnlockInfo | null> {
  try {
    const info = await api.cdjRemoteInfo();
    remoteInfo.set(info);
    return info;
  } catch {
    return null;
  }
}

/** The server device has to create a PIN as long as other devices can reach the UI but no PIN exists. */
export const pinSetupRequired = derived(remoteInfo, ($info) => $info?.local === true && $info.lanAccessEnabled && !$info.pinSet);

/** Other devices must unlock first; the server device and unknown state (not loaded yet) are treated as locked/open respectively. */
export const hardwareWritesLocked = derived(remoteInfo, ($info) => $info !== null && !$info.unlocked);

export const MIN_PIN_LENGTH = 4;
export const MAX_PIN_LENGTH = 64;

/** Same rule as the backend (4 to 64 characters, no whitespace); returns an error text or '' when valid. */
export function pinProblem(pin: string): string {
  if (pin.length < MIN_PIN_LENGTH) return `At least ${MIN_PIN_LENGTH} characters.`;
  if (pin.length > MAX_PIN_LENGTH) return `At most ${MAX_PIN_LENGTH} characters.`;
  if (/\s/.test(pin)) return 'No spaces.';
  return '';
}
