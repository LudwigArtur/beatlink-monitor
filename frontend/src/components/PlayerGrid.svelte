<script lang="ts">
  import type { MasterClockState, PlayerSlotConfig, PlayerState } from '../stores/types';
  import { resolveSlot } from '../stores/slotResolver';
  import PlayerCard from '../widgets/PlayerCard.svelte';

  export let players: PlayerState[];
  export let masterClock: MasterClockState;
  export let slots: PlayerSlotConfig[];

  $: resolved = [...slots]
    .filter((s) => s.visible)
    .sort((a, b) => a.slot - b.slot)
    .map((slot) => {
      const deviceNumber = resolveSlot(slot, players, masterClock);
      const player = deviceNumber != null ? players.find((p) => p.deviceNumber === deviceNumber) ?? null : null;
      return { slot, player };
    });

  // Monitor page auto-layout: the grid's column count is derived purely
  // from how many slots are currently enabled, not from any manual
  // per-card positioning/sizing - 1 deck fills the width, 2 sit
  // side-by-side, 3-4 form a 2x2 grid, 5-6 a 3-column grid, matching how a
  // real DJ booth's number of connected players determines its layout.
  $: columns = resolved.length <= 1 ? 1 : resolved.length <= 2 ? 2 : resolved.length <= 4 ? 2 : 3;
</script>

<div class="grid" style:--columns={columns}>
  {#each resolved as { slot, player } (slot.slot)}
    <div class="cell">
      <PlayerCard {player} slotLabel={slot.sourceType === 'CURRENT_MASTER' ? 'Master' : `Player ${slot.playerNumber}`} />
    </div>
  {/each}
</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(var(--columns), minmax(280px, 1fr));
    gap: 16px;
    padding: 16px;
  }
  .cell {
    display: flex;
  }
</style>
