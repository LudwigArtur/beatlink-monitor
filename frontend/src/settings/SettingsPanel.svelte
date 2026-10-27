<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '../stores/api';
  import { diagnostics, players } from '../websocket/connection';
  import type { ApplicationConfig, NetworkInterfaceInfo, OutputsConfig, PlayerSlotConfig } from '../stores/types';
  import {
    activeProfile,
    deletePreset,
    loadPreset,
    presetNames,
    savePresetAs,
    setBackgroundColor,
    setFullscreenFlag,
    setGlobalScale,
    setInactiveDeckBrightnessPercent,
    setSlot,
    setWaveformSmoothingFactor,
    updateProfile,
  } from '../stores/layout';

  let appConfig: ApplicationConfig | null = null;
  let outputsConfig: OutputsConfig | null = null;
  let interfaces: NetworkInterfaceInfo[] = [];
  let statusMessage = '';
  let newPresetName = '';
  let selectedPreset = '';

  // Device numbers currently claimed by real Pro DJ Link hardware on the
  // network, live from the existing WebSocket player feed - used to prevent
  // the user from picking a virtual player number that would collide with
  // a real device (our own currently-active virtual number is excluded, so
  // re-selecting the number already in use by this app itself is allowed).
  $: occupiedDeviceNumbers = new Set(
    $players
      .filter((p) => p.connected && p.deviceNumber !== $diagnostics?.ownDeviceNumber)
      .map((p) => p.deviceNumber),
  );

  onMount(async () => {
    appConfig = await api.getApplicationConfig();
    outputsConfig = await api.getOutputsConfig();
    interfaces = await api.getNetworkInterfaces();
  });

  async function saveApp() {
    if (!appConfig) return;
    const result = await api.saveApplicationConfig(appConfig);
    statusMessage = result.restartRequired
      ? 'Settings saved. Restart the application (quit and reopen) for the network/player-number change to take effect.'
      : 'General settings saved.';
  }

  async function saveOutputs() {
    if (!outputsConfig) return;
    await api.saveOutputsConfig(outputsConfig);
    statusMessage = 'Output settings saved.';
  }

  async function onSavePreset() {
    if (!newPresetName.trim()) return;
    await savePresetAs(newPresetName);
    statusMessage = `Preset '${newPresetName}' saved.`;
    newPresetName = '';
  }

  async function onLoadPreset() {
    if (!selectedPreset) return;
    await loadPreset(selectedPreset);
    statusMessage = `Preset '${selectedPreset}' loaded.`;
  }

  async function onDeletePreset() {
    if (!selectedPreset) return;
    const name = selectedPreset;
    await deletePreset(name);
    selectedPreset = '';
    statusMessage = `Preset '${name}' deleted.`;
  }

  // Each of the six Monitor/Live View slots is now configured through a
  // single dropdown: a fixed player number, the current tempo master, or
  // disabled - no manual sizing/positioning/ordering anymore
  // (requirements specification redesign: the Monitor page auto-arranges itself).
  type SlotChoice = `PLAYER_${number}` | 'MASTER' | 'DISABLED';

  function slotChoice(slot: PlayerSlotConfig): SlotChoice {
    if (!slot.visible) return 'DISABLED';
    if (slot.sourceType === 'CURRENT_MASTER') return 'MASTER';
    return `PLAYER_${slot.playerNumber ?? 1}`;
  }

  function onSlotChoiceChange(slotNumber: number, value: string) {
    if (value === 'DISABLED') {
      setSlot(slotNumber, { visible: false });
    } else if (value === 'MASTER') {
      setSlot(slotNumber, { visible: true, sourceType: 'CURRENT_MASTER', playerNumber: null });
    } else {
      const playerNumber = Number(value.replace('PLAYER_', ''));
      setSlot(slotNumber, { visible: true, sourceType: 'PLAYER', playerNumber });
    }
  }
</script>

<div class="panel">
  {#if statusMessage}<div class="status">{statusMessage}</div>{/if}

  <section>
    <h3>Presets</h3>
    <p class="hint">
      All settings are automatically saved continuously and reloaded on the next start. Here you can additionally
      save the current state manually under a name, to return to it later or switch between multiple
      configurations.
    </p>
    <div class="row">
      <input placeholder="Preset name" bind:value={newPresetName} />
      <button on:click={onSavePreset} disabled={!newPresetName.trim()}>Save Current State</button>
    </div>
    <div class="row">
      <select bind:value={selectedPreset}>
        <option value="" disabled>Select preset…</option>
        {#each $presetNames as name}
          <option value={name}>{name}</option>
        {/each}
      </select>
      <button on:click={onLoadPreset} disabled={!selectedPreset}>Load</button>
      <button on:click={onDeletePreset} disabled={!selectedPreset}>Delete</button>
    </div>
  </section>

  <section>
    <h3>Display</h3>
    <label title="Scales the entire user interface up or down.">
      Scale
      <input type="range" min="0.5" max="2" step="0.05" value={$activeProfile.globalScale}
        on:input={(e) => setGlobalScale(Number((e.target as HTMLInputElement).value))} />
      <span>{$activeProfile.globalScale.toFixed(2)}x</span>
    </label>
    <label title="Background color of the entire application.">
      Background Color
      <input type="color" value={$activeProfile.backgroundColor}
        on:input={(e) => setBackgroundColor((e.target as HTMLInputElement).value)} />
    </label>
    <label title="Brightness of Live View decks that are not On-Air, while 'Dim inactive decks' is active.">
      Inactive Deck Brightness
      <input type="range" min="5" max="95" step="5" value={$activeProfile.inactiveDeckBrightnessPercent}
        on:input={(e) => setInactiveDeckBrightnessPercent(Number((e.target as HTMLInputElement).value))} />
      <span>{$activeProfile.inactiveDeckBrightnessPercent}%</span>
    </label>
    <label title="How smoothly the Live View waveform playhead corrects small drift between its predicted and actually-reported position. Lower (toward 'Smooth') moves very gradually and avoids any visible snapping, but lags slightly behind during fast changes; higher (toward 'Precise') catches up faster but small corrections become a bit more noticeable.">
      Waveform Smoothness
      <input type="range" min="0.02" max="0.25" step="0.01" value={$activeProfile.waveformSmoothingFactor}
        on:input={(e) => setWaveformSmoothingFactor(Number((e.target as HTMLInputElement).value))} />
      <span>{$activeProfile.waveformSmoothingFactor <= 0.08 ? 'Smooth' : $activeProfile.waveformSmoothingFactor >= 0.18 ? 'Precise' : 'Balanced'}</span>
    </label>
    <label title="Starts the application in fullscreen mode.">
      <input type="checkbox" checked={$activeProfile.fullscreen} on:change={(e) => setFullscreenFlag((e.target as HTMLInputElement).checked)} />
      Fullscreen
    </label>
  </section>

  <section>
    <h3>Player Slots</h3>
    <p class="hint">
      Each of the six slots shows either a fixed player, the current tempo master, or is disabled.
      The Monitor and Live View layouts automatically adapt to the number of active slots.
    </p>
    <div class="slot-list">
      {#each $activeProfile.playerSlots as slot (slot.slot)}
        <label class="slot-row">
          <span class="slot-index">Slot {slot.slot}</span>
          <select value={slotChoice(slot)} on:change={(e) => onSlotChoiceChange(slot.slot, (e.target as HTMLSelectElement).value)}>
            {#each [1, 2, 3, 4, 5, 6] as n}
              <option value={`PLAYER_${n}`}>Player {n}</option>
            {/each}
            <option value="MASTER">Current Master</option>
            <option value="DISABLED">Disabled</option>
          </select>
        </label>
      {/each}
    </div>
  </section>

  {#if appConfig}
    <section>
      <h3>General</h3>
      <label title="The TCP port through which the web UI is reachable locally. The app must be restarted after a change.">
        Web UI Port
        <input type="number" bind:value={appConfig.webUiPort} />
      </label>
      <label title="Allows access to the web UI from other devices on the same network, not just from this computer.">
        <input type="checkbox" bind:checked={appConfig.allowLanAccess} />
        Allow LAN Access
      </label>
      <label title="Number of decimal places shown for BPM values.">
        BPM Decimal Places
        <input type="number" min="0" max="4" bind:value={appConfig.bpmDecimalPlaces} />
      </label>
      <button on:click={saveApp}>Save General Settings</button>
    </section>

    <section>
      <h3>Network (Pro DJ Link)</h3>
      <label title="Automatic selects the network interface itself; Manual allows explicitly choosing a network interface for Pro DJ Link.">
        Interface Mode
        <select bind:value={appConfig.networkInterfaceMode}>
          <option value="AUTOMATIC">Automatic</option>
          <option value="MANUAL">Manual</option>
        </select>
      </label>
      {#if appConfig.networkInterfaceMode === 'MANUAL'}
        <label title="The network interface used to search for Pro DJ Link devices.">
          Interface
          <select bind:value={appConfig.manualInterfaceName}>
            {#each interfaces as iface}
              <option value={iface.name}>{iface.displayName} ({iface.addresses.join(', ')})</option>
            {/each}
          </select>
        </label>
      {/if}
      <label
        class="wide-field"
        title="Pins our own virtual CDJ's player number on the Pro DJ Link network instead of letting beat-link choose it automatically. Numbers already in use by a real device are shown greyed out and cannot be selected."
      >
        Virtual Player Number
        <select bind:value={appConfig.preferredDeviceNumber}>
          <option value={0}>Automatic (recommended)</option>
          {#each Array.from({ length: 4 }, (_, i) => i + 1) as n}
            <option value={n} disabled={occupiedDeviceNumbers.has(n)}>
              Player {n}{occupiedDeviceNumbers.has(n) ? ' (in use by a real device)' : ''}
            </option>
          {/each}
          {#each Array.from({ length: 11 }, (_, i) => i + 5) as n}
            <option value={n} disabled={occupiedDeviceNumbers.has(n)}>
              Player {n}{occupiedDeviceNumbers.has(n) ? ' (in use by a real device)' : ' (not recommended, see warning below)'}
            </option>
          {/each}
        </select>
      </label>
      <label
        class="wide-field"
        title="Which waveform style to request from real CDJ-3000s. RGB is the widely-compatible standard color waveform; Three-Band requests the CDJ-3000's native, richer per-frequency-band style instead. Hardware that cannot provide the requested style automatically falls back to what it can."
      >
        Waveform Style
        <select bind:value={appConfig.waveformStylePreference}>
          <option value="RGB">RGB (standard, recommended)</option>
          <option value="THREE_BAND">Three-Band (CDJ-3000 native)</option>
        </select>
      </label>
      <p class="hint warning">
        Changing the network interface, virtual player number, or waveform style requires a full application
        restart (quit and reopen) to safely take effect - these are deliberately no longer applied live. A player
        number outside 1-4 also prevents fetching metadata/waveforms/track titles from real players (a hard
        Pioneer protocol limitation, not a bug), and repeatedly switching these settings while real players are
        connected has been confirmed to destabilize a real player's own device-number claim. Set these once
        before a session, not during one.
      </p>
      <button on:click={saveApp}>Save Network Settings</button>
    </section>

    <section>
      <h3>Mixer</h3>
      <p class="hint">
        Reads the live state of a DJM mixer (EQ, faders, crossfader, FX), its utility settings and firmware version.
        Checked on one rig (DJM-V10, CDJ-3000). Changing a setting only happens when you press Write in the Mixer
        tab, and counts as confirmed only after the mixer reports the new value back. See Docs/MIXER_CONTROL.md.
      </p>
      <label title="Listens for the mixer's live knob and fader positions and allows reading its utility settings. Hooks into the Pro DJ Link socket at startup, so a restart is required.">
        <input type="checkbox" bind:checked={appConfig.mixerIntegrationEnabled} />
        Enable mixer integration (restart required)
      </label>
      <label title="Additionally allows changing mixer utility settings (balance, limiter, attenuation, mic routing...) from the Mixer tab.">
        <input type="checkbox" bind:checked={appConfig.mixerControlEnabled} disabled={!appConfig.mixerIntegrationEnabled} />
        Allow changing mixer settings
      </label>
      <label title="Announces this app as an additional third-party participant (category 5, device number 54-57, like ShowKontrol) next to the virtual CDJ. A DJM-V10 only sent its live EQ/fader state to such a peer (observed). It adds one more device to your Pro DJ Link network.">
        <input type="checkbox" bind:checked={appConfig.mixerAnnounceIdentity} disabled={!appConfig.mixerIntegrationEnabled} />
        Announce as third-party device (needed for the live state)
      </label>
      <label title="Reads every CDJ's settings and deck flags (harmless requests, repeated every few seconds). Uses the same socket hook as the mixer integration, so a restart is required.">
        <input type="checkbox" bind:checked={appConfig.cdjIntegrationEnabled} />
        Read CDJ settings and deck flags (restart required)
      </label>
      <label title="Allows changing CDJ settings from the CDJs tab; confirmed only after the CDJ reports the new value.">
        <input type="checkbox" bind:checked={appConfig.cdjControlEnabled} disabled={!appConfig.cdjIntegrationEnabled} />
        Allow changing CDJ settings
      </label>
      <label class="wide-field" title="IPv4 address of the mixer on the local network. Leave empty to use the mixer found by Pro DJ Link discovery.">
        Mixer address (optional)
        <input placeholder="e.g. 169.254.12.34" value={appConfig.mixerAddress ?? ''}
          on:input={(e) => (appConfig!.mixerAddress = (e.target as HTMLInputElement).value.trim() || null)} />
      </label>
      <button on:click={saveApp}>Save Mixer Settings</button>

      <h4>EQ controls waveform brightness</h4>
      <p class="hint">
        With live mixer data, each channel's EQ dims the matching part of that player's waveform: low = red (RGB) /
        blue (three-band), mid = green / amber, high = blue / white. Channel N is taken to be fed by player N.
        Knob fully down (raw 0) shows the minimum brightness, the center detent (raw 128) 100 %. Above the detent
        the band glows up to the boost value at raw 255. The waveform data only has three bands: on a 4-band
        mixer (e.g. DJM-V10) the mid part follows the average of Hi-Mid and Lo-Mid.
      </p>
      <label>
        <input type="checkbox" checked={$activeProfile.eqWaveformEnabled}
          on:change={(e) => updateProfile({ eqWaveformEnabled: (e.target as HTMLInputElement).checked })} />
        Enabled
      </label>
      <label title="Raw EQ value of a knob turned fully down (minimum brightness).">
        Raw value, knob fully down
        <input type="number" min="0" max="255" value={$activeProfile.eqRawMin}
          on:change={(e) => updateProfile({ eqRawMin: Number((e.target as HTMLInputElement).value) })} />
      </label>
      <label title="Raw EQ value of a knob at its center detent (100 % brightness).">
        Raw value, knob at center
        <input type="number" min="0" max="255" value={$activeProfile.eqRawCenter}
          on:change={(e) => updateProfile({ eqRawCenter: Number((e.target as HTMLInputElement).value) })} />
      </label>
      <label title="Brightness of a band with its knob fully down, in percent.">
        Minimum brightness (%)
        <input type="number" min="0" max="100" value={Math.round($activeProfile.eqMinBrightness * 100)}
          on:change={(e) => updateProfile({ eqMinBrightness: Math.min(100, Math.max(0, Number((e.target as HTMLInputElement).value))) / 100 })} />
      </label>
      <label title="Extra glow for a knob turned fully up (raw 255), in percent on top of 100 %. 0 = no boost.">
        Boost above center (%)
        <input type="number" min="0" max="100" value={Math.round($activeProfile.eqBoostGlow * 100)}
          on:change={(e) => updateProfile({ eqBoostGlow: Math.min(100, Math.max(0, Number((e.target as HTMLInputElement).value))) / 100 })} />
      </label>
      <label title="Which EQ knob dims the mid part of the waveform.">
        Mid band follows
        <select value={$activeProfile.eqMidSource}
          on:change={(e) => updateProfile({ eqMidSource: (e.target as HTMLSelectElement).value as 'AUTO' | 'AVERAGE' | 'HI_MID' | 'LO_MID' })}>
          <option value="AUTO">Automatic (3-band: Hi-Mid, 4-band: average)</option>
          <option value="AVERAGE">Average of Hi-Mid and Lo-Mid</option>
          <option value="HI_MID">Hi-Mid only</option>
          <option value="LO_MID">Lo-Mid only</option>
        </select>
      </label>
    </section>
  {/if}

  {#if outputsConfig}
    <section>
      <h3>Ableton Link</h3>
      <label><input type="checkbox" bind:checked={outputsConfig.abletonLink.enabled} /> Enabled</label>
      <label>Carabiner Host <input bind:value={outputsConfig.abletonLink.carabinerHost} /></label>
      <label>Carabiner Port <input type="number" bind:value={outputsConfig.abletonLink.carabinerPort} /></label>
      <label>Quantum <input type="number" bind:value={outputsConfig.abletonLink.quantum} /></label>
      <label>
        Position Source
        <select bind:value={outputsConfig.abletonLink.positionSource}>
          <option value="AUTO">Auto</option>
          <option value="PRECISE">Precise</option>
          <option value="SMOOTHED">Smoothed</option>
        </select>
      </label>
      <h4>Network Adapter</h4>
      <label title="Determines which network adapter is used to connect to the Carabiner process - independent of the adapter used for Pro DJ Link.">
        Interface Mode
        <select bind:value={outputsConfig.abletonLink.networkInterfaceMode}>
          <option value="AUTOMATIC">Automatic</option>
          <option value="MANUAL">Manual</option>
        </select>
      </label>
      {#if outputsConfig.abletonLink.networkInterfaceMode === 'MANUAL'}
        <label title="The network interface used to connect to the Carabiner process.">
          Interface
          <select bind:value={outputsConfig.abletonLink.manualInterfaceName}>
            {#each interfaces as iface}
              <option value={iface.name}>{iface.displayName} ({iface.addresses.join(', ')})</option>
            {/each}
          </select>
        </label>
      {/if}
      <button on:click={saveOutputs}>Save Ableton Link Settings</button>
    </section>
  {/if}
</div>

<style>
  .panel {
    padding: 20px;
    color: #eee;
    font-family: system-ui, sans-serif;
    max-width: 820px;
  }
  section {
    margin-bottom: 20px;
    padding: 16px 18px;
    background: #16171b;
    border: 1px solid #24252b;
    border-radius: 10px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  h3 {
    margin: 0 0 4px 0;
    font-size: 15px;
  }
  h4 {
    margin: 10px 0 -4px 0;
    font-size: 12px;
    color: #999;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    border-top: 1px solid #24252b;
    padding-top: 10px;
  }
  label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
  }
  input, select {
    background: #0e0f12;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 6px;
    padding: 5px 8px;
    font-family: inherit;
    font-size: 13px;
  }
  button {
    align-self: flex-start;
    background: #2a2b31;
    color: #eee;
    border: 1px solid #3a3b42;
    border-radius: 6px;
    padding: 6px 14px;
    cursor: pointer;
    font-size: 13px;
    transition: background 0.15s ease, border-color 0.15s ease;
  }
  button:hover {
    background: #34353d;
    border-color: #4a4b52;
  }
  .status {
    background: #1b3a1f;
    color: #a5d6a7;
    padding: 8px 12px;
    border-radius: 6px;
    margin-bottom: 14px;
    font-size: 12px;
  }
  .row {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .row select {
    flex: 1;
    max-width: 240px;
  }
  .hint {
    font-size: 12px;
    color: #888;
    margin: -2px 0 2px 0;
  }
  .hint.warning {
    color: #ffb300;
    background: #2a2308;
    border: 1px solid #4a3b10;
    border-radius: 6px;
    padding: 6px 10px;
  }
  .wide-field {
    align-items: center;
  }
  .wide-field select {
    flex: 1;
    max-width: 360px;
  }
  .slot-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 10px;
  }
  .slot-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    background: #0e0f12;
    border: 1px solid #2a2b31;
    border-radius: 6px;
    padding: 8px 10px;
  }
  .slot-index {
    font-weight: 700;
    color: #ccc;
  }
</style>
