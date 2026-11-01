<script lang="ts">
  import type { NetworkHealth } from '../stores/types';

  export let network: NetworkHealth | null;

  /** From this many dropouts in 10 minutes the connection counts as unstable (same limit as the backend log). */
  const UNSTABLE_DROPOUTS = 4;

  const list = (players: number[]) => players.map((p) => `#${p}`).join(', ');

  $: addressChanged = network?.addressChanged === true;
  $: unstable = !addressChanged && (network?.deviceDropouts ?? 0) >= UNSTABLE_DROPOUTS;
  $: noMetadata = (network?.metadataUnavailablePlayers.length ?? 0) > 0;
</script>

{#if network && (addressChanged || unstable || noMetadata)}
  <div class="banner" role="alert">
    {#if addressChanged}
      <p class="bad">
        <strong>The network address changed.</strong>
        Pro DJ Link is still using {network.boundAddress} on {network.interfaceName}, which now has
        {network.currentAddresses.length ? network.currentAddresses.join(', ') : 'no address'}. The players cannot be
        reached until the application is quit and reopened.
      </p>
    {/if}
    {#if unstable}
      <p class="warn">
        <strong>The connection to the players is unstable:</strong>
        {network.deviceDropouts} dropouts in the last 10 minutes. Check cable, adapter, switch and that no second device or
        adapter uses the same address range.
      </p>
    {/if}
    {#if noMetadata}
      <p class="warn">
        <strong>No track information for player {list(network.metadataUnavailablePlayers)}.</strong>
        {#if network.unreachablePlayers.length}
          The player's database port does not answer ({list(network.unreachablePlayers)}), which points to the network and not
          to the track.
        {:else}
          The player answers, but delivers no title or waveform.
        {/if}
      </p>
    {/if}
  </div>
{/if}

<style>
  .banner {
    margin: 8px 20px 0;
    padding: 8px 12px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--surface);
  }
  .banner p {
    margin: 2px 0;
  }
</style>
