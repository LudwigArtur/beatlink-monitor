import type { MasterClockState, PlayerSlotConfig, PlayerState } from './types';

/**
 * Client-side mirror of the backend's `PlayerSlotResolver`
 * (requirements specification §5): decides which Pro DJ Link player a UI slot currently
 * displays, given its configured source (a fixed player number or the
 * current tempo master).
 */
export function resolveSlot(
  slot: PlayerSlotConfig,
  players: PlayerState[],
  master: MasterClockState,
): number | null {
  if (slot.sourceType === 'CURRENT_MASTER') return master.masterPlayerNumber;
  return slot.playerNumber;
}
