<script lang="ts">
  import type { PlayerState } from '../stores/types';
  import WaveformView from './WaveformView.svelte';

  export let player: PlayerState | null;
  export let slotLabel: string;

  // Fixed, sensible waveform height - the Monitor page no longer offers
  // per-card sizing (requirements specification redesign: auto-layout by deck count).
  const waveformHeight = 64;

  function formatTime(ms: number | null): string {
    if (ms == null) return '--:--';
    const totalSeconds = Math.floor(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  /** Bar/measure number (every 4 beats), derived from the 1-indexed beat counter - not reported directly by Pro DJ Link. */
  function barNumber(beatNumber: number | null): number | null {
    if (beatNumber == null || beatNumber < 1) return null;
    return Math.ceil(beatNumber / 4);
  }
</script>

<div class="card" class:master={player?.tempoMaster} class:offline={!player?.connected}>
  <div class="header">
    <span class="slot">{slotLabel}</span>
    {#if player}
      <span class="device">#{player.deviceNumber} · {player.deviceName || player.deviceType}</span>
    {:else}
      <span class="device">unassigned</span>
    {/if}
    <span class="badges">
      {#if player?.tempoMaster}<span class="badge master-badge">MASTER</span>{/if}
      {#if player?.onAir}<span class="badge onair-badge">ON AIR</span>{/if}
      {#if player?.looping}<span class="badge loop-badge">LOOP</span>{/if}
      {#if player?.source === 'SIMULATED'}<span class="badge sim-badge">SIM</span>{/if}
    </span>
  </div>

  {#if player && player.connected}
    <div class="track">
      <div class="title">{player.title ?? '—'}</div>
      <div class="artist">{player.artist ?? ''}</div>
    </div>

    <div class="bpm-row">
      <div>
        <span class="label">BPM</span>
        <span class="value">{player.originalBpm?.toFixed(2) ?? '--'}</span>
      </div>
      <div>
        <span class="label">Pitch</span>
        <span class="value">{player.pitchPercent != null ? (player.pitchPercent >= 0 ? '+' : '') + player.pitchPercent.toFixed(2) + '%' : '--'}</span>
      </div>
      <div>
        <span class="label">Effective</span>
        <span class="value highlight">{player.effectiveBpm?.toFixed(2) ?? '--'}</span>
      </div>
    </div>

    <WaveformView {player} height={waveformHeight} />

    <div class="footer">
      <span>{formatTime(player.playbackPositionMs)} / {formatTime(player.trackDurationMs)}</span>
      <span>Takt {barNumber(player.beatNumber) ?? '-'} · {player.beatWithinBar ?? '-'}/4</span>
      <span class="status">{player.playing ? '▶ Playing' : player.paused ? '⏸ Paused' : '⏹'}</span>
    </div>
  {:else}
    <div class="disconnected">Not connected</div>
  {/if}
</div>

<style>
  .card {
    position: relative;
    width: 100%;
    background: #202127;
    border: 1px solid #2d2e36;
    border-radius: 10px;
    padding: 12px 14px;
    color: #eee;
    font-family: system-ui, sans-serif;
    font-size: 14px;
    box-sizing: border-box;
    transition: border-color 0.15s ease, box-shadow 0.15s ease;
  }
  .card.master {
    border-color: #ffb300;
    box-shadow: 0 0 0 1px #ffb300 inset;
  }
  .card.offline {
    opacity: 0.55;
  }
  .header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 0.8em;
    color: #999;
    margin-bottom: 4px;
  }
  .slot {
    font-weight: 700;
    color: #ddd;
  }
  .badges {
    margin-left: auto;
    display: flex;
    gap: 4px;
  }
  .badge {
    font-size: 0.65em;
    padding: 1px 5px;
    border-radius: 3px;
    font-weight: 700;
  }
  .master-badge { background: #ffb300; color: #201c00; }
  .onair-badge { background: #e53935; color: #fff; }
  .loop-badge { background: #ffeb3b; color: #201c00; }
  .sim-badge { background: #455a64; color: #cfd8dc; }
  .track .title {
    font-size: 1.15em;
    font-weight: 600;
  }
  .track .artist {
    font-size: 0.85em;
    color: #aaa;
    margin-bottom: 6px;
  }
  .bpm-row {
    display: flex;
    gap: 16px;
    margin: 6px 0;
  }
  .label {
    display: block;
    font-size: 0.7em;
    color: #888;
  }
  .value {
    font-size: 1.05em;
    font-variant-numeric: tabular-nums;
  }
  .value.highlight {
    color: #4fc3f7;
    font-weight: 700;
  }
  .footer {
    display: flex;
    justify-content: space-between;
    font-size: 0.8em;
    color: #999;
    margin-top: 6px;
  }
  .disconnected {
    padding: 18px 0;
    text-align: center;
    color: #777;
    font-size: 0.9em;
  }
</style>
