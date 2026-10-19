<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '../stores/api';
  import type { PlayerState, SimulatedTrack } from '../stores/types';

  export let players: PlayerState[];
  export let simulatorRunning: boolean;

  let tracks: SimulatedTrack[] = [];
  const deviceNumbers = [1, 2, 3, 4, 5, 6];

  onMount(async () => {
    tracks = await api.simulatorTracks();
  });

  // Built as a `$:` reactive statement (rather than a plain function called
  // from inside the template) so Svelte reliably recomputes it whenever the
  // `players` prop updates - the same root cause and fix as the earlier
  // GlobalBar widget-visibility bug: a plain function closing over a
  // reactive prop is not tracked as a template dependency, so buttons here
  // only reflected fresh state after an unrelated re-render (e.g. switching
  // tabs) forced the whole tree to redraw.
  $: playersByDevice = new Map(
    players.filter((p) => p.source === 'SIMULATED').map((p) => [p.deviceNumber, p]),
  );

  async function toggleGlobal() {
    if (simulatorRunning) await api.simulatorStop();
    else await api.simulatorStart();
  }
</script>

<div class="panel">
  <div class="global-controls">
    <button on:click={toggleGlobal}>{simulatorRunning ? 'Stop Simulator' : 'Start Simulator'}</button>
  </div>

  <div class="players">
    {#each deviceNumbers as device}
      {@const player = playersByDevice.get(device)}
      <div class="row">
        <span class="device-label">Player {device}</span>

        {#if player?.connected}
          <button on:click={() => api.simulatorDisconnect(device)}>Disconnect</button>
          <select on:change={(e) => api.simulatorLoad(device, Number((e.target as HTMLSelectElement).value))}>
            <option value="" disabled selected>Load track…</option>
            {#each tracks as track, i}
              <option value={i}>{track.title} — {track.artist} ({track.originalBpm} BPM)</option>
            {/each}
          </select>
          <button on:click={() => api.simulatorPlay(device)} disabled={player.playing}>Play</button>
          <button on:click={() => api.simulatorPause(device)} disabled={!player.playing}>Pause</button>
          <button on:click={() => api.simulatorMaster(device)} class:active={player.tempoMaster}>Master</button>
          <button on:click={() => api.simulatorOnAir(device, !player.onAir)} class:active={player.onAir}>On Air</button>
          <label class="pitch">
            Pitch
            <input
              type="range"
              min="-10"
              max="10"
              step="0.1"
              value={player.pitchPercent ?? 0}
              on:input={(e) => api.simulatorPitch(device, Number((e.target as HTMLInputElement).value))}
            />
            <span>{(player.pitchPercent ?? 0).toFixed(1)}%</span>
          </label>
          {#if player.trackDurationMs}
            <input
              type="range"
              min="0"
              max={player.trackDurationMs}
              value={player.playbackPositionMs ?? 0}
              on:input={(e) => api.simulatorSeek(device, Number((e.target as HTMLInputElement).value))}
            />
          {/if}
        {:else}
          <button on:click={() => api.simulatorConnect(device)}>Connect</button>
        {/if}
      </div>
    {/each}
  </div>
</div>

<style>
  .panel {
    padding: 16px;
    color: #eee;
    font-family: system-ui, sans-serif;
  }
  .global-controls {
    margin-bottom: 12px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 0;
    border-bottom: 1px solid #2a2b31;
    flex-wrap: wrap;
  }
  .device-label {
    width: 80px;
    font-weight: 600;
  }
  button {
    background: #2a2b31;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 4px;
    padding: 4px 8px;
    cursor: pointer;
  }
  button.active {
    background: #ffb300;
    color: #201c00;
  }
  .pitch {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
  }
</style>
