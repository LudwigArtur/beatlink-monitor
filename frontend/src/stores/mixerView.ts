/** Geometry helpers for the stylized mixer drawing. Raw values are the bytes of the mixer's live frame (0..255). */

/** Sweep of a knob from its minimum stop to its maximum stop, in degrees (centered on 12 o'clock). */
export const KNOB_SWEEP_DEGREES = 270;

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

/**
 * Pointer angle in degrees (0 = 12 o'clock, positive = clockwise). A centered knob (EQ, filter, ...) points up at the
 * detent value 128 and reaches the end stops at 0 and 255; any other knob runs from the left stop (0) to the right
 * stop (255).
 */
export function knobAngle(raw: number, centered: boolean): number {
  const half = KNOB_SWEEP_DEGREES / 2;
  if (centered) {
    const offset = raw - 128;
    return clamp01(Math.abs(offset) / (offset < 0 ? 128 : 127)) * half * Math.sign(offset);
  }
  return -half + clamp01(raw / 255) * KNOB_SWEEP_DEGREES;
}

/** Position of a fader cap, 0 = bottom, 1 = top. */
export function faderFraction(raw: number): number {
  return clamp01(raw / 255);
}

/** Meter fill, 0..1, for a level byte on the mixer's -40..0 dB scale. */
export function meterFraction(level: number): number {
  if (level <= 0) return 0;
  return clamp01(1 + (20 * Math.log10(level / 255)) / 40);
}

/** Point on a circle, angle in degrees measured clockwise from 12 o'clock. */
export function polar(radius: number, degrees: number): { x: number; y: number } {
  const rad = (degrees * Math.PI) / 180;
  return { x: radius * Math.sin(rad), y: -radius * Math.cos(rad) };
}

/** SVG path of the circular track a knob's pointer can travel along. */
export function knobTrack(radius: number): string {
  const half = KNOB_SWEEP_DEGREES / 2;
  const start = polar(radius, -half);
  const end = polar(radius, half);
  return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} A ${radius} ${radius} 0 1 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
}
