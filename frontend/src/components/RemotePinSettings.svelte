<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '../stores/api';
  import { pinProblem, refreshRemoteInfo, remoteInfo, MAX_PIN_LENGTH } from '../stores/remotePin';
  import { confirmHardwareWrites } from '../stores/confirm';

  let pin = '';
  let confirm = '';
  let message = '';
  let failed = false;
  let busy = false;

  $: info = $remoteInfo;
  $: problem = pin ? pinProblem(pin) : '';
  $: ready = pin !== '' && !problem && pin === confirm;

  onMount(() => void refreshRemoteInfo());

  async function save() {
    if (!ready || busy) return;
    busy = true;
    const result = await api.cdjRemoteSetPin(pin);
    busy = false;
    failed = result.status !== 'SAVED';
    message = failed ? (result.message ?? 'The PIN could not be saved.') : 'PIN changed. Other devices have to enter it again.';
    if (!failed) {
      pin = '';
      confirm = '';
      await refreshRemoteInfo();
    }
  }
</script>

<section class="card">
  <h3>Safety</h3>
  <label class="check" title="Every write to a mixer or CDJ and every player button press asks for confirmation in a popup.">
    <input type="checkbox" bind:checked={$confirmHardwareWrites} />
    Confirm every write and button press
  </label>
  <p class="hint">
    You can turn the confirmation off for the current session. It is on again after reloading the page. With confirmation
    on, the hold button (CUE) acts as a single press.
  </p>

  <h4>PIN for other devices</h4>
  {#if !info}
    <p class="hint">Status unknown.</p>
  {:else if !info.local}
    <p class="hint">
      Other devices need the PIN to write to mixers and CDJs or to press buttons. It is created and changed on the device
      the server runs on.
    </p>
  {:else if !info.lanAccessEnabled}
    <p class="hint">
      LAN access is off, so no PIN is needed. A new PIN is requested every time LAN access is turned on (General).
    </p>
  {:else}
    <p class="hint">
      {info.pinSet ? 'A PIN is set.' : 'No PIN is set.'} It is stored only as a hash and cannot be displayed. If you forget
      it, turn LAN access off and on again (General) or change it here. This device needs no PIN.
    </p>
    <form on:submit|preventDefault={save}>
      <div class="field">
        <label class="label" for="pin-set-new">New PIN</label>
        <div class="control"><input id="pin-set-new" type="password" autocomplete="off" maxlength={MAX_PIN_LENGTH} bind:value={pin} /></div>
      </div>
      <div class="field">
        <label class="label" for="pin-set-confirm">Repeat new PIN</label>
        <div class="control"><input id="pin-set-confirm" type="password" autocomplete="off" maxlength={MAX_PIN_LENGTH} bind:value={confirm} /></div>
      </div>
      {#if problem}<p class="warn">{problem}</p>{/if}
      {#if confirm !== '' && confirm !== pin}<p class="warn">The PINs do not match.</p>{/if}
      <div class="row">
        <button class="primary" type="submit" disabled={!ready || busy}>Change PIN</button>
        {#if message}<span class:ok={!failed} class:warn={failed}>{message}</span>{/if}
      </div>
    </form>
  {/if}
</section>
