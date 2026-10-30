import { writable } from 'svelte/store';

/**
 * Every write to a mixer or CDJ and every remote button press has to be confirmed in a popup. The user may switch
 * that off in the settings, but only for the current page session: the value is deliberately not persisted, so a
 * reload (or a new browser tab) asks again.
 */
export const confirmHardwareWrites = writable(true);

export interface ConfirmRequest {
  title: string;
  detail: string;
  confirmLabel: string;
  resolve: (confirmed: boolean) => void;
}

/** The popup that is currently open, if any. */
export const pendingConfirm = writable<ConfirmRequest | null>(null);

let confirmEnabled = true;
confirmHardwareWrites.subscribe((value) => (confirmEnabled = value));

/** Resolves true right away when confirmation is switched off for this session, otherwise after the user decided. */
export function confirmAction(title: string, detail: string, confirmLabel = 'Confirm'): Promise<boolean> {
  if (!confirmEnabled) return Promise.resolve(true);
  return new Promise((resolve) => {
    pendingConfirm.update((open) => {
      open?.resolve(false);
      return { title, detail, confirmLabel, resolve };
    });
  });
}

export function answerConfirm(confirmed: boolean) {
  pendingConfirm.update((open) => {
    open?.resolve(confirmed);
    return null;
  });
}
