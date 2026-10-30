<svelte:options namespace="svg" />

<script lang="ts">
  import { meterFraction } from '../stores/mixerView';

  export let x: number;
  export let y: number;
  export let width: number;
  export let height: number;
  /** Level byte 0..255 as sent by the mixer, or null if no level data is available. */
  export let level: number | null;
  export let label = '';

  $: fraction = level == null ? 0 : meterFraction(level);
  $: tipText = level == null ? `${label}: no level data` : `${label}: ${level}`;
</script>

<g>
  <title>{tipText}</title>
  <rect class="frame" {x} {y} {width} {height} rx="1.5" />
  <rect class="fill" x={x + 1} y={y + 1 + (height - 2) * (1 - fraction)} width={width - 2} height={Math.max(0, (height - 2) * fraction)} />
</g>

<style>
  .frame {
    fill: #0a0b0d;
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .fill {
    fill: var(--ok);
  }
</style>
