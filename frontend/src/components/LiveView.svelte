<script lang="ts">
  import type { MasterClockState, PlayerSlotConfig, PlayerState } from '../stores/types';
  import { resolveSlot } from '../stores/slotResolver';
  import WaveformView from '../widgets/WaveformView.svelte';
  import LiveDetailWaveform from './LiveDetailWaveform.svelte';

  export let players: PlayerState[];
  export let masterClock: MasterClockState;
  export let slots: PlayerSlotConfig[];
  export let inactiveDeckBrightnessPercent = 50;
  export let onExit: (() => void) | undefined = undefined;

  // CDJ-3000-style "WAVE ZOOM" presets: number of beats visible to the right
  // of the playhead (i.e. how far into the future, from the track's current
  // position, the display looks). 16 = most zoomed in (maximum detail),
  // 192 = most zoomed out (overall structure/phrasing at a glance).
  const zoomLevels = [16, 24, 32, 48, 64, 96, 128, 160, 192];
  let zoomIndex = zoomLevels.indexOf(48);
  $: zoomBeats = zoomLevels[zoomIndex];

  function zoomOut() {
    zoomIndex = Math.min(zoomLevels.length - 1, zoomIndex + 1);
  }
  function zoomIn() {
    zoomIndex = Math.max(0, zoomIndex - 1);
  }

  // Dims decks that are not currently On-Air (brightness configurable via
  // Settings -> Display, inactiveDeckBrightnessPercent) relative to the
  // On-Air ones, so the active deck(s) stand out at a glance - toggled
  // off here if the user prefers all decks shown at full, equal brightness.
  let dimInactive = true;

  // Privacy/"Datenschutz" mode: hides the track title/artist in the
  // overview row (e.g. for streaming/screenshots where the currently
  // played track shouldn't be revealed). Defaults to on.
  let privacyMode = true;

  function formatTime(ms: number | null): string {
    if (ms == null) return '--:--';
    const totalSeconds = Math.floor(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  // The CDJ/rekordbox loop range: lengths are always a power-of-two number
  // of beats, from 1/32 up to 32 - real DJs practically always use one of
  // these exact "quantized" lengths (CDJs even enforce it when "Quantize"
  // is on, which is the common case), never an arbitrary duration. Our own
  // measured loop length (see `PlayerState.loopStartMs`/`loopEndMs`'s doc
  // comment) is derived from real hardware position updates, which only
  // arrive every so often - so the raw measurement is always *slightly*
  // off from the true musical length by however much the track moved
  // between the last update before the wrap and the wrap itself. Rounding
  // to the nearest value in this list turns that noisy raw measurement
  // into the clean, musically meaningful length a DJ actually set.
  const QUANTIZED_LOOP_BEATS = [1 / 32, 1 / 16, 1 / 8, 1 / 4, 1 / 2, 1, 2, 4, 8, 16, 32];

  function quantizeLoopBeats(rawBeats: number): number {
    if (!(rawBeats > 0)) return QUANTIZED_LOOP_BEATS[0];
    // Compare in log2-space since the candidates are spaced geometrically
    // (powers of two), not linearly - the "nearest" value only makes sense
    // relative to that spacing.
    let closest = QUANTIZED_LOOP_BEATS[0];
    let smallestLogDiff = Infinity;
    for (const candidate of QUANTIZED_LOOP_BEATS) {
      const diff = Math.abs(Math.log2(rawBeats) - Math.log2(candidate));
      if (diff < smallestLogDiff) {
        smallestLogDiff = diff;
        closest = candidate;
      }
    }
    return closest;
  }

  function formatLoopBeats(beats: number): string {
    if (beats >= 1) return `${beats}`;
    return `1/${Math.round(1 / beats)}`;
  }

  /**
   * Quantized loop length, both in beats (the musically meaningful unit DJs
   * actually think/set loops in) and the equivalent seconds at the track's
   * current tempo - `null` until the first wrap has been observed (right
   * when a loop starts, its length is not yet measurable).
   */
  function loopInfo(player: PlayerState | null): { beats: number; seconds: number } | null {
    if (player?.loopStartMs == null || player?.loopEndMs == null) return null;
    const rawDurationMs = player.loopEndMs - player.loopStartMs;
    const bpm = player.effectiveBpm || player.originalBpm || 120;
    const beatIntervalMs = 60_000 / bpm;
    const quantizedBeats = quantizeLoopBeats(rawDurationMs / beatIntervalMs);
    return { beats: quantizedBeats, seconds: (quantizedBeats * beatIntervalMs) / 1000 };
  }

  /**
   * Kind label for the left-hand device box: "Master" when the slot
   * follows the current tempo master, "Player" otherwise (fixed-player and
   * AUTO/ON-AIR slots alike) - shown above the device number.
   */
  function slotKind(slot: PlayerSlotConfig): string {
    return slot.sourceType === 'CURRENT_MASTER' ? 'Master' : 'Player';
  }

  $: resolved = [...slots]
    .filter((s) => s.visible)
    .sort((a, b) => a.slot - b.slot)
    .map((slot) => {
      const deviceNumber = resolveSlot(slot, players, masterClock);
      const player = deviceNumber != null ? players.find((p) => p.deviceNumber === deviceNumber) ?? null : null;
      return { slot, player, deviceNumber };
    });

  // Only dim decks when at least one connected deck actually *is* On-Air -
  // otherwise (nobody on air yet) dimming everything would be meaningless
  // and just make the whole view darker for no reason.
  $: anyOnAir = resolved.some(({ player }) => player?.onAir);
  $: shouldDim = (player: PlayerState | null) => dimInactive && anyOnAir && !player?.onAir;
  $: dimFilter = `brightness(${Math.min(95, Math.max(5, inactiveDeckBrightnessPercent)) / 100})`;
</script>

<div class="live-view">
  <div class="overview-row">
    {#each resolved as { slot, player } (slot.slot)}
      <div
        class="overview-card"
        class:master={player?.tempoMaster}
        class:dimmed={shouldDim(player)}
        style:filter={shouldDim(player) ? dimFilter : ''}
      >
        <div class="header">
          {#if player && privacyMode}
            <span class="title privacy-placeholder" title="Datenschutz-Modus aktiv">🔒 Track ausgeblendet</span>
          {:else}
            <span class="title">{player?.title ?? (player ? '—' : 'Not connected')}</span>
            <span class="artist">{player?.artist ?? ''}</span>
          {/if}
        </div>
        <div class="time-row">
          <span>{formatTime(player?.playbackPositionMs ?? null)} / {formatTime(player?.trackDurationMs ?? null)}</span>
        </div>
        {#if player}
          <WaveformView {player} height={36} />
        {:else}
          <div class="empty-overview">Slot {slot.slot}</div>
        {/if}
      </div>
    {/each}
  </div>

  <div class="detail-rows">
    <div class="zoom-control">
      <span class="zoom-label">Wave Zoom</span>
      <button on:click={zoomIn} disabled={zoomIndex === 0} aria-label="Zoom in">−</button>
      <span class="zoom-value">{zoomBeats}</span>
      <button on:click={zoomOut} disabled={zoomIndex === zoomLevels.length - 1} aria-label="Zoom out">+</button>
      <button class="dim-toggle" class:active={dimInactive} on:click={() => (dimInactive = !dimInactive)}>
        Inaktive Decks abdunkeln
      </button>
      <button class="dim-toggle" class:active={privacyMode} on:click={() => (privacyMode = !privacyMode)}>
        🔒 Datenschutz-Modus
      </button>
      {#if onExit}
        <button class="exit-button" on:click={onExit} title="Exit Live View (Esc)">✕ Exit</button>
      {/if}
    </div>
    {#each resolved as { slot, player, deviceNumber } (slot.slot)}
      <div
        class="detail-row-wrapper"
        class:dimmed={shouldDim(player)}
        style:filter={shouldDim(player) ? dimFilter : ''}
      >
        <div class="detail-label">
          <div class="device-number-box" class:on-air={player?.onAir}>
            <span class="device-kind">{slotKind(slot)}</span>
            <span class="device-number">{deviceNumber ?? slot.slot}</span>
          </div>
          <div class="bpm-value" class:master={player?.tempoMaster}>
            {player?.effectiveBpm?.toFixed(2) ?? '--.--'}
            {#if player?.tempoMaster}<span class="master-icon" title="Tempo Master">★</span>{/if}
          </div>
          <div class="pitch-value">{player?.pitchPercent != null ? `${player.pitchPercent >= 0 ? '+' : ''}${player.pitchPercent.toFixed(2)}%` : '--%'}</div>
          {#if player?.looping}
            <div class="loop-badge" title="Loop active">
              ∞ LOOP{#if loopInfo(player) != null}&nbsp;· {formatLoopBeats(loopInfo(player)?.beats ?? 0)} Beats ({loopInfo(player)?.seconds.toFixed(1)}s){/if}
            </div>
          {/if}
        </div>
        <LiveDetailWaveform {player} height={130} {zoomBeats} />
      </div>
    {/each}
  </div>
</div>

<style>
  .live-view {
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    background: #08090b;
    color: #eee;
    font-family: system-ui, sans-serif;
    overflow: hidden;
  }
  .overview-row {
    display: flex;
    gap: 8px;
    padding: 10px;
    border-bottom: 1px solid #222;
    flex-shrink: 0;
  }
  .overview-card {
    flex: 1;
    min-width: 0;
    background: #141519;
    border: 1px solid #24252b;
    border-radius: 6px;
    padding: 6px 10px;
    transition: filter 0.2s ease;
  }
  .overview-card.master {
    border-color: #ffb300;
  }
  .header {
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .title {
    font-weight: 700;
    font-size: 14px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .artist {
    font-size: 11px;
    color: #999;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .privacy-placeholder {
    color: #777;
    font-style: italic;
  }
  .time-row {
    display: flex;
    justify-content: flex-end;
    font-size: 11px;
    color: #aaa;
    margin: 2px 0 4px 0;
  }
  .empty-overview {
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #555;
    font-size: 11px;
  }
  .detail-rows {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .zoom-control {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-bottom: 1px solid #1a1b1f;
    flex-shrink: 0;
  }
  .zoom-label {
    font-size: 10px;
    color: #777;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-right: 4px;
  }
  .zoom-control button {
    background: #141519;
    border: 1px solid #2a2b31;
    color: #bbb;
    font-size: 13px;
    font-weight: 700;
    width: 24px;
    height: 24px;
    line-height: 1;
    border-radius: 4px;
    cursor: pointer;
  }
  .zoom-control button:hover:not(:disabled) {
    border-color: #444;
  }
  .zoom-control button:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .dim-toggle {
    width: auto !important;
    height: auto !important;
    padding: 3px 10px;
    font-size: 11px !important;
    font-weight: 400 !important;
  }
  .dim-toggle.active {
    background: #2a4a2a;
    border-color: #4caf50;
    color: #c8e6c9;
  }
  .exit-button {
    width: auto !important;
    height: auto !important;
    padding: 3px 12px;
    font-size: 11px !important;
    font-weight: 400 !important;
    margin-left: auto;
  }
  .exit-button:hover {
    border-color: #d32f2f !important;
    color: #ff8a80;
  }
  .zoom-value {
    font-size: 13px;
    font-weight: 700;
    min-width: 32px;
    text-align: center;
  }
  .detail-row-wrapper {
    flex: 1;
    display: flex;
    align-items: stretch;
    border-bottom: 1px solid #1a1b1f;
    min-height: 0;
    transition: filter 0.2s ease;
  }
  .detail-label {
    width: 120px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 8px;
    border-right: 1px solid #1a1b1f;
    overflow: hidden;
  }
  .device-number-box {
    width: 72px;
    height: 72px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    background: #3a3b42;
    color: #ddd;
    transition: background 0.15s ease;
  }
  .device-number-box.on-air {
    background: #d32f2f;
    color: #fff;
  }
  .device-kind {
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }
  .device-number {
    font-size: 28px;
    font-weight: 700;
    line-height: 1.1;
  }
  .bpm-value {
    font-size: 20px;
    font-weight: 700;
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 1px 8px;
    border-radius: 4px;
    border: 2px solid transparent;
  }
  .bpm-value.master {
    border-color: #ffb300;
  }
  .master-icon {
    color: #ffb300;
    font-size: 12px;
  }
  .loop-badge {
    font-size: 10px;
    font-weight: 700;
    color: #fff9c4;
    background: rgba(255, 235, 59, 0.2);
    border: 1px solid #ffeb3b;
    border-radius: 3px;
    padding: 1px 6px;
  }
  .pitch-value {
    font-size: 12px;
    color: #999;
  }
</style>
