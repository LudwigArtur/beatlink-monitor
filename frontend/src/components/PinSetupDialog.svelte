<script lang="ts">
  import { api } from '../stores/api';
  import { pinProblem, refreshRemoteInfo, MAX_PIN_LENGTH } from '../stores/remotePin';

  /** Called after the PIN was stored. */
  export let onDone: () => void = () => {};
  /** When given, the dialog can be dismissed (used while turning on LAN access); otherwise a PIN is mandatory. */
  export let onCancel: (() => void) | null = null;

  let pin = '';
  let confirm = '';
  let error = '';
  let busy = false;
  let reveal = false;

  $: problem = pin ? pinProblem(pin) : '';
  $: mismatch = confirm !== '' && confirm !== pin;
  $: ready = pin !== '' && !problem && pin === confirm;

  async function save() {
    if (!ready || busy) return;
    busy = true;
    error = '';
    const result = await api.cdjRemoteSetPin(pin);
    busy = false;
    if (result.status === 'SAVED') {
      pin = '';
      confirm = '';
      await refreshRemoteInfo();
      onDone();
    } else {
      error = result.message ?? 'The PIN could not be saved.';
    }
  }
</script>

<div class="ui">
  <div class="backdrop" role="dialog" aria-modal="true" aria-labelledby="pin-setup-title">
    <form class="dialog card" on:submit|preventDefault={save}>
      <h3 id="pin-setup-title">Create a PIN for other devices</h3>
      <p class="hint">
        Other devices on the network can see this app. Before they can change a setting on a mixer or CDJ, or press a
        player button, they have to enter this PIN (again after every connection). This device needs no PIN. The PIN
        cannot be looked up later; if you forget it, turn LAN access off and on again to create a new one.
      </p>
      <div class="field">
        <label class="label" for="pin-new">PIN</label>
        <div class="control">
          <input id="pin-new" type={reveal ? 'text' : 'password'} autocomplete="off" maxlength={MAX_PIN_LENGTH} bind:value={pin} />
        </div>
      </div>
      <div class="field">
        <label class="label" for="pin-confirm">Repeat PIN</label>
        <div class="control">
          <input id="pin-confirm" type={reveal ? 'text' : 'password'} autocomplete="off" maxlength={MAX_PIN_LENGTH} bind:value={confirm} />
        </div>
      </div>
      <label class="check"><input type="checkbox" bind:checked={reveal} /> Show PIN</label>
      {#if problem}<p class="warn">{problem}</p>{/if}
      {#if mismatch}<p class="warn">The PINs do not match.</p>{/if}
      {#if error}<p class="warn">{error}</p>{/if}
      <div class="row">
        <span class="spacer"></span>
        {#if onCancel}<button type="button" on:click={onCancel}>Cancel</button>{/if}
        <button class="primary" type="submit" disabled={!ready || busy}>Save PIN</button>
      </div>
    </form>
  </div>
</div>

<style>
  .backdrop {
    position: fixed;
    inset: 0;
    z-index: 1000;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 16px;
    background: rgba(0, 0, 0, 0.7);
  }
  .dialog {
    width: min(480px, 100%);
    max-height: 100%;
    overflow: auto;
  }
</style>
