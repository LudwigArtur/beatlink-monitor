/**
 * Playhead smoothing levels of the Live View. The stored profile value is the soft-correction factor; the first
 * three levels keep the behavior they always had (factor only, dead zone 120 ms, snap at 600 ms, snap on any pitch
 * change). The two additional levels use factors below the old slider range and, on top of that, tolerate more jitter
 * and follow pitch changes without snapping the playhead, which is what makes a jittery position feed look calm.
 */
export type SmoothingLevel = 'PRECISE' | 'BALANCED' | 'SMOOTH' | 'SMOOTHER' | 'SMOOTHEST';

export const SMOOTHING_LEVELS: { id: SmoothingLevel; label: string; factor: number; hint: string }[] = [
  { id: 'PRECISE', label: 'Precise', factor: 0.2, hint: 'Follows the reported position closely' },
  { id: 'BALANCED', label: 'Balanced', factor: 0.12, hint: 'Compromise between smooth and precise' },
  { id: 'SMOOTH', label: 'Smooth', factor: 0.08, hint: 'Default: gradual corrections' },
  { id: 'SMOOTHER', label: 'Smoother', factor: 0.015, hint: 'Ignores more jitter; pitch changes do not snap the playhead' },
  { id: 'SMOOTHEST', label: 'Smoothest', factor: 0.01, hint: 'Calmest display; real jumps are caught a little later' },
];

/** Level a stored factor belongs to (values saved by the former slider map onto the first three levels). */
export function smoothingLevel(factor: number): SmoothingLevel {
  if (factor < 0.0125) return 'SMOOTHEST';
  if (factor < 0.0175) return 'SMOOTHER';
  if (factor >= 0.18) return 'PRECISE';
  if (factor >= 0.09) return 'BALANCED';
  return 'SMOOTH';
}

export interface SmoothingParams {
  /** Fraction of a disagreement corrected per update. */
  factor: number;
  /** Disagreements below this are pure jitter and ignored. */
  deadZoneMs: number;
  /** Disagreements above this are a real seek and snap immediately. */
  hardResyncMs: number;
  /** Pitch change (in percent) that re-anchors the playhead. */
  pitchThreshold: number;
  /** Keep the playhead continuous when the pitch changes instead of snapping to the reported position. */
  continuousPitch: boolean;
}

export function smoothingParams(factor: number): SmoothingParams {
  switch (smoothingLevel(factor)) {
    case 'SMOOTHEST':
      return { factor, deadZoneMs: 300, hardResyncMs: 1200, pitchThreshold: 0.05, continuousPitch: true };
    case 'SMOOTHER':
      return { factor, deadZoneMs: 200, hardResyncMs: 900, pitchThreshold: 0.05, continuousPitch: true };
    default:
      return { factor, deadZoneMs: 120, hardResyncMs: 600, pitchThreshold: 0.01, continuousPitch: false };
  }
}

/** How long the playhead takes to glide over a jump of [offsetMs]: short for small, longer for large jumps. */
export function glideDurationMs(offsetMs: number): number {
  return Math.min(800, 300 + Math.abs(offsetMs) / 40);
}

/**
 * What is left of a glide that started with [offsetMs] (displayed position minus the real position at the moment of
 * the jump) after [elapsedMs]: ease-in-out from the full offset to 0, so the playhead accelerates and slows down
 * smoothly and the motion never reverses.
 */
export function glideRemainingMs(offsetMs: number, elapsedMs: number): number {
  const t = Math.min(1, Math.max(0, elapsedMs / glideDurationMs(offsetMs)));
  const ease = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
  return offsetMs * (1 - ease);
}
