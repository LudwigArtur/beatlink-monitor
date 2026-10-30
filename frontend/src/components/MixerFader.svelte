<svelte:options namespace="svg" />

<script lang="ts">
  import { faderFraction } from '../stores/mixerView';

  /** Center x of the slot, top y and height of the travel. */
  export let x: number;
  export let y: number;
  export let height: number;
  export let value: number;
  export let label = '';
  export let tip = '';
  /** Draw the fader horizontally (the crossfader); then [height] is its length. */
  export let horizontal = false;

  const capLong = 22;
  const capShort = 11;

  $: fraction = faderFraction(value);
  $: tipText = tip || `${label}: ${value}`;
  $: ticks = Array.from({ length: 11 }, (_, i) => i / 10);
</script>

<g>
  <title>{tipText}</title>
  {#if horizontal}
    <rect class="slot" x={x - height / 2} y={y - 2} width={height} height="4" rx="2" />
    {#each ticks as t}<line class="tick" x1={x - height / 2 + t * height} y1={y - 9} x2={x - height / 2 + t * height} y2={y - 6} />{/each}
    <rect class="cap" x={x - height / 2 + fraction * (height - capShort) - 0} y={y - capLong / 2} width={capShort} height={capLong} rx="2" />
  {:else}
    <rect class="slot" x={x - 2} y={y} width="4" {height} rx="2" />
    {#each ticks as t}<line class="tick" x1={x - 11} y1={y + height - t * (height - capShort) - capShort / 2} x2={x - 7} y2={y + height - t * (height - capShort) - capShort / 2} />{/each}
    <rect class="cap" x={x - capLong / 2} y={y + (1 - fraction) * (height - capShort)} width={capLong} height={capShort} rx="2" />
    <line class="cap-line" x1={x - capLong / 2 + 3} y1={y + (1 - fraction) * (height - capShort) + capShort / 2} x2={x + capLong / 2 - 3} y2={y + (1 - fraction) * (height - capShort) + capShort / 2} />
  {/if}
  {#if label}<text class="label" {x} y={horizontal ? y + 20 : y + height + 14}>{label}</text>{/if}
</g>

<style>
  .slot {
    fill: var(--bg);
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .tick {
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .cap {
    fill: var(--raised);
    stroke: var(--muted);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .cap-line {
    stroke: var(--text);
    stroke-width: 1.5;
  }
  .label {
    fill: var(--muted);
    font-size: 8px;
    text-anchor: middle;
  }
</style>
