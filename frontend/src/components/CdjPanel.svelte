<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api } from '../stores/api';
  import type { CdjDeckFlags, CdjRemoteButton, CdjRemoteResult, CdjSettingItem, CdjSnapshot, CdjWriteResult, RemoteUnlockInfo } from '../stores/types';
  import { connectionId } from '../websocket/connection';

  let snapshot: CdjSnapshot | null = null;
  let selected = 0;
  let error = '';
  let pollHandle: ReturnType<typeof setInterval> | null = null;

  // Value the user is about to write, keyed "player:offset" (kept apart from what the CDJ reports).
  let pending: Record<string, number> = {};
  let results: Record<string, CdjWriteResult> = {};
  let writing: Record<string, boolean> = {};
  let remoteResult: CdjRemoteResult | null = null;
  let remoteInfo: RemoteUnlockInfo | null = null;
  let pin = '';
  let pinMessage = '';

  async function refreshRemote() {
    try {
      remoteInfo = await api.cdjRemoteInfo();
    } catch (e) {
      remoteInfo = null;
    }
  }

  // The unlock belongs to the WebSocket connection: a new connection starts locked again.
  $: if ($connectionId !== undefined) {
    pin = '';
    pinMessage = '';
    remoteResult = null;
    void refreshRemote();
  }

  async function unlock() {
    const result = await api.cdjRemoteUnlock(pin);
    pin = '';
    pinMessage = {
      UNLOCKED: '',
      WRONG_PIN: 'Wrong PIN.',
      NO_PIN_FILE: 'No PIN file on the server yet.',
      INVALID_PIN_FILE: 'The PIN file on the server is too short (at least 4 characters).',
      LOCKED_OUT: `Too many attempts. Try again in ${result.retryAfterSeconds ?? 60} s.`,
      UNKNOWN_CONNECTION: 'Not connected to the backend; try again in a moment.',
    }[result.status];
    await refreshRemote();
  }

  async function lockAgain() {
    await api.cdjRemoteLock();
    await refreshRemote();
  }

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
      remoteResult = { status: 'FAILED', message: 'Request failed - locked? Enter the PIN again.' };
      await refreshRemote();
    }
  }

  // [flag, label, verified on real CDJ-3000s]
  const flagRows: [keyof CdjDeckFlags, string, boolean][] = [
    ['tempoReset', 'Tempo Reset', true],
    ['sync', 'Sync', true],
    ['keySync', 'Key Sync', true],
    ['masterTempo', 'Master Tempo', true],
    ['quantize', 'Quantize', true],
    ['slip', 'Slip', true],
    ['vinylMode', 'Vinyl mode', true],
    ['autoCue', 'Auto Cue', true],
    ['tempoMaster', 'Tempo master', false],
  ];

  const show = (value: boolean | number) => (typeof value === 'boolean' ? (value ? 'on' : 'off') : String(value));
</script>

<div class="panel">
  <section>
    <h3>CDJs</h3>
    {#if error}<p class="warn">{error}</p>{/if}
    {#if link}
      <ul class="status">
        <li>Integration: {link.integrationEnabled ? 'on' : 'off'}, socket hooked: {link.transportAvailable ? 'yes' : 'no'}</li>
        <li>Replies: {link.settingsRepliesReceived} settings, {link.statusRepliesReceived} deck flags</li>
        <li>Settings changes: {link.controlEnabled ? 'allowed' : 'disabled'}</li>
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
      <h3>Deck state (player {device.deviceNumber})</h3>
      <p class="hint">Verified against real CDJ-3000s by switching the function on the player; * = matches a CDJ value but was not switched.</p>
      {#if device.flags}
        <table>
          <tbody>
            <tr><td>Track BPM</td><td>{device.flags.trackBpm.toFixed(2)}</td></tr>
            <tr><td>Pitch</td><td>{device.flags.pitchPercent.toFixed(2)} %</td></tr>
            {#each flagRows as [flag, label, verified]}
              <tr><td>{label}{verified ? '' : ' *'}</td><td>{show(device.flags[flag])}</td></tr>
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

    <section>
      <h3>Remote buttons (player {device.deviceNumber})</h3>
      {#if !remoteInfo}
        <p class="hint">Status unknown.</p>
      {:else if !remoteInfo.unlocked}
        <p class="hint">
          Remote buttons act immediately on the player, including during playback, so they are locked. Each device
          has to unlock them after every connection with the PIN from the file <code>{remoteInfo.pinFileName}</code>
          in the backend's data directory on the server (create it there; the app never creates it).
        </p>
        {#if !remoteInfo.pinFilePresent}<p class="warn">The PIN file does not exist (or is too short) on the server.</p>{/if}
        <form class="row" on:submit|preventDefault={unlock}>
          <input type="password" inputmode="numeric" autocomplete="off" placeholder="PIN" bind:value={pin} />
          <button type="submit" disabled={!pin || !remoteInfo.pinFilePresent}>Unlock</button>
        </form>
        {#if pinMessage}<p class="warn">{pinMessage}</p>{/if}
      {:else}
        <p class="warn">Unlocked for this connection. These buttons act immediately on the player. Not acknowledged by the CDJ.</p>
        <div class="row"><button on:click={lockAgain}>Lock again</button></div>
        {#each groups as group}
          <h4>{group}</h4>
          <div class="row">
            {#each link?.buttons.filter((b) => b.group === group) ?? [] as button (button.code)}
              {#if button.key === 'cue'}
                <button on:mousedown={() => press(button, 'down')} on:mouseup={() => press(button, 'up')}>{button.label} (hold)</button>
              {:else}
                <button on:click={() => press(button)}>{button.label}</button>
              {/if}
            {/each}
          </div>
        {/each}
        {#if remoteResult}<p class:ok={remoteResult.status === 'SENT'}>{remoteResult.status}{remoteResult.message ? `: ${remoteResult.message}` : ''}</p>{/if}
      {/if}
    </section>
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
