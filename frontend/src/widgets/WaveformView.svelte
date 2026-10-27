<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api } from '../stores/api';
  import { eqGains } from '../stores/eq';
  import { applyRgbEq, eqChangesBrightness } from '../stores/eqRender';
  import type { PlayerState } from '../stores/types';
  import { robustMaxHeight } from './waveformScale';

  export let player: PlayerState;
  export let height = 56;

  let container: HTMLDivElement;
  let canvas: HTMLCanvasElement;
  let heights: number[] = [];
  let colors: string[] | null = null;
  let lowHeights: number[] | null = null;
  let midHeights: number[] | null = null;
  let highHeights: number[] | null = null;
  let waveType = 'NONE';
  let pollHandle: ReturnType<typeof setTimeout> | null = null;
  let lastTrackId: number | null = null;
  // Guards against an older fetch (e.g. a slow retry-until-available poll)
  // resolving after a newer one already succeeded, which previously caused
  // visible flicker (the overview waveform briefly "reappearing"/changing
  // for no reason) on whichever deck was fetched most often.
  let fetchSeq = 0;

  // Device-pixel-aware canvas sizing (same technique as the Live View's
  // detail waveform): the backing bitmap is sized to the element's *actual
  // physical* pixel dimensions (CSS size times devicePixelRatio) rather
  // than a fixed low-resolution constant, so the waveform stays crisp -
  // both horizontally and vertically - on HiDPI/Retina displays instead of
  // being blurrily upscaled by the browser.
  let containerWidth = 0;
  const devicePixelRatio = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
  $: physicalWidth = Math.max(50, Math.round(containerWidth * devicePixelRatio));
  $: physicalHeight = Math.max(10, Math.round(height * devicePixelRatio));

  async function loadWaveform() {
    const seq = ++fetchSeq;
    try {
      const response = await api.getWaveform(player.deviceNumber);
      if (seq !== fetchSeq) return;
      waveType = response.type;
      heights = response.available ? response.heights : [];
      colors = response.available ? response.colors : null;
      lowHeights = response.available ? (response.lowHeights ?? null) : null;
      midHeights = response.available ? (response.midHeights ?? null) : null;
      highHeights = response.available ? (response.highHeights ?? null) : null;
      // The waveform for a given track never changes, so there is no need
      // to keep polling once it has loaded successfully (coding guideline
      // #13) - only real hardware can briefly have "not yet cached" data,
      // in which case we retry with backoff until it becomes available.
      if (!response.available) {
        pollHandle = setTimeout(loadWaveform, 1000);
      }
    } catch (e) {
      // Waveform fetch failures must never break the rest of the UI
      // (requirements specification §23).
      if (seq !== fetchSeq) return;
      heights = [];
      colors = null;
      lowHeights = null;
      midHeights = null;
      highHeights = null;
      pollHandle = setTimeout(loadWaveform, 1000);
    }
  }

  function draw() {
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const width = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, width, h);
    ctx.fillStyle = '#1b1c22';
    ctx.fillRect(0, 0, width, h);

    // Mixer EQ -> brightness of the low/mid/high parts (see stores/eq.ts); no
    // live mixer data means full brightness.
    const gains = $eqGains[player.deviceNumber];
    let applyRgbGains = false;
    let drawBars: ((target: CanvasRenderingContext2D) => void) | null = null;

    const isThreeBand = waveType === 'THREE_BAND' && lowHeights && midHeights && highHeights;
    if (isThreeBand) {
      const columnCount = lowHeights!.length;
      const barWidth = width / columnCount;
      const maxHeight = robustMaxHeight(lowHeights!, midHeights!, highHeights!);
      const bandColors = { low: '#2053d9', mid: '#f2aa3c', high: '#ffffff' };
      for (let i = 0; i < columnCount; i++) {
        const x = i * barWidth;
        const w = Math.max(1, barWidth - 0.5);
        const highBar = Math.min(h, (highHeights![i] / maxHeight) * h);
        ctx.globalAlpha = gains?.high ?? 1;
        ctx.fillStyle = bandColors.high;
        ctx.fillRect(x, h - highBar, w, highBar);
        const midBar = Math.min(h, (midHeights![i] / maxHeight) * h);
        ctx.globalAlpha = gains?.mid ?? 1;
        ctx.fillStyle = bandColors.mid;
        ctx.fillRect(x, h - midBar, w, midBar);
        const lowBar = Math.min(h, (lowHeights![i] / maxHeight) * h);
        ctx.globalAlpha = gains?.low ?? 1;
        ctx.fillStyle = bandColors.low;
        ctx.fillRect(x, h - lowBar, w, lowBar);
      }
      ctx.globalAlpha = 1;
    } else if (heights.length > 0) {
      const barWidth = width / heights.length;
      const fallbackColor = waveType === 'RGB' ? '#4fc3f7' : '#bbbbbb';
      // Classic (monochrome) heights are 0-31; real RGB color waveforms use
      // raw byte magnitudes (roughly 0-255) as their "height" value - auto
      // scaling by a robust (outlier-resistant) high percentile of the
      // observed values renders both correctly without hardcoding a
      // format-specific divisor (see `robustMaxHeight`'s doc comment for
      // why the strict maximum is avoided).
      const maxHeight = robustMaxHeight(heights);
      // Real per-segment RGB colors decoded from the actual Pro DJ Link
      // waveform data take priority (requirements specification §9.2); otherwise a flat
      // color indicates the current waveform mode.
      drawBars = (target) => {
        heights.forEach((barHeightValue, i) => {
          const barHeight = Math.min(h, (barHeightValue / maxHeight) * h);
          target.fillStyle = colors?.[i] ?? fallbackColor;
          target.fillRect(i * barWidth, h - barHeight, Math.max(1, barWidth - 0.5), barHeight);
        });
      };
      drawBars(ctx);
      applyRgbGains = waveType === 'RGB' && eqChangesBrightness(gains);
    } else {
      ctx.fillStyle = '#555';
      ctx.font = '11px sans-serif';
      ctx.fillText('No Waveform', 8, h / 2 + 4);
    }

    if (applyRgbGains && gains && drawBars) {
      // The RGB waveform's red/green/blue components stand for its low/mid/
      // high frequency content, so each band is dimmed (or boosted) separately.
      applyRgbEq(ctx, 0, 0, width, h, gains, drawBars);
    }

    // Playback cursor (requirements specification §9.4) - drawn as a filled bar with a
    // black outline so it stays clearly visible against any waveform color
    // behind it (plain thin colored lines were hard to spot).
    if (player.playbackPositionMs != null && player.trackDurationMs) {
      const fraction = Math.min(1, player.playbackPositionMs / player.trackDurationMs);
      const playheadX = fraction * width;
      const playheadWidth = Math.max(3, Math.round(3 * devicePixelRatio));
      const borderWidth = Math.max(1, Math.round(devicePixelRatio));
      ctx.fillStyle = '#000000';
      ctx.fillRect(playheadX - playheadWidth / 2 - borderWidth, 0, playheadWidth + borderWidth * 2, h);
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(playheadX - playheadWidth / 2, 0, playheadWidth, h);
    }
  }

  $: if (player.rekordboxTrackId !== lastTrackId) {
    lastTrackId = player.rekordboxTrackId;
    if (pollHandle) clearTimeout(pollHandle);
    loadWaveform();
  }

  $: {
    // Redraw whenever playback position, the physical canvas size, or the
    // configured waveform height (requirements specification §10.4) updates.
    player.playbackPositionMs;
    $eqGains;
    physicalWidth;
    physicalHeight;
    draw();
  }

  let resizeObserver: ResizeObserver | undefined;

  onMount(() => {
    loadWaveform();
    if (container) {
      containerWidth = container.clientWidth;
      resizeObserver = new ResizeObserver((entries) => {
        containerWidth = entries[0]?.contentRect.width ?? containerWidth;
      });
      resizeObserver.observe(container);
    }
  });

  onDestroy(() => {
    if (pollHandle) clearTimeout(pollHandle);
    resizeObserver?.disconnect();
  });

  // Resizing the backing bitmap clears its contents, so this must only run
  // when the physical pixel size actually changes, not on every frame.
  $: if (canvas && (canvas.width !== physicalWidth || canvas.height !== physicalHeight)) {
    canvas.width = physicalWidth;
    canvas.height = physicalHeight;
  }
</script>

<div class="waveform" bind:this={container} style:height={`${height}px`}>
  <canvas bind:this={canvas} width={physicalWidth} height={physicalHeight}></canvas>
  <span class="mode-badge">{waveType}</span>
</div>

<style>
  .waveform {
    position: relative;
    width: 100%;
  }
  canvas {
    width: 100%;
    height: 100%;
    border-radius: 4px;
    display: block;
  }
  .mode-badge {
    position: absolute;
    top: 2px;
    right: 6px;
    font-size: 9px;
    color: #999;
    background: rgba(0, 0, 0, 0.4);
    padding: 1px 4px;
    border-radius: 3px;
  }
</style>
