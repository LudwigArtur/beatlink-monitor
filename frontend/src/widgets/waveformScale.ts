/**
 * Robust scaling reference for waveform bar heights.
 *
 * Both waveform renderers (`WaveformView`'s overview strip and
 * `LiveDetailWaveform`'s zoomed detail view) auto-scale bar heights by the
 * observed maximum value in the data, rather than a hardcoded divisor -
 * intentionally, since different waveform formats beat-link returns use
 * different natural ranges ("classic"/three-band heights are 0-31, while
 * RGB color waveforms use raw byte magnitudes, roughly 0-255).
 *
 * Using the literal maximum for that, however, is fragile: a single
 * corrupted/outlier sample anywhere in an entire track's data (observed on
 * real hardware - Pro DJ Link waveform data occasionally contains a stray
 * anomalous value, cause unconfirmed, but plausibly a transient decode
 * glitch) silently scales every *other*, perfectly normal bar down to a
 * sub-pixel, invisible height - the whole waveform then renders as
 * completely blank with no error, exactly as if no data were available at
 * all, for as long as that stale buffer remains in use (until the next
 * track load re-fetches fresh data and - by chance - no longer includes
 * the bad sample).
 *
 * A high percentile of the data is used as the scaling reference instead of
 * the strict maximum: for normal, outlier-free data (the overwhelming
 * common case) this is numerically indistinguishable from the true max, so
 * everyday rendering is unaffected - but it no longer lets a handful of
 * freak values anywhere in a 10,000+-sample track dictate the scale for
 * everything else. Bar heights are expected to be separately clamped to
 * the canvas/bitmap height by each caller, so any value that does exceed
 * this reference still renders at full height rather than overflowing.
 */
const OUTLIER_RESISTANT_PERCENTILE = 0.995;

/**
 * Computes a scaling reference from one or more parallel height arrays
 * (e.g. low/mid/high band heights, combined). Always at least 1 to avoid
 * division by zero for all-silent data.
 */
export function robustMaxHeight(...valueArrays: (readonly number[])[]): number {
  const combined: number[] = [];
  for (const values of valueArrays) {
    for (const v of values) combined.push(v);
  }
  if (combined.length === 0) return 1;
  combined.sort((a, b) => a - b);
  const index = Math.min(combined.length - 1, Math.floor(combined.length * OUTLIER_RESISTANT_PERCENTILE));
  return Math.max(1, combined[index]);
}
