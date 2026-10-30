<script lang="ts">
  import type { DiagnosticsSnapshot, MasterClockState, OutputStatus } from '../stores/types';

  export let masterClock: MasterClockState;
  export let outputs: OutputStatus[];
  export let diagnostics: DiagnosticsSnapshot | null;
  export let connectionStatus: 'connecting' | 'open' | 'closed';

  $: grandMa = outputs.find((o) => o.name === 'GrandMA2 Telnet');
  $: link = outputs.find((o) => o.name === 'Ableton Link');
</script>

<!--
  Compact status area: master tempo on the left, connection indicators on the right. All indicators are always
  shown in a fixed order, there is no per-widget configuration (they are small status lights, not something to curate).
-->
<div class="bar">
  <div class="master" title="Tempo master">
    <span class="bpm">{masterClock.valid ? masterClock.effectiveBpm?.toFixed(2) : '--'}</span>
    <span class="unit">BPM</span>
    <span class="player">{masterClock.valid ? `Master #${masterClock.masterPlayerNumber}` : 'No master'}</span>
  </div>

  <div class="status" aria-label="Connection status">
    <span class="item" title="Connection between this page and the backend">
      <span class="dot" class:ok={connectionStatus === 'open'}></span>Backend
    </span>
    <span class="item"><span class="dot" class:ok={diagnostics?.proDjLinkRunning}></span>Pro DJ Link</span>
    <span class="item"><span class="dot" class:ok={link?.connected}></span>Ableton Link</span>
    <span class="item"><span class="dot" class:ok={grandMa?.connected}></span>GrandMA2</span>
  </div>
</div>

<style>
  .bar {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px 24px;
    margin-left: auto;
    padding: 6px 0;
  }
  .master {
    display: flex;
    align-items: baseline;
    gap: 6px;
  }
  .bpm {
    font-size: 18px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
  .unit,
  .player {
    font-size: 12px;
    color: var(--muted);
  }
  .status {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 16px;
    font-size: 12px;
    color: var(--muted);
  }
  .item {
    display: flex;
    align-items: center;
    gap: 6px;
  }
</style>
