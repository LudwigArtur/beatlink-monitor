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
  All status widgets are always shown, in a fixed order - there is no
  per-widget visibility/ordering configuration anymore (simplification:
  these are small, always-relevant status indicators, not something users
  need to curate).
-->
<div class="bar">
  <div class="metric">
    <span class="label">Master BPM</span>
    <span class="value">{masterClock.valid ? masterClock.effectiveBpm?.toFixed(2) : '--'}</span>
  </div>
  <div class="metric">
    <span class="label">Master Player</span>
    <span class="value">{masterClock.valid ? `#${masterClock.masterPlayerNumber}` : '--'}</span>
  </div>

  <div class="status-group">
    <span class="dot" class:ok={connectionStatus === 'open'} title="Pro DJ Link / WebSocket"></span>
    <span>Network</span>
  </div>
  <div class="status-group">
    <span class="dot" class:ok={diagnostics?.proDjLinkRunning}></span>
    <span>Pro DJ Link</span>
  </div>
  <div class="status-group">
    <span class="dot" class:ok={link?.connected}></span>
    <span>Ableton Link</span>
  </div>
  <div class="status-group">
    <span class="dot" class:ok={grandMa?.connected}></span>
    <span>GrandMA2</span>
  </div>
  <div class="status-group">
    <span class="dot" class:ok={diagnostics?.simulatorRunning}></span>
    <span>Simulator</span>
  </div>
</div>

<style>
  .bar {
    display: flex;
    align-items: center;
    gap: 24px;
    padding: 8px 16px;
    background: #16171b;
    border-bottom: 1px solid #2a2b31;
    color: #eee;
    font-family: system-ui, sans-serif;
    flex-wrap: wrap;
  }
  .metric {
    display: flex;
    flex-direction: column;
    line-height: 1.1;
  }
  .metric .label {
    font-size: 10px;
    color: #888;
  }
  .metric .value {
    font-size: 20px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }
  .status-group {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: #bbb;
  }
  .status-group:last-of-type {
    margin-left: auto;
  }
  .dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: #c62828;
    display: inline-block;
  }
  .dot.ok {
    background: #43a047;
  }
</style>
