<script lang="ts">
  import { onMount } from 'svelte';
  import GlobalBar from './components/GlobalBar.svelte';
  import PlayerGrid from './components/PlayerGrid.svelte';
  import LiveView from './components/LiveView.svelte';
  import SimulatorPanel from './simulator/SimulatorPanel.svelte';
  import SettingsPanel from './settings/SettingsPanel.svelte';
  import DiagnosticsView from './diagnostics/DiagnosticsView.svelte';
  import MaTelnetView from './matelnet/MaTelnetView.svelte';
  import { connectWebSocket, connectionStatus, diagnostics, masterClock, outputs, players } from './websocket/connection';
  import { activeProfile, initLayout, setFullscreenFlag } from './stores/layout';

  type View = 'monitor' | 'liveview' | 'simulator' | 'matelnet' | 'settings' | 'diagnostics';
  let view: View = 'monitor';
  let mainEl: HTMLElement;

  onMount(() => {
    connectWebSocket();
    initLayout();

    document.addEventListener('fullscreenchange', onFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange);
  });

  function onFullscreenChange() {
    const isFullscreen = document.fullscreenElement != null;
    setFullscreenFlag(isFullscreen);
    // The Live View is a dedicated fullscreen-only view (requirements specification
    // request): if the user leaves fullscreen (Escape key, window controls,
    // etc.) while looking at it, fall back to the normal Monitor view so
    // they are never stuck on a chrome-less screen outside of fullscreen.
    if (!isFullscreen && view === 'liveview') {
      view = 'monitor';
    }
  }

  async function openLiveView() {
    view = 'liveview';
    if (!document.fullscreenElement) {
      await mainEl.requestFullscreen();
    }
  }

  async function exitLiveView() {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
    }
    view = 'monitor';
  }
</script>

<main
  bind:this={mainEl}
  class:liveview-active={view === 'liveview'}
  style:background={$activeProfile.backgroundColor}
  style:--ui-scale={$activeProfile.globalScale}
>
  {#if view === 'liveview'}
    <!-- Dedicated fullscreen view: no GlobalBar/nav chrome, just the tracks. -->
    <LiveView
      players={$players}
      masterClock={$masterClock}
      slots={$activeProfile.playerSlots}
      inactiveDeckBrightnessPercent={$activeProfile.inactiveDeckBrightnessPercent}
      onExit={exitLiveView}
    />
  {:else}
    <GlobalBar
      masterClock={$masterClock}
      outputs={$outputs}
      diagnostics={$diagnostics}
      connectionStatus={$connectionStatus}
    />

    <nav>
      <button class:active={view === 'monitor'} on:click={() => (view = 'monitor')}>Monitor</button>
      <button class="liveview-nav-button" on:click={openLiveView}>▶ Live View</button>
      <button class:active={view === 'simulator'} on:click={() => (view = 'simulator')}>Simulator</button>
      <button class:active={view === 'matelnet'} on:click={() => (view = 'matelnet')}>MA Telnet</button>
      <button class:active={view === 'settings'} on:click={() => (view = 'settings')}>Settings</button>
      <button class:active={view === 'diagnostics'} on:click={() => (view = 'diagnostics')}>Diagnostics</button>
    </nav>

    <div class="scaled-content" style:transform={`scale(${$activeProfile.globalScale})`}>
      {#if view === 'monitor'}
        <PlayerGrid players={$players} masterClock={$masterClock} slots={$activeProfile.playerSlots} />
      {:else if view === 'simulator'}
        <SimulatorPanel players={$players} simulatorRunning={$diagnostics?.simulatorRunning ?? false} />
      {:else if view === 'matelnet'}
        <MaTelnetView />
      {:else if view === 'settings'}
        <SettingsPanel />
      {:else if view === 'diagnostics'}
        <DiagnosticsView diagnostics={$diagnostics} />
      {/if}
    </div>
  {/if}
</main>

<style>
  :global(html, body) {
    margin: 0;
    background: #0e0f12;
    color: #eee;
  }
  :global(*) {
    box-sizing: border-box;
  }
  main {
    min-height: 100vh;
  }
  main.liveview-active {
    height: 100vh;
    position: relative;
  }
  .scaled-content {
    transform-origin: top left;
  }
  nav {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 16px;
    background: #101114;
    border-bottom: 1px solid #2a2b31;
    flex-wrap: wrap;
  }
  nav button {
    background: none;
    border: 1px solid transparent;
    color: #999;
    padding: 7px 14px;
    border-radius: 6px;
    cursor: pointer;
    font-size: 13px;
    font-weight: 500;
    transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
  }
  nav button:hover {
    color: #ddd;
    border-color: #2a2b31;
  }
  nav button.active {
    background: #2a2b31;
    color: #fff;
  }
  /* The Live View is the primary destination once everything else is
     configured (requirements specification: the most important button after setup), so
     it stands out visually from the other, more administrative nav
     entries. */
  nav button.liveview-nav-button {
    background: linear-gradient(135deg, #4fc3f7, #2196f3);
    color: #06141c;
    font-weight: 700;
    box-shadow: 0 1px 4px rgba(33, 150, 243, 0.4);
  }
  nav button.liveview-nav-button:hover {
    filter: brightness(1.08);
    border-color: transparent;
  }
</style>
