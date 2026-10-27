<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api } from '../stores/api';
  import { bandGainsFor, looksThreeBand } from '../stores/eq';
  import { activeProfile } from '../stores/layout';
  import type { AudioLevelStatus, MixerLinkStatus, UtilityItem, UtilityWriteResult } from '../stores/types';
  import { mixerState } from '../websocket/connection';

  let link: MixerLinkStatus | null = null;
  let pollHandle: ReturnType<typeof setInterval> | null = null;
  let error = '';

  // Value the user is about to write, per utility id (kept separate from the value the mixer last reported).
  let pending: Record<number, number> = {};
  let results: Record<number, UtilityWriteResult> = {};
  let writing: Record<number, boolean> = {};

  async function refreshLink() {
    try {
      link = (await api.getMixer()).link;
      error = '';
    } catch (e) {
      error = 'Backend not reachable';
    }
  }

  let audio: AudioLevelStatus | null = null;
  let levelHandle: ReturnType<typeof setInterval> | null = null;

  async function refreshLevels() {
    try {
      audio = await api.getMixerLevels();
    } catch (e) {
      audio = null;
    }
  }

  onMount(() => {
    void refreshLink();
    pollHandle = setInterval(refreshLink, 2000);
    levelHandle = setInterval(refreshLevels, 200);
  });
  onDestroy(() => {
    if (pollHandle) clearInterval(pollHandle);
    if (levelHandle) clearInterval(levelHandle);
  });

  // Meter scale: 0..255 byte, shown as -40..0 dB.
  const meter = (value: number) => (value <= 0 ? 0 : Math.max(0, Math.min(1, 1 + (20 * Math.log10(value / 255)) / 40)));

  $: reported = new Map($mixerState.utilities.map((u) => [u.id, u.value]));
  $: channels = $mixerState.live?.channels.slice(0, 6) ?? [];
  $: items = link?.utilityItems ?? [];
  $: canWrite = !!link && (link.integrationEnabled && link.controlEnabled && link.transportAvailable);

  function valueOptions(item: UtilityItem): number[] {
    if (item.max - item.min > 16) return [];
    return Array.from({ length: item.max - item.min + 1 }, (_, i) => item.min + i);
  }

  function shownValue(item: UtilityItem): number {
    return pending[item.id] ?? (reported.get(item.id) ?? item.min);
  }

  async function write(item: UtilityItem) {
    writing = { ...writing, [item.id]: true };
    try {
      results = { ...results, [item.id]: await api.mixerWriteUtility(item.id, shownValue(item)) };
    } catch (e) {
      results = {
        ...results,
        [item.id]: { status: 'NO_MIXER', id: item.id, requestedValue: shownValue(item), reportedValue: null, message: 'Request failed' },
      };
    } finally {
      writing = { ...writing, [item.id]: false };
    }
  }

  function percent(gain: number): string {
    return `${Math.round(gain * 100)}%`;
  }

  function statusText(result: UtilityWriteResult): string {
    switch (result.status) {
      case 'CONFIRMED':
        return 'Confirmed by the mixer';
      case 'MISMATCH':
        return `Mixer reports ${result.reportedValue}`;
      case 'REJECTED':
        return 'Mixer reports this item as unavailable';
      case 'NO_REPLY':
        return 'No reply - effect unknown';
      default:
        return result.message ?? result.status;
    }
  }
</script>

<div class="panel">
  <section>
    <h3>Status</h3>
    {#if error}<p class="warn">{error}</p>{/if}
    {#if link}
      <ul class="status">
        <li>Integration: <b>{link.integrationEnabled ? 'enabled' : 'disabled'}</b> (Settings → Mixer)</li>
        <li>Changing settings: <b>{link.controlEnabled ? 'allowed' : 'not allowed'}</b></li>
        <li>Pro DJ Link socket hooked: <b>{link.transportAvailable ? 'yes' : 'no'}</b></li>
        <li>Mixer: <b>{link.mixerModel ?? 'unknown'}</b>, address <b>{link.mixerAddress ?? 'unknown'}</b></li>
        <li>
          Live frames received: <b>{link.liveFramesReceived}</b>
          {#if link.lastLiveFrameAgeMs != null}(last {(link.lastLiveFrameAgeMs / 1000).toFixed(1)} s ago){/if}
        </li>
        <li>Utility replies received: <b>{link.utilityRepliesReceived}</b></li>
        <li>Mixer firmware version: <b>{$mixerState.version ?? 'unknown'}</b></li>
        <li>
          Extra identity (category 5):
          <b>{link.announcer && link.announcer.state === 'ONLINE' ? `online as #${link.announcer.deviceNumber} (${link.announcer.name})` : (link.announcer?.state ?? 'off').toLowerCase()}</b>
        </li>
        {#if link.lastError}<li class="warn">Last error: {link.lastError}</li>{/if}
      </ul>
    {/if}
  </section>

  <section>
    <h3>Live EQ</h3>
    {#if channels.length === 0}
      <p class="hint">No live mixer data. Enable the mixer integration and the third-party identity (Settings → Mixer) and connect a mixer.</p>
    {:else}
      <p class="hint">
        Raw knob values as sent by the mixer, and the resulting waveform brightness (low / mid / high) with the
        current calibration (Settings → Mixer).
      </p>
      <table>
        <thead>
          <tr><th>Ch</th><th>Hi</th><th>Hi-Mid</th><th>Lo-Mid</th><th>Low</th><th>Fader</th><th>Brightness L / M / H</th></tr>
        </thead>
        <tbody>
          {#each channels as channel (channel.channel)}
            {@const gains = bandGainsFor(channel, $activeProfile, looksThreeBand(channels))}
            <tr>
              <td>{channel.channel}</td>
              <td>{channel.eqHi}</td>
              <td>{channel.eqHiMid}</td>
              <td>{channel.eqLoMid}</td>
              <td>{channel.eqLow}</td>
              <td>{channel.fader}</td>
              <td>{percent(gains.low)} / {percent(gains.mid)} / {percent(gains.high)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}
  </section>

  <section>
    <h3>Audio levels</h3>
    {#if audio?.levels}
      <div class="meters">
        {#each audio.levels.channels as channel}
          <div class="meter" title={`Ch ${channel.channel}: ${channel.left}`}>
            <div class="bar" style:height={`${meter(channel.left) * 100}%`}></div>
            <span>{channel.channel}</span>
          </div>
        {/each}
        <div class="meter master" title={`Master L ${audio.levels.masterLeft}`}>
          <div class="bar" style:height={`${meter(audio.levels.masterLeft) * 100}%`}></div>
          <span>L</span>
        </div>
        <div class="meter master" title={`Master R ${audio.levels.masterRight}`}>
          <div class="bar" style:height={`${meter(audio.levels.masterRight) * 100}%`}></div>
          <span>R</span>
        </div>
      </div>
    {:else}
      <p class="hint">No level data (frames received: {audio?.framesReceived ?? 0}).</p>
    {/if}
  </section>

  <section>
    <h3>Mixer settings</h3>
    <p class="hint">
      Values appear after the mixer has answered a read request. A change is only reported as confirmed after the mixer
      itself reports the new value back.
      {#if !canWrite}Changing is currently disabled (see Status).{/if}
    </p>
    <div class="row">
      <button on:click={() => api.mixerReadAll()} disabled={!link || !link.transportAvailable}>Read all</button>
    </div>
    <table>
      <thead><tr><th>Setting</th><th>Mixer reports</th><th>New value</th><th></th><th>Result</th></tr></thead>
      <tbody>
        {#each items as item (item.id)}
          {@const options = valueOptions(item)}
          <tr>
            <td title={item.note ?? ''}>{item.label}</td>
            <td>{reported.has(item.id) ? (reported.get(item.id) ?? 'unavailable') : '–'}</td>
            <td>
              {#if options.length > 0}
                <select value={shownValue(item)} on:change={(e) => (pending = { ...pending, [item.id]: Number((e.target as HTMLSelectElement).value) })}>
                  {#each options as option}<option value={option}>{option}</option>{/each}
                </select>
              {:else}
                <input type="number" min={item.min} max={item.max} value={shownValue(item)}
                  on:input={(e) => (pending = { ...pending, [item.id]: Number((e.target as HTMLInputElement).value) })} />
              {/if}
            </td>
            <td>
              <button on:click={() => api.mixerRead(item.id)} disabled={!link || !link.transportAvailable}>Read</button>
              <button on:click={() => write(item)} disabled={!canWrite || writing[item.id]}>Write</button>
            </td>
            <td class:ok={results[item.id]?.status === 'CONFIRMED'}>{results[item.id] ? statusText(results[item.id]) : ''}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  </section>
</div>

<style>
  .panel {
    padding: 16px;
    color: #eee;
    font-family: system-ui, sans-serif;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  section {
    background: #17181d;
    border: 1px solid #2a2b31;
    border-radius: 6px;
    padding: 12px;
  }
  h3 {
    margin: 0 0 8px;
  }
  .hint {
    color: #9a9ba3;
    font-size: 12px;
  }
  .warn {
    color: #ffb300;
  }
  .status {
    list-style: none;
    padding: 0;
    margin: 0 0 8px;
    font-size: 13px;
  }
  table {
    border-collapse: collapse;
    width: 100%;
    font-size: 13px;
  }
  th,
  td {
    text-align: left;
    padding: 4px 8px;
    border-bottom: 1px solid #2a2b31;
  }
  td.ok {
    color: #66bb6a;
  }
  button {
    background: #2a2b31;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 4px;
    padding: 4px 8px;
    cursor: pointer;
  }
  button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .row {
    margin-bottom: 8px;
  }
  .meters {
    display: flex;
    gap: 8px;
    align-items: flex-end;
    height: 120px;
  }
  .meter {
    position: relative;
    width: 24px;
    height: 100%;
    background: #0c0d10;
    border: 1px solid #2a2b31;
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
  }
  .meter.master {
    width: 32px;
  }
  .bar {
    background: linear-gradient(to top, #2e7d32, #fdd835, #e53935);
    width: 100%;
  }
  .meter span {
    position: absolute;
    bottom: -18px;
    width: 100%;
    text-align: center;
    font-size: 11px;
    color: #9a9ba3;
  }
  .meters {
    margin-bottom: 22px;
  }
</style>
