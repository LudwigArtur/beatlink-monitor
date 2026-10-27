import { derived } from 'svelte/store';
import { mixerState } from '../websocket/connection';
import { activeProfile } from './layout';
import type { MixerChannelState, UiProfile } from './types';

/** Brightness of the low / mid / high part of a waveform: 1 = unchanged, below 1 = dimmed, above 1 = boosted (glow). */
export interface BandGains {
  low: number;
  mid: number;
  high: number;
}

export type EqSettings = Pick<UiProfile, 'eqRawMin' | 'eqRawCenter' | 'eqMidSource' | 'eqMinBrightness' | 'eqBoostGlow'>;

const RAW_MAX = 255;

/**
 * Brightness factor of one band for a raw EQ knob value: [minBrightness] at or below [rawMin] (knob fully
 * down), rising linearly to 1 at [rawCenter] (detent), then on to 1 + [boost] at the top of the scale.
 */
export function eqGain(raw: number, rawMin: number, rawCenter: number, minBrightness = 0, boost = 0): number {
  if (rawCenter <= rawMin) return 1;
  if (raw <= rawMin) return minBrightness;
  if (raw <= rawCenter) return minBrightness + (1 - minBrightness) * ((raw - rawMin) / (rawCenter - rawMin));
  if (RAW_MAX <= rawCenter) return 1;
  return 1 + boost * Math.min(1, (raw - rawCenter) / (RAW_MAX - rawCenter));
}

/**
 * 3-band mixers keep their single mid knob in Hi-Mid and leave Lo-Mid at 0 on every channel (seen in
 * data of a real DJM-V10); detected here as "all Lo-Mid values in the kill region while some Hi-Mid is not".
 */
export function looksThreeBand(channels: MixerChannelState[]): boolean {
  return channels.length > 0 && channels.every((c) => c.eqLoMid <= 2) && channels.some((c) => c.eqHiMid > 2);
}

/** Low = red/blue layer, mid = green/amber layer, high = blue/white layer of the waveform. */
export function bandGainsFor(channel: MixerChannelState, settings: EqSettings, threeBand = false): BandGains {
  const g = (raw: number) =>
    eqGain(raw, settings.eqRawMin, settings.eqRawCenter, settings.eqMinBrightness, settings.eqBoostGlow);
  const hiMid = g(channel.eqHiMid);
  const loMid = g(channel.eqLoMid);
  const source = settings.eqMidSource === 'AUTO' ? (threeBand ? 'HI_MID' : 'AVERAGE') : settings.eqMidSource;
  const mid = source === 'HI_MID' ? hiMid : source === 'LO_MID' ? loMid : (hiMid + loMid) / 2;
  const round = (v: number) => Math.round(v * 100) / 100;
  return { low: round(g(channel.eqLow)), mid: round(mid), high: round(g(channel.eqHi)) };
}

/**
 * Per-channel band gains from the mixer's live EQ positions (channel N is
 * taken to be the one fed by player N), or an empty object when the feature
 * is off or no live mixer data is available - in which case waveforms are
 * drawn at full brightness. Only emits when a value actually changed so the
 * waveforms are not redrawn needlessly.
 */
let lastKey = '{}';

export const eqGains = derived(
  [mixerState, activeProfile],
  ([$mixer, $profile], set) => {
    const next: Record<number, BandGains> = {};
    if ($profile.eqWaveformEnabled && $mixer.live) {
      const threeBand = looksThreeBand($mixer.live.channels);
      for (const channel of $mixer.live.channels) next[channel.channel] = bandGainsFor(channel, $profile, threeBand);
    }
    const key = JSON.stringify(next);
    if (key !== lastKey) {
      lastKey = key;
      set(next);
    }
  },
  {} as Record<number, BandGains>,
);
