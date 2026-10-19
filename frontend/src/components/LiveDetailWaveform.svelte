<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api } from '../stores/api';
  import { activeProfile } from '../stores/layout';
  import type { PlayerState } from '../stores/types';
  import { robustMaxHeight } from '../widgets/waveformScale';

  export let player: PlayerState | null;
  export let height = 120;
  // CDJ-3000-style "WAVE ZOOM": the detail waveform window is sized by how
  // many beats are visible to the *right* of the playhead (i.e. how far
  // into the future the display looks), with the same preset step values
  // the CDJ-3000 itself offers - 16 (most zoomed in, maximum detail) up to
  // 192 (most zoomed out, shows overall structure/phrasing).
  export let zoomBeats = 48;

  let canvas: HTMLCanvasElement;
  let container: HTMLDivElement;
  let waveType = 'NONE';
  let available = false;

  // Position reconciliation tuning (see the `$: if (player)` block below):
  // real Pro DJ Link hardware's reported position naturally jitters by a
  // few tens of milliseconds from one update to the next (beat-grid
  // interpolation plus normal network/WebSocket delivery timing variance).
  // Hard-resyncing the client-side rAF extrapolation to every single one of
  // those tiny corrections (as this used to do unconditionally) made the
  // playhead visibly stutter forward and backward in lock-step with that
  // jitter - never visible with the simulator's perfectly smooth synthetic
  // position, but clearly noticeable against real hardware.
  //
  // `HARD_RESYNC_THRESHOLD_MS`: a disagreement at least this large can only
  // be a genuine seek/jump (far beyond normal jitter), so snap immediately.
  // `SOFT_CORRECTION_FACTOR`: for anything smaller, nudge the extrapolation
  // anchor by only this fraction of the disagreement on each update, rather
  // than either fully snapping to it (still perceptible, see above) or
  // ignoring it outright (which would let small client/hardware clock-rate
  // differences accumulate without bound over a long track). User-
  // configurable (Settings -> Display -> "Waveform Smoothness") since the
  // right smooth-vs-precise balance depends on the specific hardware/
  // network's jitter characteristics - reactive so it updates live without
  // needing to reopen the Live View.
  //
  // `DEAD_ZONE_MS`: disagreements smaller than this are *pure* jitter noise
  // (confirmed against a real hardware session - even the soft correction
  // below still produced a faint residual stutter purely from continuously
  // nudging toward every tiny noise fluctuation) - these are now ignored
  // completely rather than corrected at all, since they are far too small
  // to matter over the visible time window at any zoom level. Tuned toward
  // a perfectly calm display over split-second seek responsiveness, per
  // explicit preference - a real seek is still caught well within a
  // fraction of a second by the hard-resync threshold below, just not
  // instantaneously.
  const DEAD_ZONE_MS = 120;
  const HARD_RESYNC_THRESHOLD_MS = 600;
  $: SOFT_CORRECTION_FACTOR = $activeProfile.waveformSmoothingFactor;

  // The fetched buffer always spans the *entire* track (0..durationMs),
  // fetched exactly once per track load - never on a timer. This mirrors
  // how `beat-link-trigger`'s own `WaveformDetailComponent` works: waveform
  // *data* only changes when the track changes, so repeatedly re-fetching
  // it while playing serves no purpose other than creating a race between
  // out-of-order network responses, which was the actual cause of the
  // flicker/"jumps back" previously seen on whichever deck was most active.
  // Only the *position* needs to update smoothly, and that is already
  // delivered continuously via the reactive `player` prop (WebSocket) and
  // extrapolated client-side below - no additional network round-trip
  // needed for that at all.
  let bufferHeights: number[] = [];
  let bufferColors: string[] | null = null;
  // Populated instead of bufferHeights/bufferColors for a CDJ-3000's native
  // "three-band" waveform style - see `buildWaveformBitmap`'s three-band
  // branch for how these are rendered.
  let bufferLowHeights: number[] | null = null;
  let bufferMidHeights: number[] | null = null;
  let bufferHighHeights: number[] | null = null;
  // Computed once per fetched buffer (not per animation frame) so the bar
  // scaling stays stable while scrolling - recomputing the max from only the
  // currently *visible* slice every frame made the whole waveform visibly
  // "breathe"/flicker in intensity as louder/quieter peaks scrolled in and
  // out of view.
  let bufferMaxHeight = 1;
  // Authoritative, exact milliseconds-per-column for the fetched buffer,
  // reported directly by the backend (see `WaveformResponse.columnIntervalMs`'s
  // doc comment) - deliberately NOT derived from `player.trackDurationMs`,
  // which Pioneer only reports rounded to whole seconds and previously
  // caused the whole waveform image to be subtly mis-scaled in time (it
  // would drift out of sync with playback - "appearing a beat or two
  // early" - while the beat grid, which uses real per-beat timestamps
  // instead, stayed perfectly aligned).
  //
  // After the backend's raw per-sample interval, this may be *coarsened*
  // (and the corresponding height/color arrays downsampled to match) if the
  // raw column count would exceed `MAX_BITMAP_COLUMNS` - see
  // `downsampleForBitmap`'s doc comment for why.
  let bufferColumnIntervalMs = 0;

  // The entire track's waveform, pre-rendered *once* per track load into an
  // offscreen bitmap at native sample resolution (one bitmap column per
  // buffer sample) - like a single long strip image. Every animation frame
  // then simply `drawImage`s whatever time-window is currently visible out
  // of this already-finished bitmap, scaled to fit the viewport, rather
  // than re-sampling/re-smoothing the raw buffer from scratch 60 times a
  // second. This eliminates the remaining "wabern"/boiling shimmer at its
  // root rather than just damping it: that shimmer was caused by exactly
  // *which* source sample won each on-screen column's peak-hold flipping
  // between adjacent samples as the sub-pixel scroll position changed every
  // single frame. With a pre-rendered bitmap there is no more per-frame
  // resampling at all - the pixel data itself is completely static, and
  // only the viewport's (sub-pixel) offset into it changes, which the
  // canvas's own image scaling interpolates smoothly with no jitter.
  //
  // Because the bitmap's x-axis is plain track-time (milliseconds), not
  // beats or screen pixels, it stays perfectly valid regardless of the
  // current pitch/tempo (which only changes how fast the viewport scrolls
  // across it, not the bitmap's content) and regardless of the current
  // "Wave Zoom" level (which only changes how much of the bitmap's time
  // range is scaled into the viewport at once) - one bitmap serves every
  // zoom level and every playback rate without re-rendering.
  let waveformBitmap: HTMLCanvasElement | null = null;

  // Beat grid (beat timestamps + position within bar), fetched once per
  // track load alongside the waveform buffer and rendered as tick marks in
  // a thin strip above the waveform - like a CDJ's beat/phrase markers.
  let beatTimesMs: number[] = [];
  let beatsWithinBar: number[] = [];
  let beatGridAvailable = false;

  // Last known authoritative player state, used to extrapolate the *current*
  // playback position between WebSocket updates (requirements specification §22: the UI
  // must not be limited to the network's update rate for smooth motion).
  let lastKnownPositionMs = 0;
  let lastKnownAtWallClock = 0;
  let lastPlaying = false;
  let lastPitchPercent = 0;
  let lastTrackId: number | null | undefined = undefined;

  let rafHandle: number;
  // Guards against a slower-arriving fetch for an *older* track resolving
  // after a newer track-change fetch already completed.
  let fetchSeq = 0;

  // Device-pixel-aware canvas sizing: the backing bitmap is sized to the
  // *actual physical* pixel width/height of the on-screen element (CSS size
  // times devicePixelRatio), not a fixed constant, and one bar is drawn per
  // physical pixel column. This keeps the waveform crisp on HiDPI/Retina
  // displays instead of a low-res bitmap being blurrily upscaled by the
  // browser, and also means more detail is visible the wider the window is.
  let containerWidth = 0;
  let containerHeight = 0;
  const devicePixelRatio = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
  $: physicalWidth = Math.max(50, Math.round(containerWidth * devicePixelRatio));
  $: physicalHeight = Math.max(10, Math.round((containerHeight || height) * devicePixelRatio));

  // Window width in track-time is derived from the zoom level (in beats)
  // and the track's current effective BPM - exactly like the CDJ-3000's
  // "WAVE ZOOM", rather than a fixed number of seconds, so the amount of
  // musical content shown stays consistent across different tempos.
  // `zoomBeats` is the number of beats visible to the *right* of the
  // playhead (i.e. how far into the future the CDJ shows) - the portion to
  // the left (already played) is derived from the fixed playhead position
  // below so the already-played/upcoming ratio stays constant across zoom
  // levels.
  $: bpm = player?.effectiveBpm || player?.originalBpm || 120;
  $: beatIntervalMs = 60_000 / bpm;
  // Playhead fixed at 20% of the width, representing the track's current
  // playback position (clearly left of center, like a CDJ detail display
  // that shows more upcoming waveform than already-played).
  $: playheadFraction = 0.2;
  $: afterMs = zoomBeats * beatIntervalMs;
  $: beforeMs = afterMs * (playheadFraction / (1 - playheadFraction));

  // Track every incoming player update so extrapolation always starts from
  // the freshest known-good anchor point - hard-resyncing only when
  // genuinely warranted (track change, play/pause/pitch state change, or a
  // disagreement large enough to only be a real seek/jump), and otherwise
  // gently nudging the anchor toward the reported position (see
  // `HARD_RESYNC_THRESHOLD_MS` / `SOFT_CORRECTION_FACTOR` above).
  $: if (player) {
    const reportedPositionMs = player.playbackPositionMs ?? 0;
    if (player.rekordboxTrackId !== lastTrackId) {
      lastTrackId = player.rekordboxTrackId;
      bufferHeights = [];
      bufferColors = null;
      available = false;
      beatTimesMs = [];
      beatsWithinBar = [];
      beatGridAvailable = false;
      waveformBitmap = null; // don't show the old track's pre-rendered strip while the new one loads
      fetchFullTrack(); // fetch once, immediately, for the newly loaded track
      fetchBeatGrid();
      // No valid previous extrapolation to reconcile against - hard resync.
      lastKnownPositionMs = reportedPositionMs;
      lastKnownAtWallClock = Date.now();
    } else if (player.playing !== lastPlaying || Math.abs((player.pitchPercent ?? 0) - lastPitchPercent) > 0.01) {
      // Play/pause toggled or pitch changed: the old extrapolation basis no
      // longer applies, so resync immediately rather than waiting for the
      // gentler reconciliation below to (eventually) notice the drift.
      lastKnownPositionMs = reportedPositionMs;
      lastKnownAtWallClock = Date.now();
    } else {
      const error = reportedPositionMs - predictedPositionMs();
      if (Math.abs(error) > HARD_RESYNC_THRESHOLD_MS) {
        // A genuine seek/jump happened - snap immediately rather than
        // slowly crawling toward the new position over several seconds.
        lastKnownPositionMs = reportedPositionMs;
        lastKnownAtWallClock = Date.now();
      } else if (Math.abs(error) > DEAD_ZONE_MS) {
        // Gentle, continuous reconciliation for genuine small drift (but
        // not pure noise, see `DEAD_ZONE_MS`) - nudging the anchor by only
        // a small fraction of the disagreement each update means any one
        // correction is too small to notice, while still keeping the two
        // in sync over time (rather than letting real clock-rate
        // differences between client and hardware accumulate unbounded
        // over a long track).
        lastKnownPositionMs = predictedPositionMs() + error * SOFT_CORRECTION_FACTOR;
        lastKnownAtWallClock = Date.now();
      }
      // else: within the dead zone - pure jitter, deliberately left
      // completely uncorrected (see `DEAD_ZONE_MS`).
    }
    lastPlaying = player.playing;
    lastPitchPercent = player.pitchPercent ?? 0;
  }

  function predictedPositionMs(): number {
    if (!lastPlaying) return lastKnownPositionMs;
    const elapsed = Date.now() - lastKnownAtWallClock;
    const playbackRate = 1 + lastPitchPercent / 100;
    return lastKnownPositionMs + elapsed * playbackRate;
  }

  let fullTrackRetryHandle: ReturnType<typeof setTimeout> | null = null;

  async function fetchFullTrack() {
    if (fullTrackRetryHandle) {
      clearTimeout(fullTrackRetryHandle);
      fullTrackRetryHandle = null;
    }
    if (!player || !player.connected) {
      available = false;
      return;
    }
    const seq = ++fetchSeq;
    try {
      const response = await api.getWaveformDetailFull(player.deviceNumber);
      // A fetch for an even newer track may have already resolved while
      // this one was in flight; discard this response rather than
      // overwrite the buffer with stale/wrong-track data.
      if (seq !== fetchSeq) return;
      waveType = response.type;
      available = response.available;
      if (response.available) {
        const downsampled = downsampleForBitmap(
          response.heights,
          response.colors,
          response.lowHeights ?? null,
          response.midHeights ?? null,
          response.highHeights ?? null,
          response.columnIntervalMs,
        );
        bufferHeights = downsampled.heights;
        bufferColors = downsampled.colors;
        bufferLowHeights = downsampled.lowHeights;
        bufferMidHeights = downsampled.midHeights;
        bufferHighHeights = downsampled.highHeights;
        bufferColumnIntervalMs = downsampled.columnIntervalMs;
        bufferMaxHeight =
          waveType === 'THREE_BAND'
            ? robustMaxHeight(bufferLowHeights ?? [], bufferMidHeights ?? [], bufferHighHeights ?? [])
            : robustMaxHeight(bufferHeights);
        waveformBitmap = buildWaveformBitmap(
          bufferHeights,
          bufferColors,
          bufferMaxHeight,
          waveType,
          bufferLowHeights,
          bufferMidHeights,
          bufferHighHeights,
        );
      } else {
        // Real hardware can briefly not have the waveform cached yet right
        // after a track loads (metadata/waveform/beat-grid are independent,
        // asynchronously-arriving subsystems) - retry with backoff until it
        // becomes available, rather than giving up permanently and forcing
        // the user to leave and re-enter the Live View to get another
        // chance (exactly what was previously happening here, unlike the
        // Monitor tab's overview waveform, which already retried).
        bufferHeights = [];
        bufferLowHeights = null;
        bufferMidHeights = null;
        bufferHighHeights = null;
        bufferColors = null;
        waveformBitmap = null;
        fullTrackRetryHandle = setTimeout(fetchFullTrack, 1000);
      }
    } catch (e) {
      // Network/backend hiccups must never break the rest of the Live View
      // (requirements specification §23) - retry rather than leaving the view stuck.
      if (seq !== fetchSeq) return;
      fullTrackRetryHandle = setTimeout(fetchFullTrack, 1000);
    }
  }

  let beatGridFetchSeq = 0;
  let beatGridRetryHandle: ReturnType<typeof setTimeout> | null = null;

  async function fetchBeatGrid() {
    if (beatGridRetryHandle) {
      clearTimeout(beatGridRetryHandle);
      beatGridRetryHandle = null;
    }
    if (!player || !player.connected) {
      beatGridAvailable = false;
      return;
    }
    const seq = ++beatGridFetchSeq;
    try {
      const response = await api.getBeatGrid(player.deviceNumber);
      if (seq !== beatGridFetchSeq) return;
      beatGridAvailable = response.available;
      beatTimesMs = response.available ? response.beatTimesMs : [];
      beatsWithinBar = response.available ? response.beatsWithinBar : [];
      if (!response.available) {
        beatGridRetryHandle = setTimeout(fetchBeatGrid, 1000);
      }
    } catch (e) {
      // Same resilience principle as the waveform fetch - a missing beat
      // grid must never break the rest of the Live View, and must retry
      // rather than getting stuck until the view is reopened.
      if (seq !== beatGridFetchSeq) return;
      beatGridRetryHandle = setTimeout(fetchBeatGrid, 1000);
    }
  }

  /**
   * Beat-link's own documented `WaveformFinder.ThreeBandLayer` colors (dark
   * blue/amber/white for low/mid/high) - kept in sync with the fixed colors
   * defined there, since the backend only sends plain per-band *heights*,
   * never colors, for three-band waveforms (their colors are fixed per
   * band, not per-sample, unlike RGB).
   */
  const THREE_BAND_COLORS = { low: '#2053d9', mid: '#f2aa3c', high: '#ffffff' };

  /**
   * Applies the same fixed smoothing kernel used for single-value (RGB/
   * Classic) waveforms to one three-band layer's height array.
   */
  function smooth(heights: number[], i: number, radius: number): number {
    let sum = 0;
    let weightSum = 0;
    const lastIndex = heights.length - 1;
    for (let k = -radius; k <= radius; k++) {
      const idx = i + k;
      if (idx < 0 || idx > lastIndex) continue;
      const weight = radius + 1 - Math.abs(k);
      sum += heights[idx] * weight;
      weightSum += weight;
    }
    return weightSum > 0 ? sum / weightSum : heights[i];
  }

  /**
   * Browsers using Chromium's Skia 2D canvas backend (Chrome, Edge, and the
   * Chromium-based kiosk browser used on the Raspberry Pi deployment) hard-
   * cap a single `<canvas>` dimension at 65535px (2^16 - 1) - verified
   * directly: a canvas created with `width = 65536` silently produces a
   * fully blank/transparent backing store with no exception, console
   * warning, or any other signal that anything went wrong, while
   * `width = 65535` renders perfectly normally. `buildWaveformBitmap`
   * creates one offscreen bitmap column per raw waveform sample (at the
   * backend's native resolution, ~6.7ms/column) - so any track long enough
   * to need more than 65535 columns (roughly 7m17s at that resolution)
   * silently rendered as a *completely invisible* detail waveform on those
   * browsers, while the real hardware/backend data was entirely fine and
   * the exact same track displayed correctly in Safari/WebKit (whose
   * canvas dimension limit is far higher) - this was the actual root cause
   * of the detail waveform intermittently "just disappearing" depending on
   * which browser displayed it and which track happened to be loaded.
   *
   * [downsampleForBitmap] keeps the bitmap's column count safely under
   * that limit regardless of track length by grouping multiple raw samples
   * into one bitmap column whenever needed, taking the *peak* (loudest)
   * value within each group - like real DJ waveform displays do when
   * zoomed out, this preserves the loudest transients rather than
   * smearing them out via averaging. [columnIntervalMs] is scaled up by
   * the same grouping factor so the caller's time-to-bitmap-column mapping
   * (see `draw()`'s `msToIndex`) stays correct. For tracks short enough to
   * stay under the limit (the overwhelming majority), this is a no-op that
   * returns the input arrays unchanged.
   */
  const MAX_BITMAP_COLUMNS = 60_000;

  function downsampleForBitmap(
    heights: number[],
    colors: string[] | null,
    lowHeights: number[] | null,
    midHeights: number[] | null,
    highHeights: number[] | null,
    columnIntervalMs: number,
  ): {
    heights: number[];
    colors: string[] | null;
    lowHeights: number[] | null;
    midHeights: number[] | null;
    highHeights: number[] | null;
    columnIntervalMs: number;
  } {
    const columnCount = heights.length;
    if (columnCount <= MAX_BITMAP_COLUMNS) {
      return { heights, colors, lowHeights, midHeights, highHeights, columnIntervalMs };
    }
    const groupSize = Math.ceil(columnCount / MAX_BITMAP_COLUMNS);
    const outCount = Math.ceil(columnCount / groupSize);
    const outHeights = new Array<number>(outCount);
    const outColors = colors ? new Array<string>(outCount) : null;
    const outLow = lowHeights ? new Array<number>(outCount) : null;
    const outMid = midHeights ? new Array<number>(outCount) : null;
    const outHigh = highHeights ? new Array<number>(outCount) : null;
    for (let o = 0; o < outCount; o++) {
      const start = o * groupSize;
      const end = Math.min(columnCount, start + groupSize);
      let peakIndex = start;
      let peakValue = heights[start];
      for (let i = start + 1; i < end; i++) {
        if (heights[i] > peakValue) {
          peakValue = heights[i];
          peakIndex = i;
        }
      }
      outHeights[o] = peakValue;
      if (outColors) outColors[o] = colors![peakIndex];
      if (outLow) {
        let peak = lowHeights![start];
        for (let i = start + 1; i < end; i++) if (lowHeights![i] > peak) peak = lowHeights![i];
        outLow[o] = peak;
      }
      if (outMid) {
        let peak = midHeights![start];
        for (let i = start + 1; i < end; i++) if (midHeights![i] > peak) peak = midHeights![i];
        outMid[o] = peak;
      }
      if (outHigh) {
        let peak = highHeights![start];
        for (let i = start + 1; i < end; i++) if (highHeights![i] > peak) peak = highHeights![i];
        outHigh[o] = peak;
      }
    }
    return {
      heights: outHeights,
      colors: outColors,
      lowHeights: outLow,
      midHeights: outMid,
      highHeights: outHigh,
      columnIntervalMs: columnIntervalMs * groupSize,
    };
  }

  /**
   * Pre-renders the *entire* track's waveform into an offscreen bitmap, one
   * bitmap column per native buffer sample (no downsampling), with a tiny
   * fixed smoothing kernel across neighboring samples applied once here
   * (not per frame) to turn the Pioneer detail-waveform format's inherent
   * 32-level amplitude quantization into a smooth-looking curve rather than
   * a visible staircase - the same one-time cost standard DJ software pays
   * for this exact data format. The bitmap's background is left fully
   * transparent so drawing it onto the already-painted destination canvas
   * (see `draw()`) only adds the bars themselves.
   *
   * For a CDJ-3000's native "three-band" style ([lowHeights]/[midHeights]/
   * [highHeights] instead of [heights]/[colors]), three overlapping,
   * fixed-colored bars are drawn per column instead of one - back-to-front
   * in high/mid/low order. This single fixed z-order correctly reproduces
   * the real banded look for both of the two different value shapes
   * beat-link returns: the overview preview's band heights are
   * *cumulative* (high already includes mid+low, so drawing it widest/
   * first and progressively narrower layers on top naturally reveals each
   * band), while the detail waveform's band heights are independent
   * per-band values (where the same fixed order is instead a deliberate,
   * reasonable compositing choice approximating the same official color
   * scheme, not exact alpha-blended math) - see beat-link's own
   * `ThreeBandLayer` documentation.
   */
  function buildWaveformBitmap(
    heights: number[],
    colors: string[] | null,
    maxHeight: number,
    type: string,
    lowHeights: number[] | null = null,
    midHeights: number[] | null = null,
    highHeights: number[] | null = null,
  ): HTMLCanvasElement | null {
    const isThreeBand = type === 'THREE_BAND' && lowHeights && midHeights && highHeights;
    const columnCount = isThreeBand ? lowHeights!.length : heights.length;
    if (columnCount === 0) return null;
    const bitmap = document.createElement('canvas');
    bitmap.width = columnCount;
    const bitmapHeight = 256;
    bitmap.height = bitmapHeight;
    const ctx = bitmap.getContext('2d');
    if (!ctx) return null;

    const radius = 2;
    if (isThreeBand) {
      for (let i = 0; i < columnCount; i++) {
        const highBar = Math.min(bitmapHeight, (smooth(highHeights!, i, radius) / maxHeight) * bitmapHeight);
        const midBar = Math.min(bitmapHeight, (smooth(midHeights!, i, radius) / maxHeight) * bitmapHeight);
        const lowBar = Math.min(bitmapHeight, (smooth(lowHeights!, i, radius) / maxHeight) * bitmapHeight);
        ctx.fillStyle = THREE_BAND_COLORS.high;
        ctx.fillRect(i, (bitmapHeight - highBar) / 2, 1, highBar);
        ctx.fillStyle = THREE_BAND_COLORS.mid;
        ctx.fillRect(i, (bitmapHeight - midBar) / 2, 1, midBar);
        ctx.fillStyle = THREE_BAND_COLORS.low;
        ctx.fillRect(i, (bitmapHeight - lowBar) / 2, 1, lowBar);
      }
      return bitmap;
    }

    const fallbackColor = type === 'RGB' ? '#4fc3f7' : '#bbbbbb';
    for (let i = 0; i < heights.length; i++) {
      const smoothedHeight = smooth(heights, i, radius);
      const barHeight = Math.min(bitmapHeight, (smoothedHeight / maxHeight) * bitmapHeight);
      ctx.fillStyle = colors?.[i] ?? fallbackColor;
      ctx.fillRect(i, (bitmapHeight - barHeight) / 2, 1, barHeight);
    }
    return bitmap;
  }

  // Thin strip at the top of the canvas reserved for the beat grid ticks
  // (requirements specification: beat grid shown above the waveform per track, like a
  // CDJ's beat/phrase markers) - the waveform bars below it are shortened
  // accordingly so nothing overlaps.
  $: beatGridStripHeight = Math.round(16 * devicePixelRatio);

  function draw() {
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const width = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, width, h);
    ctx.fillStyle = '#0c0d10';
    ctx.fillRect(0, 0, width, h);

    const center = predictedPositionMs();
    const windowStart = center - beforeMs;
    const windowSpan = beforeMs + afterMs;
    const waveformTop = beatGridStripHeight;
    const waveformHeight = h - beatGridStripHeight;

    if (waveformBitmap && available && bufferColumnIntervalMs > 0) {
      // Map the current visible time-window onto the pre-rendered bitmap's
      // x-axis (plain track-time), then let the canvas's own image scaling
      // draw+interpolate that slice into the viewport - no per-frame
      // resampling of the raw buffer happens here at all anymore, which is
      // what eliminates the "wabern" shimmer at its root (see
      // `buildWaveformBitmap`/`waveformBitmap` above for the full
      // rationale).
      const bitmapWidth = waveformBitmap.width;
      const bitmapHeight = waveformBitmap.height;
      const msToIndex = 1 / bufferColumnIntervalMs;
      const srcXStart = windowStart * msToIndex;
      const srcXEnd = (windowStart + windowSpan) * msToIndex;
      // Clip the source range to the bitmap's valid bounds, shrinking the
      // destination rect correspondingly - this is what renders "silence"
      // (just the plain background) before the track start / after its
      // end, instead of stretching or wrapping the edge pixels.
      const clippedSrcXStart = Math.max(0, srcXStart);
      const clippedSrcXEnd = Math.min(bitmapWidth, srcXEnd);
      if (clippedSrcXEnd > clippedSrcXStart && srcXEnd > srcXStart) {
        const srcWidth = clippedSrcXEnd - clippedSrcXStart;
        const destXStart = ((clippedSrcXStart - srcXStart) / (srcXEnd - srcXStart)) * width;
        const destXEnd = ((clippedSrcXEnd - srcXStart) / (srcXEnd - srcXStart)) * width;
        ctx.imageSmoothingEnabled = true;
        ctx.drawImage(
          waveformBitmap,
          clippedSrcXStart,
          0,
          srcWidth,
          bitmapHeight,
          destXStart,
          waveformTop,
          destXEnd - destXStart,
          waveformHeight,
        );
      }
    } else {
      ctx.fillStyle = '#444';
      ctx.font = '12px sans-serif';
      ctx.fillText(player ? 'No Waveform' : 'Not connected', 10, waveformTop + waveformHeight / 2 + 4);
    }

    if (beatGridAvailable && beatTimesMs.length > 0) {
      drawBeatGrid(ctx, width, windowStart, windowSpan);
    }

    if (player?.looping && player.loopStartMs != null && player.loopEndMs != null) {
      drawLoopRegion(ctx, width, waveformTop, waveformHeight, windowStart, windowSpan, player.loopStartMs, player.loopEndMs);
    }

    // Playhead, deliberately shifted left of center (20% from the left)
    // rather than the usual centered CDJ display. Drawn as a filled white
    // bar with a black outline (rather than a thin colored line) so it
    // stays clearly visible against any waveform color behind it.
    const playheadX = playheadFraction * width;
    const playheadWidth = Math.max(3, Math.round(3 * devicePixelRatio));
    const borderWidth = Math.max(1, Math.round(1 * devicePixelRatio));
    ctx.fillStyle = '#000000';
    ctx.fillRect(playheadX - playheadWidth / 2 - borderWidth, 0, playheadWidth + borderWidth * 2, h);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(playheadX - playheadWidth / 2, 0, playheadWidth, h);
  }

  /** Draws beat grid tick marks in the reserved top strip - taller/brighter for downbeats. */
  function drawBeatGrid(ctx: CanvasRenderingContext2D, width: number, windowStart: number, windowSpan: number) {
    ctx.lineWidth = Math.max(1, Math.round(devicePixelRatio));
    for (let i = 0; i < beatTimesMs.length; i++) {
      const t = beatTimesMs[i];
      if (t < windowStart) continue;
      if (t > windowStart + windowSpan) break;
      const x = ((t - windowStart) / windowSpan) * width;
      const isDownbeat = beatsWithinBar[i] === 1;
      ctx.strokeStyle = isDownbeat ? '#ffd54f' : '#777777';
      const tickHeight = isDownbeat ? beatGridStripHeight : beatGridStripHeight * 0.5;
      ctx.beginPath();
      ctx.moveTo(x, beatGridStripHeight - tickHeight);
      ctx.lineTo(x, beatGridStripHeight);
      ctx.stroke();
    }
  }

  /**
   * Highlights the currently active loop's region directly on the
   * waveform - a translucent fill between its measured start/end bounds
   * (see `PlayerState.loopStartMs`/`loopEndMs`'s doc comment for how these
   * are derived from real, observed hardware position jumps) with a bright
   * solid line marking each boundary, the same visual language real DJ
   * hardware/software uses for active loop regions. Also marks each
   * boundary with a downward-pointing right-triangle flag in the beat grid
   * strip above, so the loop's start/end stand out even when the strip
   * itself is busy with regular beat ticks.
   */
  function drawLoopRegion(
    ctx: CanvasRenderingContext2D,
    width: number,
    top: number,
    height: number,
    windowStart: number,
    windowSpan: number,
    loopStartMs: number,
    loopEndMs: number,
  ) {
    const startX = ((loopStartMs - windowStart) / windowSpan) * width;
    const endX = ((loopEndMs - windowStart) / windowSpan) * width;
    // Entirely outside the visible window - nothing to draw.
    if (endX < 0 || startX > width) return;
    const clippedStartX = Math.max(0, startX);
    const clippedEndX = Math.min(width, endX);

    ctx.fillStyle = 'rgba(255, 235, 59, 0.18)';
    ctx.fillRect(clippedStartX, top, clippedEndX - clippedStartX, height);

    ctx.strokeStyle = '#ffeb3b';
    ctx.lineWidth = Math.max(1, Math.round(devicePixelRatio));
    if (startX >= 0 && startX <= width) {
      ctx.beginPath();
      ctx.moveTo(startX, top);
      ctx.lineTo(startX, top + height);
      ctx.stroke();
    }
    if (endX >= 0 && endX <= width) {
      ctx.beginPath();
      ctx.moveTo(endX, top);
      ctx.lineTo(endX, top + height);
      ctx.stroke();
    }

    // Right-triangle "flag" markers in the beat grid strip, tip pointing
    // down at the exact boundary - the loop-start flag's flat edge extends
    // rightward (into the loop), the loop-end flag's extends leftward (back
    // into the loop), so together they visually bracket the region.
    const flagSize = beatGridStripHeight * 0.7;
    if (startX >= 0 && startX <= width) {
      drawLoopFlag(ctx, startX, flagSize, 'right');
    }
    if (endX >= 0 && endX <= width) {
      drawLoopFlag(ctx, endX, flagSize, 'left');
    }
  }

  /** One downward-pointing right-triangle loop boundary flag, drawn at the top of the beat grid strip. */
  function drawLoopFlag(ctx: CanvasRenderingContext2D, x: number, size: number, direction: 'left' | 'right') {
    const otherX = direction === 'right' ? x + size : x - size;
    ctx.fillStyle = '#ffeb3b';
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(otherX, 0);
    ctx.lineTo(x, size);
    ctx.closePath();
    ctx.fill();
  }

  function animate() {
    draw();
    rafHandle = requestAnimationFrame(animate);
  }

  let resizeObserver: ResizeObserver | undefined;

  onMount(() => {
    fetchFullTrack();
    fetchBeatGrid();
    rafHandle = requestAnimationFrame(animate);
    if (container) {
      containerWidth = container.clientWidth;
      containerHeight = container.clientHeight;
      resizeObserver = new ResizeObserver((entries) => {
        containerWidth = entries[0]?.contentRect.width ?? containerWidth;
        containerHeight = entries[0]?.contentRect.height ?? containerHeight;
      });
      resizeObserver.observe(container);
    }
  });

  onDestroy(() => {
    cancelAnimationFrame(rafHandle);
    resizeObserver?.disconnect();
    if (fullTrackRetryHandle) clearTimeout(fullTrackRetryHandle);
    if (beatGridRetryHandle) clearTimeout(beatGridRetryHandle);
  });

  // Resizing the backing bitmap clears its contents, so this must only run
  // when the physical pixel size actually changes, not on every frame.
  $: if (canvas && (canvas.width !== physicalWidth || canvas.height !== physicalHeight)) {
    canvas.width = physicalWidth;
    canvas.height = physicalHeight;
  }
</script>

<div class="detail-row" bind:this={container}>
  <canvas bind:this={canvas} width={physicalWidth} height={physicalHeight}></canvas>
</div>

<style>
  .detail-row {
    width: 100%;
    line-height: 0;
  }
  canvas {
    width: 100%;
    height: 100%;
    display: block;
  }
</style>
