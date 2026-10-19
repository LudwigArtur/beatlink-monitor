import { get, writable } from 'svelte/store';
import { api } from './api';
import { defaultProfile } from './profileDefaults';
import type { PlayerSlotConfig, UiProfile } from './types';

/**
 * Holds the single, automatically-loaded/saved user profile (requirements specification
 * §11): visible player slots, global display scale, background color and
 * fullscreen state. This is always the one profile that auto-loads on
 * startup and auto-saves on every change - no manual "save layout" step is
 * needed for day-to-day use.
 *
 * On top of that, the user can also manually save/load named *presets*
 * (snapshots of this profile under a different name) via `savePresetAs` /
 * `loadPreset` below, e.g. to back up a configuration or switch between a
 * couple of known setups without re-entering everything by hand. Loading a
 * preset copies its settings into this one live profile (which keeps
 * auto-saving from then on) rather than "switching the active profile" -
 * there is still only ever one thing that's live and auto-loaded.
 */
export const activeProfile = writable<UiProfile>(defaultProfile());
export const presetNames = writable<string[]>([]);

const PROFILE_NAME = 'default';

export async function initLayout(): Promise<void> {
  try {
    const profile = await api.getActiveProfile();
    activeProfile.set(normalizeProfile(profile));
  } catch (e) {
    // No profile saved yet: fall back to the built-in default, and persist
    // it immediately so subsequent setting changes have something to save
    // into.
    console.warn('No active profile found yet, using built-in default', e);
    try {
      await api.saveProfile(get(activeProfile));
    } catch (e2) {
      console.error('Failed to persist initial default profile', e2);
    }
  }
  await refreshPresetNames();
}

/** Ensures older/partial profiles still have all fields the UI expects. */
function normalizeProfile(profile: UiProfile): UiProfile {
  const fallback = defaultProfile(profile.name || PROFILE_NAME);
  return {
    ...fallback,
    ...profile,
    name: PROFILE_NAME,
    playerSlots: profile.playerSlots?.length ? profile.playerSlots : fallback.playerSlots,
  };
}

async function persist(): Promise<void> {
  await api.saveProfile(get(activeProfile));
}

export function setSlot(slot: number, patch: Partial<PlayerSlotConfig>): void {
  activeProfile.update((p) => ({
    ...p,
    playerSlots: p.playerSlots.map((s) => (s.slot === slot ? { ...s, ...patch } : s)),
  }));
  void persist();
}

export function setGlobalScale(scale: number): void {
  activeProfile.update((p) => ({ ...p, globalScale: scale }));
  void persist();
}

export function setFullscreenFlag(fullscreen: boolean): void {
  activeProfile.update((p) => ({ ...p, fullscreen }));
  void persist();
}

export function setBackgroundColor(color: string): void {
  activeProfile.update((p) => ({ ...p, backgroundColor: color }));
  void persist();
}

export function setInactiveDeckBrightnessPercent(percent: number): void {
  activeProfile.update((p) => ({ ...p, inactiveDeckBrightnessPercent: percent }));
  void persist();
}

export function setWaveformSmoothingFactor(factor: number): void {
  activeProfile.update((p) => ({ ...p, waveformSmoothingFactor: factor }));
  void persist();
}

/** Refreshes the list of manually saved presets (not including live auto-save state). */
export async function refreshPresetNames(): Promise<void> {
  try {
    presetNames.set((await api.listProfiles()).filter((name) => name !== PROFILE_NAME));
  } catch (e) {
    console.error('Failed to list presets', e);
  }
}

/** Manually saves the current live settings as a named, reloadable preset. */
export async function savePresetAs(name: string): Promise<void> {
  const trimmed = name.trim();
  if (!trimmed) return;
  const preset: UiProfile = { ...get(activeProfile), name: trimmed };
  await api.saveProfile(preset);
  await refreshPresetNames();
}

/** Manually loads a previously saved preset into the live profile, replacing current settings. */
export async function loadPreset(name: string): Promise<void> {
  const preset = await api.getProfile(name);
  activeProfile.set(normalizeProfile(preset));
  await persist();
}

/** Deletes a manually saved preset (does not affect the live profile). */
export async function deletePreset(name: string): Promise<void> {
  await api.deleteProfile(name);
  await refreshPresetNames();
}

