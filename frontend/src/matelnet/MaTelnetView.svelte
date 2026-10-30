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
    return d.toLocaleTimeString('en-GB', { hour12: false });
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

<div class="page wide panel">
  <div class="row">
    <button on:click={toggleSettings} aria-expanded={settingsOpen}>
      {settingsOpen ? '▾' : '▸'} GrandMA2 settings
    </button>
    {#if statusMessage}<span class="ok">{statusMessage}</span>{/if}
  </div>

  {#if settingsOpen && outputsConfig}
    <section class="card drawer">
      <label class="check" title="Enables sending BPM data via Telnet to a grandMA2 console.">
        <input type="checkbox" bind:checked={outputsConfig.grandMa.enabled} />
        Enabled
      </label>
      <label class="field" title="IP address or hostname of the grandMA2 console.">
        <span class="label">Host</span>
        <span class="control"><input bind:value={outputsConfig.grandMa.host} /></span>
      </label>
      <label class="field" title="Telnet port of the grandMA2 console (default: 30000).">
        <span class="label">Port</span>
        <span class="control"><input type="number" bind:value={outputsConfig.grandMa.port} /></span>
      </label>
      <label class="field" title="Username for the Telnet login on the console.">
        <span class="label">Username</span>
        <span class="control"><input bind:value={outputsConfig.grandMa.username} /></span>
      </label>
      <label class="field" title="Password for the Telnet login on the console.">
        <span class="label">Password</span>
        <span class="control"><input type="password" bind:value={outputsConfig.grandMa.password} /></span>
      </label>
      <label class="field" title="Template for the login command. Placeholders are automatically replaced with the configured credentials.">
        <span class="label">Login template</span>
        <span class="control"><textarea rows="2" bind:value={outputsConfig.grandMa.loginTemplate}></textarea></span>
      </label>
      <label class="field" title="Template for the BPM command that is sent. Placeholders are automatically replaced with the current BPM value.">
        <span class="label">Command template</span>
        <span class="control"><textarea rows="2" bind:value={outputsConfig.grandMa.commandTemplate}></textarea></span>
      </label>
      <label class="field" title="Minimum BPM change before a new command is sent.">
        <span class="label">Minimum BPM change</span>
        <span class="control"><input type="number" step="0.01" bind:value={outputsConfig.grandMa.minimumChange} /></span>
      </label>
      <label class="field" title="Maximum number of commands sent per second, to avoid overloading the console.">
        <span class="label">Maximum send rate (Hz)</span>
        <span class="control"><input type="number" bind:value={outputsConfig.grandMa.maximumRateHz} /></span>
      </label>
      <label class="check" title="Automatically reconnects if the Telnet connection is interrupted.">
        <input type="checkbox" bind:checked={outputsConfig.grandMa.reconnect} />
        Auto-reconnect
      </label>
      <label class="check" title="Periodically sends a keepalive signal to maintain the connection.">
        <input type="checkbox" bind:checked={outputsConfig.grandMa.keepalive} />
        Keepalive
      </label>

      <h4>Network adapter</h4>
      <label class="field" title="Determines which network adapter the Telnet connection to the grandMA2 console uses - independent of the adapter used for Pro DJ Link.">
        <span class="label">Interface mode</span>
        <span class="control">
          <select bind:value={outputsConfig.grandMa.networkInterfaceMode}>
            <option value="AUTOMATIC">Automatic</option>
            <option value="MANUAL">Manual</option>
          </select>
        </span>
      </label>
      {#if outputsConfig.grandMa.networkInterfaceMode === 'MANUAL'}
        <label class="field" title="The network interface used for the Telnet connection to the grandMA2 console.">
          <span class="label">Interface</span>
          <span class="control">
            <select bind:value={outputsConfig.grandMa.manualInterfaceName}>
              {#if outputsConfig.grandMa.manualInterfaceName && !interfaces.some((i) => i.name === outputsConfig?.grandMa.manualInterfaceName)}
                <option value={outputsConfig.grandMa.manualInterfaceName}>{outputsConfig.grandMa.manualInterfaceName} (not available)</option>
              {/if}
              {#each interfaces as iface}
                <option value={iface.name}>{iface.displayName} ({iface.addresses.join(', ')})</option>
              {/each}
            </select>
          </span>
        </label>
      {/if}

      <div class="row actions">
        <button class="primary" on:click={saveOutputs}>Save</button>
        <span class="spacer"></span>
        <label class="inline">Test BPM <input type="number" step="0.01" bind:value={testBpm} /></label>
        <button on:click={testTelnet}>Send test command</button>
      </div>
    </section>
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
      class="command"
      placeholder="Enter command and press Enter…"
      bind:value={commandInput}
      on:keydown={onKeydown}
    />
    <button class="primary" on:click={sendCommand} disabled={!commandInput.trim()}>Send</button>
  </div>
</div>

<style>
  .actions {
    margin-top: 10px;
  }
  .inline {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
  }
  .console {
    flex: none;
    height: 60vh;
    min-height: 240px;
    overflow-y: auto;
    background: #0a0b0d;
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 12px;
    font-family: var(--mono);
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
    color: var(--accent);
  }
  .direction-RECEIVED .direction {
    color: var(--ok);
  }
  .direction-INFO .direction {
    color: var(--warn);
  }
  .direction-INFO .text {
    color: var(--muted);
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
  .command {
    flex: 1;
    font-family: var(--mono);
  }
</style>
