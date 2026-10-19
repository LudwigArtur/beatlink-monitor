<script lang="ts">
  import type { DiagnosticsSnapshot } from '../stores/types';

  export let diagnostics: DiagnosticsSnapshot | null;
</script>

<div class="panel">
  {#if diagnostics}
    <table>
      <tbody>
        <tr><td>Application version</td><td>{diagnostics.applicationVersion}</td></tr>
        <tr><td>Java version</td><td>{diagnostics.javaVersion}</td></tr>
        <tr><td>Operating system</td><td>{diagnostics.operatingSystem}</td></tr>
        <tr><td>Network interfaces</td><td>{diagnostics.networkInterfaces.join(', ') || '—'}</td></tr>
        <tr><td>Local IP</td><td>{diagnostics.localIp ?? '—'}</td></tr>
        <tr><td>Pro DJ Link running</td><td>{diagnostics.proDjLinkRunning ? 'Yes' : 'No'}</td></tr>
        <tr><td>Discovered devices</td><td>{diagnostics.discoveredDevices}</td></tr>
        <tr><td>Own virtual player number</td><td>{diagnostics.ownDeviceNumber ?? '—'}</td></tr>
        <tr><td>Current master</td><td>{diagnostics.currentMasterPlayer ?? '—'}</td></tr>
        <tr><td>Master BPM</td><td>{diagnostics.masterBpm?.toFixed(2) ?? '—'}</td></tr>
        <tr><td>Ableton Link status</td><td>{diagnostics.abletonLinkConnected ? 'Connected' : 'Disconnected'}</td></tr>
        <tr><td>Telnet status</td><td>{diagnostics.telnetConnected ? 'Connected' : 'Disconnected'}</td></tr>
        <tr><td>Last Telnet command</td><td>{diagnostics.lastTelnetCommand ?? '—'}</td></tr>
        <tr><td>UI WebSocket connections</td><td>{diagnostics.uiWebSocketConnections}</td></tr>
        <tr><td>Simulator status</td><td>{diagnostics.simulatorRunning ? 'Running' : 'Stopped'}</td></tr>
        <tr><td>Log directory</td><td class="monospace">{diagnostics.logDirectory}</td></tr>
      </tbody>
    </table>
    <p class="hint">
      The <code>beatlink-monitor.log</code> file in the directory above contains detailed technical logs
      (including Pro DJ Link connection status) - helpful if, for example, waveforms, titles, or the playback
      position are not displayed correctly with real hardware.
    </p>
    <p class="hint">
      The "Own virtual player number" is assigned automatically by default in a collision-safe way: on startup,
      the network is briefly observed to detect all numbers already in use - regardless of which four of the
      numbers 1-6 are occupied by real devices. The lowest free number is then claimed (preferring the range 1-4,
      since that is required for metadata/waveform requests) and defended against simultaneous conflicts via the
      Pro DJ Link protocol itself - no manual configuration is required. Alternatively, a specific player number
      can be pinned manually under Settings -> Network (Pro DJ Link) -> Virtual Player Number; numbers already in
      use by a real device are shown there and cannot be selected.
    </p>
  {:else}
    <p>Waiting for diagnostics…</p>
  {/if}
</div>

<style>
  .panel {
    padding: 16px;
    color: #eee;
    font-family: system-ui, sans-serif;
  }
  table {
    border-collapse: collapse;
    width: 100%;
    max-width: 600px;
  }
  td {
    padding: 6px 12px;
    border-bottom: 1px solid #2a2b31;
    font-size: 13px;
  }
  td:first-child {
    color: #999;
    width: 220px;
  }
  .monospace {
    font-family: ui-monospace, Menlo, monospace;
    font-size: 12px;
    word-break: break-all;
  }
  .hint {
    max-width: 600px;
    font-size: 12px;
    color: #888;
    margin-top: 10px;
  }
  code {
    font-family: ui-monospace, Menlo, monospace;
  }
</style>
