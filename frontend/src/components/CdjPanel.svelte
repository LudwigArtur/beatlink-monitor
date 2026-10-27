<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api } from '../stores/api';
  import type { CdjRemoteButton, CdjRemoteResult, CdjSettingItem, CdjSnapshot, CdjWriteResult } from '../stores/types';

  let snapshot: CdjSnapshot | null = null;
  let selected = 0;
  let error = '';
  let pollHandle: ReturnType<typeof setInterval> | null = null;

  // Value the user is about to write, keyed "player:offset" (kept apart from what the CDJ reports).
  let pending: Record<string, number> = {};
  let results: Record<string, CdjWriteResult> = {};
  let writing: Record<string, boolean> = {};
  let remoteResult: CdjRemoteResult | null = null;

  async function refresh() {
    try {
      snapshot = await api.getCdj();
      error = '';
      if (!snapshot.devices.some((d) => d.deviceNumber === selected)) selected = snapshot.devices[0]?.deviceNumber ?? 0;
    } catch (e) {
      error = 'Backend not reachable';
    }
  }

  onMount(() => {
    void refresh();
    pollHandle = setInterval(refresh, 2000);
  });
  onDestroy(() => {
    if (pollHandle) clearInterval(pollHandle);
  });

  $: link = snapshot?.link ?? null;
  $: device = snapshot?.devices.find((d) => d.deviceNumber === selected) ?? null;
  $: canWrite = !!link && link.integrationEnabled && link.controlEnabled && link.transportAvailable;
  $: groups = link ? [...new Set(link.buttons.map((b) => b.group))] : [];

  const key = (offset: number) => `${selected}:${offset}`;

  function reported(offset: number): number | undefined {
    return device?.settings[String(offset)];
  }

  function shown(item: CdjSettingItem): number {
    return pending[key(item.offset)] ?? reported(item.offset) ?? item.min;
  }

  function options(item: CdjSettingItem): number[] {
    return Array.from({ length: item.max - item.min + 1 }, (_, i) => item.min + i);
  }

  async function write(item: CdjSettingItem) {
    const k = key(item.offset);
    writing = { ...writing, [k]: true };
    try {
      results = { ...results, [k]: await api.cdjWriteSetting(selected, item.offset, shown(item)) };
      delete pending[k];
      pending = pending;
      await refresh();
    } catch (e) {
      results = { ...results, [k]: { status: 'NO_DEVICE', deviceNumber: selected, offset: item.offset, requestedValue: shown(item), reportedValue: null, message: 'Request failed' } };
    } finally {
      writing = { ...writing, [k]: false };
    }
  }

  function statusText(result: CdjWriteResult): string {
    switch (result.status) {
      case 'CONFIRMED':
        return 'Confirmed by the CDJ';
      case 'MISMATCH':
        return `CDJ reports ${result.reportedValue}`;
      case 'NO_REPLY':
        return 'No reply - effect unknown';
      default:
        return result.message ?? result.status;
    }
  }

  async function press(button: CdjRemoteButton, mode: 'click' | 'down' | 'up' = 'click') {
    try {
      remoteResult = await api.cdjButton(selected, button.code, mode);
    } catch (e) {
      remoteResult = { status: 'FAILED', message: 'Request failed' };
    }
  }

  const flagLabels: [string, string, boolean][] = [
    ['masterTempo', 'Master Tempo', true],
    ['vinylMode', 'Vinyl mode', true],
    ['autoCue', 'Auto Cue', true],
    ['quantize', 'Quantize', true],
    ['slip', 'Slip', false],
    ['keySync', 'Key Sync', false],
    ['tempoReset', 'Tempo Reset', false],
  ];
</script>

<div class="panel">
  <section>
    <h3>CDJs</h3>
    {#if error}<p class="warn">{error}</p>{/if}
    {#if link}
      <ul class="status">
        <li>Integration: {link.integrationEnabled ? 'on' : 'off'}, socket hooked: {link.transportAvailable ? 'yes' : 'no'}</li>
        <li>Replies: {link.settingsRepliesReceived} settings, {link.statusRepliesReceived} deck flags</li>
        <li>Settings changes: {link.controlEnabled ? 'allowed' : 'disabled'}, remote buttons: {link.remoteEnabled ? 'allowed' : 'disabled'}</li>
        {#if link.lastError}<li class="warn">Last error: {link.lastError}</li>{/if}
      </ul>
    {/if}
    <div class="row">
      {#each snapshot?.devices ?? [] as d (d.deviceNumber)}
        <button class:active={d.deviceNumber === selected} on:click={() => (selected = d.deviceNumber)}>Player {d.deviceNumber}</button>
      {/each}
      <button on:click={() => api.cdjRefresh()} disabled={!link || !link.transportAvailable}>Read now</button>
    </div>
    {#if !device}<p class="hint">No CDJ has answered yet.</p>{/if}
  </section>

  {#if device}
    <section>
      <h3>Deck flags (player {device.deviceNumber})</h3>
      <p class="hint">Names marked * are inferred from the order of the fields; the unmarked ones were matched against the CDJ's own settings.</p>
      {#if device.flags}
        <table>
          <tbody>
            {#each flagLabels as [flag, label, verified]}
              <tr><td>{label}{verified ? '' : ' *'}</td><td>{device.flags[flag as keyof typeof device.flags]}</td></tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>

    <section>
      <h3>Settings (player {device.deviceNumber})</h3>
      <p class="hint">
        A change counts as confirmed only after the CDJ reports the new value back.
        {#if !canWrite}Changing is currently disabled.{/if}
      </p>
      <table>
        <thead><tr><th>Setting</th><th>CDJ reports</th><th>New value</th><th></th><th>Result</th></tr></thead>
        <tbody>
          {#each link?.settingItems ?? [] as item (item.offset)}
            <tr>
              <td title={item.note ?? ''}>{item.label}</td>
              <td>{reported(item.offset) ?? '–'}</td>
              <td>
                <select value={shown(item)} on:change={(e) => (pending = { ...pending, [key(item.offset)]: Number((e.target as HTMLSelectElement).value) })}>
                  {#each options(item) as option}<option value={option}>{option}</option>{/each}
                </select>
              </td>
              <td><button on:click={() => write(item)} disabled={!canWrite || writing[key(item.offset)]}>Write</button></td>
              <td class:ok={results[key(item.offset)]?.status === 'CONFIRMED'}>{results[key(item.offset)] ? statusText(results[key(item.offset)]) : ''}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </section>

    {#if link?.remoteEnabled}
      <section>
        <h3>Remote buttons (player {device.deviceNumber})</h3>
        <p class="warn">These act immediately on the player, including during playback. Not acknowledged by the CDJ.</p>
        {#each groups as group}
          <h4>{group}</h4>
          <div class="row">
            {#each link.buttons.filter((b) => b.group === group) as button (button.code)}
              {#if button.key === 'cue'}
                <button on:mousedown={() => press(button, 'down')} on:mouseup={() => press(button, 'up')}>{button.label} (hold)</button>
              {:else}
                <button on:click={() => press(button)}>{button.label}</button>
              {/if}
            {/each}
          </div>
        {/each}
        {#if remoteResult}<p class:ok={remoteResult.status === 'SENT'}>{remoteResult.status}{remoteResult.message ? `: ${remoteResult.message}` : ''}</p>{/if}
      </section>
    {/if}
  {/if}
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
  h3,
  h4 {
    margin: 0 0 8px;
  }
  h4 {
    margin-top: 10px;
    font-size: 13px;
    color: #9a9ba3;
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
  .ok {
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
  button.active {
    background: #3b5bdb;
  }
  button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 8px;
  }
</style>
