<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '../stores/api';
  import { diagnostics, players } from '../websocket/connection';
  import { SMOOTHING_LEVELS, smoothingLevel } from '../stores/smoothing';
  import RemotePinSettings from '../components/RemotePinSettings.svelte';
  import PinSetupDialog from '../components/PinSetupDialog.svelte';
  import { remoteInfo } from '../stores/remotePin';
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

  // The interface itself is scaled by this setting, so a slider would move away under the pointer while it is
  // dragged; plus and minus buttons stay put and work the same at every scale.
  const SCALE_MIN = 0.5;
  const SCALE_MAX = 2;
  const SCALE_STEP = 0.05;
  function stepScale(direction: 1 | -1) {
    const next = Math.round(($activeProfile.globalScale + direction * SCALE_STEP) * 100) / 100;
    setGlobalScale(Math.min(SCALE_MAX, Math.max(SCALE_MIN, next)));
  }

  type SectionId = 'display' | 'players' | 'general' | 'mixer' | 'outputs' | 'presets';
  const sections: { id: SectionId; label: string }[] = [
    { id: 'display', label: 'Display' },
    { id: 'players', label: 'Players' },
    { id: 'general', label: 'General' },
    { id: 'mixer', label: 'Mixer & CDJs' },
    { id: 'outputs', label: 'Ableton Link' },
    { id: 'presets', label: 'Presets' },
  ];
  let section: SectionId = 'display';

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
    lanAccessSaved = appConfig.allowLanAccess;
    outputsConfig = await api.getOutputsConfig();
    interfaces = await api.getNetworkInterfaces();
  });

  // Network visibility lets other devices reach the hardware controls, so every time it is turned on a new PIN is
  // created first; the backend refuses to enable it without one and discards the PIN when it is turned off.
  let lanAccessSaved = false;
  let askLanPin = false;

  async function saveApp() {
    if (!appConfig) return;
    if (appConfig.allowLanAccess && !lanAccessSaved) {
      askLanPin = true;
      return;
    }
    await persistApp();
  }

  async function persistApp() {
    if (!appConfig) return;
    try {
      const result = await api.saveApplicationConfig(appConfig);
      lanAccessSaved = appConfig.allowLanAccess;
      statusMessage = result.restartRequired
        ? 'Settings saved. Restart the application (quit and reopen) for the network/player-number change to take effect.'
        : 'General settings saved.';
    } catch {
      statusMessage = 'The settings could not be saved. LAN access can only be changed on the device the server runs on.';
    }
  }

  function lanPinCreated() {
    askLanPin = false;
    void persistApp();
  }

  function lanPinCancelled() {
    askLanPin = false;
    if (appConfig) appConfig.allowLanAccess = false;
    statusMessage = 'LAN access stays off because no PIN was created.';
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

<div class="page settings">
  {#if statusMessage}<div class="notice ok" role="status">{statusMessage}</div>{/if}

  <div class="layout">
    <nav class="subnav" aria-label="Settings sections">
      {#each sections as item (item.id)}
        <button class:active={section === item.id} on:click={() => (section = item.id)}>{item.label}</button>
      {/each}
    </nav>

    <div class="content">
      {#if section === 'display'}
        <section class="card">
          <h3>Display</h3>
          <p class="hint">Display settings are applied immediately and saved automatically.</p>
          <div class="field" title="Scales the entire user interface up or down.">
            <span class="label" id="scale-label">Scale</span>
            <span class="control" role="group" aria-labelledby="scale-label">
              <button class="step" on:click={() => stepScale(-1)} disabled={$activeProfile.globalScale <= SCALE_MIN} aria-label="Smaller">−</button>
              <span class="scale-value" aria-live="polite">{$activeProfile.globalScale.toFixed(2)}x</span>
              <button class="step" on:click={() => stepScale(1)} disabled={$activeProfile.globalScale >= SCALE_MAX} aria-label="Larger">+</button>
            </span>
          </div>
          <label class="field" title="Background color of the entire application.">
            <span class="label">Background color</span>
            <span class="control">
              <input type="color" value={$activeProfile.backgroundColor}
                on:input={(e) => setBackgroundColor((e.target as HTMLInputElement).value)} />
            </span>
          </label>
          <label class="field" title="Brightness of Live View decks that are not On-Air, while 'Dim inactive decks' is active.">
            <span class="label">Inactive deck brightness</span>
            <span class="control">
              <input type="range" min="5" max="95" step="5" value={$activeProfile.inactiveDeckBrightnessPercent}
                on:input={(e) => setInactiveDeckBrightnessPercent(Number((e.target as HTMLInputElement).value))} />
              <span class="value">{$activeProfile.inactiveDeckBrightnessPercent}%</span>
            </span>
          </label>
          <div class="field" title="How smoothly the Live View waveform playhead corrects small differences between its predicted and the actually reported position. Smoother levels ignore more jitter and move more gradually, but catch up more slowly after a real jump; the two smoothest levels also keep the playhead steady while the pitch fader moves.">
            <span class="label" id="smooth-label">Waveform smoothness</span>
            <span class="control smoothing" role="group" aria-labelledby="smooth-label">
              {#each SMOOTHING_LEVELS as level (level.id)}
                <button
                  class:active={smoothingLevel($activeProfile.waveformSmoothingFactor) === level.id}
                  aria-pressed={smoothingLevel($activeProfile.waveformSmoothingFactor) === level.id}
                  title={level.hint}
                  on:click={() => setWaveformSmoothingFactor(level.factor)}
                >{level.label}</button>
              {/each}
            </span>
          </div>
          <label class="check" title="When the playhead jumps (seeking, hot cue, loop jump), the Live View waveform eases to the new position in a short, smooth movement instead of snapping. Turn off to see every jump instantly.">
            <input type="checkbox" checked={$activeProfile.waveformGlideJumps}
              on:change={(e) => updateProfile({ waveformGlideJumps: (e.target as HTMLInputElement).checked })} />
            Glide jumps (smooth movement instead of snapping)
          </label>
          <label class="check" title="Starts the application in fullscreen mode.">
            <input type="checkbox" checked={$activeProfile.fullscreen} on:change={(e) => setFullscreenFlag((e.target as HTMLInputElement).checked)} />
            Fullscreen
          </label>
        </section>
      {/if}

      {#if section === 'players'}
        <section class="card">
          <h3>Player slots</h3>
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
                  <option value="MASTER">Current master</option>
                  <option value="DISABLED">Disabled</option>
                </select>
              </label>
            {/each}
          </div>
        </section>
      {/if}

      {#if section === 'general'}
        {#if appConfig}
          <section class="card">
            <h3>General</h3>
            <label class="field" title="The TCP port through which the web UI is reachable locally. The app must be restarted after a change.">
              <span class="label">Web UI port</span>
              <span class="control"><input type="number" bind:value={appConfig.webUiPort} /></span>
            </label>
            <label class="check" title="Allows access to the web UI from other devices on the same network, not just from this computer. Needs a restart. Turning it on asks you to create a PIN that other devices need to change mixer or CDJ settings or press buttons.">
              <input type="checkbox" bind:checked={appConfig.allowLanAccess} disabled={$remoteInfo !== null && !$remoteInfo.local} />
              Allow LAN access (restart required)
            </label>
            {#if $remoteInfo && !$remoteInfo.local}
              <p class="hint">LAN access can only be changed on the device the server runs on.</p>
            {:else}
              <p class="hint">When turned on you create a PIN. Other devices need it to change mixer or CDJ settings or press player buttons.</p>
            {/if}
            <label class="field" title="Number of decimal places shown for BPM values.">
              <span class="label">BPM decimal places</span>
              <span class="control"><input type="number" min="0" max="4" bind:value={appConfig.bpmDecimalPlaces} /></span>
            </label>
          </section>

          <section class="card">
            <h3>Network (Pro DJ Link)</h3>
            <label class="field" title="Automatic selects the network interface itself; Manual allows explicitly choosing a network interface for Pro DJ Link.">
              <span class="label">Interface mode</span>
              <span class="control">
                <select bind:value={appConfig.networkInterfaceMode}>
                  <option value="AUTOMATIC">Automatic</option>
                  <option value="MANUAL">Manual</option>
                </select>
              </span>
            </label>
            {#if appConfig.networkInterfaceMode === 'MANUAL'}
              <label class="field" title="The network interface used to search for Pro DJ Link devices.">
                <span class="label">Interface</span>
                <span class="control">
                  <select bind:value={appConfig.manualInterfaceName}>
                    {#if appConfig.manualInterfaceName && !interfaces.some((i) => i.name === appConfig?.manualInterfaceName)}
                      <option value={appConfig.manualInterfaceName}>{appConfig.manualInterfaceName} (not available)</option>
                    {/if}
                    {#each interfaces as iface}
                      <option value={iface.name}>{iface.displayName} ({iface.addresses.join(', ')})</option>
                    {/each}
                  </select>
                </span>
              </label>
            {/if}
            <label
              class="field"
              title="Pins our own virtual CDJ's player number on the Pro DJ Link network instead of letting beat-link choose it automatically. Numbers already in use by a real device are shown greyed out and cannot be selected."
            >
              <span class="label">Virtual player number</span>
              <span class="control">
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
              </span>
            </label>
            <label
              class="field"
              title="Which waveform style to request from real CDJ-3000s. RGB is the widely-compatible standard color waveform; Three-Band requests the CDJ-3000's native, richer per-frequency-band style instead. Hardware that cannot provide the requested style automatically falls back to what it can."
            >
              <span class="label">Waveform style</span>
              <span class="control">
                <select bind:value={appConfig.waveformStylePreference}>
                  <option value="RGB">RGB (standard, recommended)</option>
                  <option value="THREE_BAND">Three-Band (CDJ-3000 native)</option>
                </select>
              </span>
            </label>
            <p class="hint warning">
              Changing the network interface, virtual player number, or waveform style requires a full application
              restart (quit and reopen) to safely take effect - these are deliberately no longer applied live. A player
              number outside 1-4 also prevents fetching metadata, waveforms and track titles from real players (a hard
              Pioneer protocol limitation, not a bug), and repeatedly switching these settings while real players are
              connected has been confirmed to destabilize a real player's own device-number claim. Set these once
              before a session, not during one.
            </p>
          </section>

          <div class="row">
            <button class="primary" on:click={saveApp}>Save</button>
          </div>
        {:else}
          <p class="muted">Loading…</p>
        {/if}
      {/if}

      {#if section === 'mixer'}
        {#if appConfig}
          <section class="card">
            <h3>Mixer</h3>
            <p class="hint">
              Reads the live state of a DJM mixer (EQ, faders, crossfader, FX), its utility settings and firmware version.
              Checked with a DJM-V10, a DJM-A9 and CDJ-3000s. Changing a setting only happens when you press Write in the Mixer
              tab, and counts as confirmed only after the mixer reports the new value back. See Docs/MIXER_CONTROL.md.
            </p>
            <label class="check" title="Listens for the mixer's live knob and fader positions and allows reading its utility settings. Hooks into the Pro DJ Link socket at startup, so a restart is required.">
              <input type="checkbox" bind:checked={appConfig.mixerIntegrationEnabled} />
              Enable mixer integration (restart required)
            </label>
            <label class="check" title="Additionally allows changing mixer utility settings (balance, limiter, attenuation, mic routing...) from the Mixer tab.">
              <input type="checkbox" bind:checked={appConfig.mixerControlEnabled} disabled={!appConfig.mixerIntegrationEnabled} />
              Allow changing mixer settings
            </label>
            <label class="check" title="Announces this app as an additional third-party participant (category 5, device number 54-57, like ShowKontrol) next to the virtual CDJ. A DJM-V10 only sent its live EQ/fader state to such a peer (observed). It adds one more device to your Pro DJ Link network.">
              <input type="checkbox" bind:checked={appConfig.mixerAnnounceIdentity} disabled={!appConfig.mixerIntegrationEnabled} />
              Announce as third-party device (needed for the live state)
            </label>
            <label class="field" title="IPv4 address of the mixer on the local network. Leave empty to use the mixer found by Pro DJ Link discovery.">
              <span class="label">Mixer address (optional)</span>
              <span class="control">
                <input placeholder="e.g. 169.254.12.34" value={appConfig.mixerAddress ?? ''}
                  on:input={(e) => (appConfig!.mixerAddress = (e.target as HTMLInputElement).value.trim() || null)} />
              </span>
            </label>

            <h4>CDJs</h4>
            <label class="check" title="Reads every CDJ's settings and deck flags (harmless requests, repeated every few seconds). Uses the same socket hook as the mixer integration, so a restart is required.">
              <input type="checkbox" bind:checked={appConfig.cdjIntegrationEnabled} />
              Read CDJ settings and deck state (restart required)
            </label>
            <label class="check" title="Allows changing CDJ settings from the CDJs tab; confirmed only after the CDJ reports the new value.">
              <input type="checkbox" bind:checked={appConfig.cdjControlEnabled} disabled={!appConfig.cdjIntegrationEnabled} />
              Allow changing CDJ settings
            </label>
          </section>

          <section class="card">
            <h3>EQ controls waveform brightness</h3>
            <p class="hint">
              With live mixer data, each channel's EQ dims the matching part of that player's waveform: low = red (RGB) /
              blue (three-band), mid = green / amber, high = blue / white. Channel N is taken to be fed by player N.
              Knob fully down (raw 0) shows the minimum brightness, the center detent (raw 128) 100 %. Above the detent
              the band glows up to the boost value at raw 255. The waveform data only has three bands: on a 4-band
              mixer (e.g. DJM-V10) the mid part follows the average of Hi-Mid and Lo-Mid.
            </p>
            <label class="check">
              <input type="checkbox" checked={$activeProfile.eqWaveformEnabled}
                on:change={(e) => updateProfile({ eqWaveformEnabled: (e.target as HTMLInputElement).checked })} />
              Enabled
            </label>
            <label class="field" title="Raw EQ value of a knob turned fully down (minimum brightness).">
              <span class="label">Raw value, knob fully down</span>
              <span class="control">
                <input type="number" min="0" max="255" value={$activeProfile.eqRawMin}
                  on:change={(e) => updateProfile({ eqRawMin: Number((e.target as HTMLInputElement).value) })} />
              </span>
            </label>
            <label class="field" title="Raw EQ value of a knob at its center detent (100 % brightness).">
              <span class="label">Raw value, knob at center</span>
              <span class="control">
                <input type="number" min="0" max="255" value={$activeProfile.eqRawCenter}
                  on:change={(e) => updateProfile({ eqRawCenter: Number((e.target as HTMLInputElement).value) })} />
              </span>
            </label>
            <label class="field" title="Brightness of a band with its knob fully down, in percent.">
              <span class="label">Minimum brightness (%)</span>
              <span class="control">
                <input type="number" min="0" max="100" value={Math.round($activeProfile.eqMinBrightness * 100)}
                  on:change={(e) => updateProfile({ eqMinBrightness: Math.min(100, Math.max(0, Number((e.target as HTMLInputElement).value))) / 100 })} />
              </span>
            </label>
            <label class="field" title="Extra glow for a knob turned fully up (raw 255), in percent on top of 100 %. 0 = no boost.">
              <span class="label">Boost above center (%)</span>
              <span class="control">
                <input type="number" min="0" max="100" value={Math.round($activeProfile.eqBoostGlow * 100)}
                  on:change={(e) => updateProfile({ eqBoostGlow: Math.min(100, Math.max(0, Number((e.target as HTMLInputElement).value))) / 100 })} />
              </span>
            </label>
            <label class="field" title="Which EQ knob dims the mid part of the waveform.">
              <span class="label">Mid band follows</span>
              <span class="control">
                <select value={$activeProfile.eqMidSource}
                  on:change={(e) => updateProfile({ eqMidSource: (e.target as HTMLSelectElement).value as 'AUTO' | 'AVERAGE' | 'HI_MID' | 'LO_MID' })}>
                  <option value="AUTO">Automatic by mixer model (A9, 3-band: Hi-Mid; V10, 4-band: average)</option>
                  <option value="AVERAGE">Average of Hi-Mid and Lo-Mid</option>
                  <option value="HI_MID">Hi-Mid only</option>
                  <option value="LO_MID">Lo-Mid only</option>
                </select>
              </span>
            </label>
            <p class="hint">The waveform settings above are applied immediately; only the integration options need Save.</p>
          </section>

          <div class="row">
            <button class="primary" on:click={saveApp}>Save</button>
          </div>
        {:else}
          <p class="muted">Loading…</p>
        {/if}
        <RemotePinSettings />
      {/if}

      {#if section === 'outputs'}
        {#if outputsConfig}
          <section class="card">
            <h3>Ableton Link</h3>
            <label class="check">
              <input type="checkbox" bind:checked={outputsConfig.abletonLink.enabled} />
              Enabled
            </label>
            <label class="field">
              <span class="label">Carabiner host</span>
              <span class="control"><input bind:value={outputsConfig.abletonLink.carabinerHost} /></span>
            </label>
            <label class="field">
              <span class="label">Carabiner port</span>
              <span class="control"><input type="number" bind:value={outputsConfig.abletonLink.carabinerPort} /></span>
            </label>
            <label class="field">
              <span class="label">Quantum</span>
              <span class="control"><input type="number" bind:value={outputsConfig.abletonLink.quantum} /></span>
            </label>
            <label class="field">
              <span class="label">Position source</span>
              <span class="control">
                <select bind:value={outputsConfig.abletonLink.positionSource}>
                  <option value="AUTO">Auto</option>
                  <option value="PRECISE">Precise</option>
                  <option value="SMOOTHED">Smoothed</option>
                </select>
              </span>
            </label>

            <h4>Network adapter</h4>
            <label class="field" title="Determines which network adapter is used to connect to the Carabiner process - independent of the adapter used for Pro DJ Link.">
              <span class="label">Interface mode</span>
              <span class="control">
                <select bind:value={outputsConfig.abletonLink.networkInterfaceMode}>
                  <option value="AUTOMATIC">Automatic</option>
                  <option value="MANUAL">Manual</option>
                </select>
              </span>
            </label>
            {#if outputsConfig.abletonLink.networkInterfaceMode === 'MANUAL'}
              <label class="field" title="The network interface used to connect to the Carabiner process.">
                <span class="label">Interface</span>
                <span class="control">
                  <select bind:value={outputsConfig.abletonLink.manualInterfaceName}>
                    {#if outputsConfig.abletonLink.manualInterfaceName && !interfaces.some((i) => i.name === outputsConfig?.abletonLink.manualInterfaceName)}
                      <option value={outputsConfig.abletonLink.manualInterfaceName}>{outputsConfig.abletonLink.manualInterfaceName} (not available)</option>
                    {/if}
                    {#each interfaces as iface}
                      <option value={iface.name}>{iface.displayName} ({iface.addresses.join(', ')})</option>
                    {/each}
                  </select>
                </span>
              </label>
            {/if}
          </section>

          <div class="row">
            <button class="primary" on:click={saveOutputs}>Save</button>
          </div>
        {:else}
          <p class="muted">Loading…</p>
        {/if}
      {/if}

      {#if section === 'presets'}
        <section class="card">
          <h3>Presets</h3>
          <p class="hint">
            All settings are automatically saved continuously and reloaded on the next start. Here you can additionally
            save the current state manually under a name, to return to it later or switch between multiple
            configurations.
          </p>
          <div class="row">
            <input placeholder="Preset name" aria-label="Preset name" bind:value={newPresetName} />
            <button class="primary" on:click={onSavePreset} disabled={!newPresetName.trim()}>Save current state</button>
          </div>
          <div class="row preset-row">
            <select aria-label="Preset" bind:value={selectedPreset}>
              <option value="" disabled>Select preset…</option>
              {#each $presetNames as name}
                <option value={name}>{name}</option>
              {/each}
            </select>
            <button on:click={onLoadPreset} disabled={!selectedPreset}>Load</button>
            <button on:click={onDeletePreset} disabled={!selectedPreset}>Delete</button>
          </div>
        </section>
      {/if}
    </div>
  </div>
  {#if askLanPin}
    <PinSetupDialog onDone={lanPinCreated} onCancel={lanPinCancelled} />
  {/if}
</div>

<style>
  .layout {
    display: grid;
    grid-template-columns: 170px minmax(0, 1fr);
    gap: 20px;
    align-items: start;
  }
  .subnav {
    display: flex;
    flex-direction: column;
    gap: 2px;
    position: sticky;
    top: 12px;
  }
  .subnav button {
    background: none;
    border: 0;
    border-left: 2px solid transparent;
    border-radius: 0;
    text-align: left;
    color: var(--muted);
    padding: 6px 10px;
  }
  .subnav button:hover:not(:disabled) {
    background: none;
    color: var(--text);
  }
  .subnav button.active {
    background: none;
    border-left-color: var(--accent);
    color: var(--text);
  }
  .content {
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-width: 0;
  }
  .step {
    min-width: 32px;
    padding: 2px 0;
    font-size: 16px;
    line-height: 1;
  }
  .scale-value {
    min-width: 5ch;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }
  .value {
    min-width: 5ch;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  .preset-row select {
    min-width: 220px;
  }
  .slot-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 8px;
  }
  .slot-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 6px 10px;
  }
  .slot-index {
    color: var(--muted);
  }
  @media (max-width: 720px) {
    .layout {
      grid-template-columns: 1fr;
      gap: 10px;
    }
    .subnav {
      flex-direction: row;
      flex-wrap: wrap;
      position: static;
    }
    .subnav button {
      border-left: 0;
      border-bottom: 2px solid transparent;
    }
    .subnav button.active {
      border-bottom-color: var(--accent);
    }
  }
</style>
