<svelte:options namespace="svg" />

<script lang="ts">
  import { knobAngle, knobTrack, polar } from '../stores/mixerView';

  export let cx: number;
  export let cy: number;
  export let value: number;
  export let label = '';
  /** Centered knobs (EQ, filter, ...) have their detent at 12 o'clock. */
  export let centered = false;
  export let radius = 13;
  /** Text of the tooltip; defaults to "label: value". */
  export let tip = '';

  $: angle = knobAngle(value, centered);
  $: tipText = tip || `${label}: ${value}`;
  $: tick = polar(radius + 5, 0);
</script>

<g transform={`translate(${cx} ${cy})`}>
  <title>{tipText}</title>
  <path class="track" d={knobTrack(radius + 4)} />
  {#if centered}<line class="detent" x1={tick.x} y1={tick.y - 2} x2={tick.x} y2={tick.y + 2} />{/if}
  <circle class="body" r={radius} />
  <line class="pointer" x1="0" y1={-radius * 0.28} x2="0" y2={-radius * 0.9} transform={`rotate(${angle.toFixed(1)})`} />
  {#if label}<text class="label" y={radius + 14}>{label}</text>{/if}
</g>

<style>
  .track {
    fill: none;
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .detent {
    stroke: var(--muted);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .body {
    fill: var(--bg);
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .pointer {
    stroke: var(--text);
    stroke-width: 2;
    stroke-linecap: round;
  }
  .label {
    fill: var(--muted);
    font-size: 7.5px;
    text-anchor: middle;
  }
</style>
