<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import { api } from '../stores/api';
  import { API_BASE } from '../websocket/connection';
  import type { OutputsConfig, NetworkInterfaceInfo, TelnetConsoleLine } from '../stores/types';

  let lines: TelnetConsoleLine[] = [];
  let commandInput = '';
  let consoleEl: HTMLDivElement;
  let socket: WebSocket | null = null;
  let settingsOpen = false;
  let outputsConfig: OutputsConfig | null = null;
  let interfaces: NetworkInterfaceInfo[] = [];
  let testBpm = 140;
  let statusMessage = '';

  function wsUrl(): string {
    const base = API_BASE || window.location.origin;
    return base.replace(/^http/, 'ws') + '/ws/telnet-console';
  }

  async function loadHistory() {
    try {
      lines = await api.getTelnetConsoleHistory();
      await tick();
      scrollToBottom();
    } catch (e) {
      // A failed initial history load should not prevent new live lines
      // from still streaming in via the WebSocket below.
    }
  }

  function connect() {
    socket = new WebSocket(wsUrl());
    socket.onmessage = (event) => {
      try {
        const line: TelnetConsoleLine = JSON.parse(event.data);
        lines = [...lines, line];
        tick().then(scrollToBottom);
      } catch (e) {
        // Ignore malformed frames rather than breaking the console.
      }
    };
    socket.onclose = () => {
      socket = null;
      reconnectTimer = setTimeout(connect, 2000);
    };
  }

  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  function scrollToBottom() {
    if (consoleEl) consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  async function sendCommand() {
    const command = commandInput.trim();
    if (!command) return;
    commandInput = '';
    await api.sendTelnetCommand(command);
  }

  function onKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter') sendCommand();
  }

  function directionLabel(direction: string): string {
    if (direction === 'SENT') return '→';
    if (direction === 'RECEIVED') return '←';
    return 'ⓘ';
  }

  // The real grandMA2 console sends ANSI color/cursor escape codes (e.g. for
  // its ASCII-art welcome banner) - strip them for display so the console
  // shows readable text instead of literal control-character garbage.
  // eslint-disable-next-line no-control-regex
  const ansiEscapePattern = /\x1b\[[0-9;]*[A-Za-z]/g;
  function stripAnsi(text: string): string {
    return text.replace(ansiEscapePattern, '');
  }

  function formatTimestamp(ts: number): string {
    const d = new Date(ts);
    return d.toLocaleTimeString('de-DE', { hour12: false });
  }

  async function loadOutputsSettings() {
    outputsConfig = await api.getOutputsConfig();
    interfaces = await api.getNetworkInterfaces();
  }

  async function toggleSettings() {
    settingsOpen = !settingsOpen;
    if (settingsOpen && !outputsConfig) {
      await loadOutputsSettings();
    }
  }

  async function saveOutputs() {
    if (!outputsConfig) return;
    await api.saveOutputsConfig(outputsConfig);
    statusMessage = 'GrandMA2 settings saved.';
  }

  async function testTelnet() {
    await api.grandMaTest(testBpm);
    statusMessage = `Test BPM ${testBpm.toFixed(2)} sent.`;
  }

  onMount(() => {
    loadHistory();
    connect();
  });

  onDestroy(() => {
    socket?.close();
    if (reconnectTimer) clearTimeout(reconnectTimer);
  });
</script>

<div class="panel">
  <div class="settings-toggle-row">
    <button class="toggle-button" on:click={toggleSettings}>
      {settingsOpen ? '▾' : '▸'} GrandMA2 Settings
    </button>
    {#if statusMessage}<span class="status">{statusMessage}</span>{/if}
  </div>

  {#if settingsOpen && outputsConfig}
    <div class="settings-drawer">
      <label title="Enables sending BPM data via Telnet to a grandMA2 console.">
        <input type="checkbox" bind:checked={outputsConfig.grandMa.enabled} />
        Enabled
      </label>
      <label title="IP address or hostname of the grandMA2 console.">
        Host
        <input bind:value={outputsConfig.grandMa.host} />
      </label>
      <label title="Telnet port of the grandMA2 console (default: 30000).">
        Port
        <input type="number" bind:value={outputsConfig.grandMa.port} />
      </label>
      <label title="Username for the Telnet login on the console.">
        Username
        <input bind:value={outputsConfig.grandMa.username} />
      </label>
      <label title="Password for the Telnet login on the console.">
        Password
        <input type="password" bind:value={outputsConfig.grandMa.password} />
      </label>
      <label class="wide-field" title="Template for the login command. Placeholders are automatically replaced with the configured credentials.">
        Login Template
        <textarea rows="2" bind:value={outputsConfig.grandMa.loginTemplate}></textarea>
      </label>
      <label class="wide-field" title="Template for the BPM command that is sent. Placeholders are automatically replaced with the current BPM value.">
        Command Template
        <textarea rows="2" bind:value={outputsConfig.grandMa.commandTemplate}></textarea>
      </label>
      <label title="Minimum BPM change before a new command is sent.">
        Min. BPM Change
        <input type="number" step="0.01" bind:value={outputsConfig.grandMa.minimumChange} />
      </label>
      <label title="Maximum number of commands sent per second, to avoid overloading the console.">
        Max. Send Rate (Hz)
        <input type="number" bind:value={outputsConfig.grandMa.maximumRateHz} />
      </label>
      <label title="Automatically reconnects if the Telnet connection is interrupted.">
        <input type="checkbox" bind:checked={outputsConfig.grandMa.reconnect} />
        Auto-Reconnect
      </label>
      <label title="Periodically sends a keepalive signal to maintain the connection.">
        <input type="checkbox" bind:checked={outputsConfig.grandMa.keepalive} />
        Keepalive
      </label>
      <h4>Network Adapter</h4>
      <label title="Determines which network adapter the Telnet connection to the grandMA2 console uses - independent of the adapter used for Pro DJ Link.">
        Interface Mode
        <select bind:value={outputsConfig.grandMa.networkInterfaceMode}>
          <option value="AUTOMATIC">Automatic</option>
          <option value="MANUAL">Manual</option>
        </select>
      </label>
      {#if outputsConfig.grandMa.networkInterfaceMode === 'MANUAL'}
        <label title="The network interface used for the Telnet connection to the grandMA2 console.">
          Interface
          <select bind:value={outputsConfig.grandMa.manualInterfaceName}>
            {#each interfaces as iface}
              <option value={iface.name}>{iface.displayName} ({iface.addresses.join(', ')})</option>
            {/each}
          </select>
        </label>
      {/if}
      <div class="row">
        <button on:click={saveOutputs}>Save Settings</button>
        <label>Test BPM <input type="number" step="0.01" bind:value={testBpm} /></label>
        <button on:click={testTelnet}>Send TEST Command</button>
      </div>
    </div>
  {/if}

  <div class="console" bind:this={consoleEl}>
    {#each lines as line, i (i)}
      <div class="line direction-{line.direction}">
        <span class="timestamp">{formatTimestamp(line.timestamp)}</span>
        <span class="direction">{directionLabel(line.direction)}</span>
        <span class="text">{stripAnsi(line.text)}</span>
      </div>
    {/each}
    {#if lines.length === 0}
      <div class="empty">No Telnet communication yet.</div>
    {/if}
  </div>

  <div class="input-row">
    <input
      placeholder="Enter command and press Enter…"
      bind:value={commandInput}
      on:keydown={onKeydown}
    />
    <button on:click={sendCommand} disabled={!commandInput.trim()}>Send</button>
  </div>
</div>

<style>
  .panel {
    display: flex;
    flex-direction: column;
    height: calc(100vh - 140px);
    padding: 16px;
    color: #eee;
    font-family: system-ui, sans-serif;
    gap: 10px;
  }
  .settings-toggle-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .toggle-button {
    background: #16171b;
    color: #ddd;
    border: 1px solid #2a2b31;
    border-radius: 6px;
    padding: 6px 12px;
    cursor: pointer;
    font-size: 13px;
    align-self: flex-start;
  }
  .toggle-button:hover {
    border-color: #4a4b52;
  }
  .status {
    font-size: 12px;
    color: #a5d6a7;
  }
  .settings-drawer {
    display: flex;
    flex-direction: column;
    gap: 10px;
    background: #16171b;
    border: 1px solid #24252b;
    border-radius: 10px;
    padding: 16px 18px;
  }
  .settings-drawer label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
  }
  .settings-drawer label.wide-field {
    align-items: flex-start;
  }
  .settings-drawer input,
  .settings-drawer select,
  .settings-drawer textarea {
    background: #0e0f12;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 6px;
    padding: 5px 8px;
    font-family: inherit;
    font-size: 13px;
  }
  .settings-drawer textarea {
    width: 100%;
    max-width: 520px;
    min-height: 44px;
    resize: vertical;
  }
  .settings-drawer h4 {
    margin: 10px 0 -4px 0;
    font-size: 12px;
    color: #999;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    border-top: 1px solid #24252b;
    padding-top: 10px;
  }
  .settings-drawer button {
    background: #2a2b31;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 6px;
    padding: 6px 14px;
    cursor: pointer;
    font-size: 13px;
  }
  .row {
    display: flex;
    gap: 10px;
    align-items: center;
  }
  .console {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    background: #0a0b0d;
    border: 1px solid #24252b;
    border-radius: 8px;
    padding: 10px 12px;
    font-family: ui-monospace, Menlo, monospace;
    font-size: 12.5px;
  }
  .line {
    display: flex;
    gap: 8px;
    padding: 1px 0;
    white-space: pre-wrap;
    word-break: break-all;
  }
  .timestamp {
    color: #666;
    flex-shrink: 0;
  }
  .direction {
    flex-shrink: 0;
    width: 14px;
    text-align: center;
  }
  .direction-SENT .direction {
    color: #4fc3f7;
  }
  .direction-RECEIVED .direction {
    color: #81c784;
  }
  .direction-INFO .direction {
    color: #ffb300;
  }
  .direction-INFO .text {
    color: #999;
    font-style: italic;
  }
  .empty {
    color: #555;
    font-style: italic;
  }
  .input-row {
    display: flex;
    gap: 8px;
  }
  .input-row input {
    flex: 1;
    background: #16171b;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 6px;
    padding: 8px 10px;
    font-family: ui-monospace, Menlo, monospace;
    font-size: 13px;
  }
  .input-row button {
    background: #2a2b31;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 6px;
    padding: 8px 16px;
    cursor: pointer;
    font-size: 13px;
  }
  .input-row button:disabled {
    opacity: 0.4;
    cursor: default;
  }
</style>
