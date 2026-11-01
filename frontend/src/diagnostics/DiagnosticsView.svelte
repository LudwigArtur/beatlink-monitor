<script lang="ts">
  import type { DiagnosticsSnapshot } from '../stores/types';

  export let diagnostics: DiagnosticsSnapshot | null;
</script>

<div class="page">
  {#if diagnostics}
    <section class="card">
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
          <tr><td>Log directory</td><td class="monospace">{diagnostics.logDirectory}</td></tr>
        </tbody>
      </table>
    </section>

    <section class="card">
      <h3>Pro DJ Link network</h3>
      <table>
        <tbody>
          <tr><td>Interface</td><td>{diagnostics.network.interfaceName ?? '—'}</td></tr>
          <tr><td>Bound address</td><td>{diagnostics.network.boundAddress ?? '—'}</td></tr>
          <tr>
            <td>Addresses of the interface now</td>
            <td class:bad={diagnostics.network.addressChanged}>
              {diagnostics.network.currentAddresses.join(', ') || '—'}
              {#if diagnostics.network.addressChanged}(differs from the bound address: restart the application){/if}
            </td>
          </tr>
          <tr><td>Device dropouts (10 min)</td><td class:warn={diagnostics.network.deviceDropouts >= 4}>{diagnostics.network.deviceDropouts}</td></tr>
          <tr>
            <td>Players without track information</td>
            <td class:warn={diagnostics.network.metadataUnavailablePlayers.length > 0}>
              {diagnostics.network.metadataUnavailablePlayers.map((p) => `#${p}`).join(', ') || '—'}
            </td>
          </tr>
          <tr>
            <td>Database port not answering</td>
            <td>{diagnostics.network.unreachablePlayers.map((p) => `#${p}`).join(', ') || '—'}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card">
      <p class="hint">
        The <code>beatlink-monitor.log</code> file in the directory above contains detailed technical logs
        (including Pro DJ Link connection status) - helpful if, for example, waveforms, titles, or the playback
        position are not displayed correctly with real hardware.
      </p>
      <p class="hint">
        The own virtual player number is assigned automatically by default in a collision-safe way: on startup,
        the network is briefly observed to detect all numbers already in use. The lowest free number is then claimed
        (preferring the range 1-4, since that is required for metadata and waveform requests) and defended against
        simultaneous conflicts via the Pro DJ Link protocol itself. Alternatively, a specific number can be pinned
        under Settings → General → Network (Pro DJ Link) → Virtual player number; numbers already in use by a real device cannot be selected there.
      </p>
    </section>
  {:else}
    <p class="muted page">Waiting for diagnostics…</p>
  {/if}
</div>

<style>
  td:first-child {
    color: var(--muted);
    width: 240px;
  }
  .monospace {
    font-family: var(--mono);
    font-size: 12px;
    word-break: break-all;
  }
  .hint:last-child {
    margin-bottom: 0;
  }
  .hint + .hint {
    margin-top: 10px;
  }
</style>
