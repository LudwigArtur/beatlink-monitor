<script lang="ts">
  import { onMount } from 'svelte';
  import GlobalBar from './components/GlobalBar.svelte';
  import PlayerGrid from './components/PlayerGrid.svelte';
  import LiveView from './components/LiveView.svelte';
  import SettingsPanel from './settings/SettingsPanel.svelte';
  import DiagnosticsView from './diagnostics/DiagnosticsView.svelte';
  import MaTelnetView from './matelnet/MaTelnetView.svelte';
  import MixerPanel from './components/MixerPanel.svelte';
  import CdjPanel from './components/CdjPanel.svelte';
  import PinSetupDialog from './components/PinSetupDialog.svelte';
  import ConfirmDialog from './components/ConfirmDialog.svelte';
  import { pinSetupRequired, refreshRemoteInfo } from './stores/remotePin';
  import { connectWebSocket, connectionId, connectionStatus, diagnostics, masterClock, outputs, players } from './websocket/connection';
  import { activeProfile, initLayout, setFullscreenFlag } from './stores/layout';

  type View = 'monitor' | 'liveview' | 'matelnet' | 'mixer' | 'cdj' | 'settings' | 'diagnostics';

  // The current page is kept in the URL hash (#settings), so a reload or a bookmark returns to it. The Live View
  // is never restored this way: it is entered explicitly and needs a user gesture for fullscreen.
  const restorableViews: View[] = ['monitor', 'matelnet', 'mixer', 'cdj', 'settings', 'diagnostics'];
  function viewFromHash(): View {
    const name = window.location.hash.replace('#', '');
    return restorableViews.find((v) => v === name) ?? 'monitor';
  }

  let view: View = viewFromHash();
  $: if (view !== 'liveview' && window.location.hash !== `#${view}`) {
    history.replaceState(null, '', `#${view}`);
  }
  function onHashChange() {
    if (view !== 'liveview') view = viewFromHash();
  }
  let mainEl: HTMLElement;

  // The unlock belongs to the WebSocket connection: a new connection starts locked again, so ask the backend again.
  $: if ($connectionId) void refreshRemoteInfo();

  onMount(() => {
    connectWebSocket();
    initLayout();

    // Ask for the PIN setup as soon as the backend answers (it may still be starting).
    let infoTimer: ReturnType<typeof setTimeout> | undefined;
    const checkRemoteInfo = async () => {
      if (!(await refreshRemoteInfo())) infoTimer = setTimeout(checkRemoteInfo, 2000);
    };
    void checkRemoteInfo();

    document.addEventListener('fullscreenchange', onFullscreenChange);
    window.addEventListener('hashchange', onHashChange);
    return () => {
      clearTimeout(infoTimer);
      document.removeEventListener('fullscreenchange', onFullscreenChange);
      window.removeEventListener('hashchange', onHashChange);
    };
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
    <div class="ui">
      <header class="topbar">
        <nav aria-label="Main">
          <button class:active={view === 'monitor'} on:click={() => (view = 'monitor')}>Monitor</button>
          <button class:active={view === 'matelnet'} on:click={() => (view = 'matelnet')}>MA Telnet</button>
          <button class:active={view === 'mixer'} on:click={() => (view = 'mixer')}>Mixer</button>
          <button class:active={view === 'cdj'} on:click={() => (view = 'cdj')}>CDJs</button>
          <button class:active={view === 'settings'} on:click={() => (view = 'settings')}>Settings</button>
          <button class:active={view === 'diagnostics'} on:click={() => (view = 'diagnostics')}>Diagnostics</button>
          <button class="liveview-nav-button" on:click={openLiveView}>▶ Live View</button>
        </nav>
        <GlobalBar
          masterClock={$masterClock}
          outputs={$outputs}
          diagnostics={$diagnostics}
          connectionStatus={$connectionStatus}
        />
      </header>

      <div class="scaled-content" style:transform={`scale(${$activeProfile.globalScale})`}>
        {#if view === 'monitor'}
          <PlayerGrid players={$players} masterClock={$masterClock} slots={$activeProfile.playerSlots} />
        {:else if view === 'matelnet'}
          <MaTelnetView />
        {:else if view === 'mixer'}
          <MixerPanel />
        {:else if view === 'cdj'}
          <CdjPanel />
        {:else if view === 'settings'}
          <SettingsPanel />
        {:else if view === 'diagnostics'}
          <DiagnosticsView diagnostics={$diagnostics} />
        {/if}
      </div>
    </div>
  {/if}
  {#if $pinSetupRequired}
    <PinSetupDialog />
  {/if}
  <ConfirmDialog />
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
  .topbar {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 24px;
    padding: 0 16px;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
  }
  nav {
    display: flex;
    flex-wrap: wrap;
    align-items: stretch;
    gap: 2px;
  }
  nav button {
    background: none;
    border: 0;
    border-bottom: 2px solid transparent;
    border-radius: 0;
    color: var(--muted);
    padding: 10px 12px 8px;
    min-height: 0;
  }
  nav button:hover:not(:disabled) {
    background: none;
    color: var(--text);
  }
  nav button.active {
    background: none;
    border-bottom-color: var(--accent);
    color: var(--text);
  }
  /* The Live View is the main destination once everything is set up, so it keeps a visible outline. */
  nav button.liveview-nav-button {
    align-self: center;
    margin-left: 10px;
    padding: 3px 10px;
    border: 1px solid var(--accent);
    border-radius: var(--radius);
    color: var(--accent);
  }
  nav button.liveview-nav-button:hover:not(:disabled) {
    background: rgba(90, 169, 255, 0.12);
    color: var(--accent);
  }
</style>
