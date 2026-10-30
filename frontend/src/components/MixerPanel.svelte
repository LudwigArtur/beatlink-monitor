<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api } from '../stores/api';
  import { bandGainsFor, isThreeBand } from '../stores/eq';
  import { activeProfile } from '../stores/layout';
  import type { AudioLevelStatus, MixerLinkStatus, UtilityItem, UtilityWriteResult } from '../stores/types';
  import { mixerState } from '../websocket/connection';
  import MixerVisual from './MixerVisual.svelte';
  import RemoteUnlock from './RemoteUnlock.svelte';
  import { confirmAction } from '../stores/confirm';
  import { hardwareWritesLocked } from '../stores/remotePin';

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
    levelHandle = setInterval(refreshLevels, 150);
  });
  onDestroy(() => {
    if (pollHandle) clearInterval(pollHandle);
    if (levelHandle) clearInterval(levelHandle);
  });

  // Meter scale: 0..255 byte, shown as -40..0 dB.
  const meter = (value: number) => (value <= 0 ? 0 : Math.max(0, Math.min(1, 1 + (20 * Math.log10(value / 255)) / 40)));

  $: reported = new Map($mixerState.utilities.map((u) => [u.id, u.value]));
  $: channels = $mixerState.live?.channels.slice(0, $mixerState.channelCount ?? 6) ?? [];
  $: threeBand = isThreeBand($mixerState.eqBands, channels);
  $: eqDescription = $mixerState.eqBands
    ? $mixerState.eqBands + '-band (by model)'
    : (threeBand ? '3' : '4') + '-band (model unknown, detected from the data)';
  $: midDescription =
    $activeProfile.eqMidSource !== 'AUTO'
      ? $activeProfile.eqMidSource.replace('_', '-').toLowerCase()
      : threeBand
        ? 'Hi-Mid'
        : 'the average of Hi-Mid and Lo-Mid';
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
    const detail = `${item.label}\n${reported.get(item.id) ?? '–'} → ${shownValue(item)}`;
    if (!(await confirmAction('Write setting to mixer?', detail, 'Write'))) return;
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

<div class="page">
  <RemoteUnlock />
  <section class="card">
    <div class="row head">
      <h3>{link?.mixerModel ?? 'Mixer'}</h3>
      {#if link}
        <span class="status-text">
          <span class="dot" class:ok={link.lastLiveFrameAgeMs != null && link.lastLiveFrameAgeMs < 5000}></span>
          {link.lastLiveFrameAgeMs != null && link.lastLiveFrameAgeMs < 5000 ? 'Live data' : 'No live data'}
        </span>
      {/if}
    </div>
    {#if error}<p class="warn">{error}</p>{/if}
    {#if link}
      <dl class="kv">
        <dt>Firmware</dt><dd>{$mixerState.version ?? 'unknown'}</dd>
        <dt>Address</dt><dd>{link.mixerAddress ?? 'unknown'}</dd>
        <dt>Integration</dt><dd>{link.integrationEnabled ? 'enabled' : 'disabled'} (Settings → Mixer &amp; CDJs)</dd>
        <dt>Changing settings</dt><dd>{link.controlEnabled ? 'allowed' : 'not allowed'}</dd>
        <dt>Pro DJ Link socket</dt><dd>{link.transportAvailable ? 'hooked' : 'not hooked'}</dd>
        <dt>Live frames</dt>
        <dd>
          {link.liveFramesReceived}
          {#if link.lastLiveFrameAgeMs != null}<span class="muted">(last {(link.lastLiveFrameAgeMs / 1000).toFixed(1)} s ago)</span>{/if}
        </dd>
        <dt>Utility replies</dt><dd>{link.utilityRepliesReceived}</dd>
        <dt>EQ</dt><dd>{eqDescription}; mid brightness follows {midDescription}</dd>
        <dt>Extra identity</dt>
        <dd>
          {link.announcer && link.announcer.state === 'ONLINE'
            ? `online as #${link.announcer.deviceNumber} (${link.announcer.name})`
            : (link.announcer?.state ?? 'off').toLowerCase()}
          <span class="muted">(category 5)</span>
        </dd>
      </dl>
      {#if link.lastError}<p class="warn">Last error: {link.lastError}</p>{/if}
    {/if}
  </section>


  {#snippet rawTable()}
    <table>
          <thead>
            <tr>
              <th>Ch</th><th class="num">Hi</th><th class="num">Hi-Mid</th><th class="num">Lo-Mid</th><th class="num">Low</th>
              <th class="num">Fader</th><th class="num">Brightness L / M / H</th>
            </tr>
          </thead>
          <tbody>
            {#each channels as channel (channel.channel)}
              {@const gains = bandGainsFor(channel, $activeProfile, isThreeBand($mixerState.eqBands, channels))}
              <tr>
                <td>{channel.channel}</td>
                <td class="num">{channel.eqHi}</td>
                <td class="num">{channel.eqHiMid}</td>
                <td class="num">{channel.eqLoMid}</td>
                <td class="num">{channel.eqLow}</td>
                <td class="num">{channel.fader}</td>
                <td class="num">{percent(gains.low)} / {percent(gains.mid)} / {percent(gains.high)}</td>
              </tr>
            {/each}
          </tbody>
        </table>
  {/snippet}
  {#if $mixerState.channelCount && $mixerState.live}
    <section class="card">
      <h3>Controls</h3>
      <p class="hint">Live positions of the knobs and faders as the mixer reports them. Hover a control for its raw value.</p>
      <div class="visual">
        <MixerVisual live={$mixerState.live} mixer={$mixerState} levels={audio?.levels ?? null} />
      </div>
      <details class="raw">
        <summary>Raw values</summary>
        <p class="hint">
          Raw knob values as sent by the mixer, and the resulting waveform brightness (low / mid / high) with the
          current calibration (Settings → Mixer &amp; CDJs).
        </p>
        {@render rawTable()}
      </details>
    </section>
  {:else if $mixerState.channelCount}
    <section class="card">
      <h3>Controls</h3>
      <p class="hint">No live mixer data. Enable the mixer integration and the third-party identity (Settings → Mixer &amp; CDJs) and connect a mixer.</p>
    </section>
  {:else}
    <section class="card">
      <h3>Live EQ</h3>
      {#if channels.length === 0}
        <p class="hint">No live mixer data. Enable the mixer integration and the third-party identity (Settings → Mixer &amp; CDJs) and connect a mixer.</p>
      {:else}
        <p class="hint">
          Raw knob values as sent by the mixer, and the resulting waveform brightness (low / mid / high) with the
          current calibration (Settings → Mixer &amp; CDJs).
        </p>
        {@render rawTable()}
      {/if}
    </section>

    <section class="card">
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
  {/if}


  <section class="card">
    <div class="row head">
      <h3>Mixer settings</h3>
      <span class="spacer"></span>
      <button on:click={() => api.mixerReadAll()} disabled={!link || !link.transportAvailable}>Read all</button>
    </div>
    <p class="hint">
      Values appear after the mixer has answered a read request. A change is only reported as confirmed after the mixer
      itself reports the new value back.
      {#if !canWrite}Changing is currently disabled (see above).{:else if $hardwareWritesLocked}Enter the PIN at the top of this page to change settings.{/if}
    </p>
    <table>
      <thead><tr><th>Setting</th><th class="num">Mixer reports</th><th>New value</th><th></th><th>Result</th></tr></thead>
      <tbody>
        {#each items as item (item.id)}
          {@const options = valueOptions(item)}
          <tr>
            <td title={item.note ?? ''}>{item.label}</td>
            <td class="num">{reported.has(item.id) ? (reported.get(item.id) ?? 'unavailable') : '–'}</td>
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
            <td class="actions">
              <button on:click={() => api.mixerRead(item.id)} disabled={!link || !link.transportAvailable}>Read</button>
              <button on:click={() => write(item)} disabled={!canWrite || $hardwareWritesLocked || writing[item.id]}>Write</button>
            </td>
            <td class:ok={results[item.id]?.status === 'CONFIRMED'}>{results[item.id] ? statusText(results[item.id]) : ''}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  </section>
</div>

<style>
  /* The drawing scales with the available width (it is vector, so it stays sharp on any display density) and scrolls
     sideways instead of becoming unreadably small on narrow screens. */
  .visual {
    overflow-x: auto;
  }
  .visual :global(svg) {
    min-width: 640px;
    max-width: 1500px;
    margin: 0 auto;
  }
  .raw {
    margin-top: 12px;
  }
  .raw summary {
    cursor: pointer;
    color: var(--muted);
  }
  .raw[open] summary {
    margin-bottom: 8px;
  }
  .head {
    margin-bottom: 8px;
  }
  .head h3 {
    margin: 0;
  }
  .status-text {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--muted);
  }
  .kv {
    display: grid;
    grid-template-columns: minmax(140px, 200px) 1fr;
    gap: 3px 16px;
    margin: 0;
  }
  .kv dt {
    color: var(--muted);
  }
  .kv dd {
    margin: 0;
  }
  .actions {
    white-space: nowrap;
  }
  .actions button {
    min-height: 24px;
    padding: 2px 10px;
  }
  .meters {
    display: flex;
    gap: 8px;
    align-items: flex-end;
    height: 110px;
    margin-bottom: 22px;
  }
  .meter {
    position: relative;
    width: 22px;
    height: 100%;
    background: #0c0d10;
    border: 1px solid var(--border);
    border-radius: 3px;
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
  }
  .meter.master {
    width: 30px;
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
    color: var(--muted);
  }
</style>
