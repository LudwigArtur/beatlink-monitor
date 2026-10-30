<script lang="ts">
  import { api } from '../stores/api';
  import { refreshRemoteInfo, remoteInfo } from '../stores/remotePin';

  let pin = '';
  let message = '';

  // A new connection starts locked: forget what was typed or answered for the previous one.
  $: if ($remoteInfo && !$remoteInfo.unlocked) message = message;

  async function unlock() {
    const result = await api.cdjRemoteUnlock(pin);
    pin = '';
    message = {
      UNLOCKED: '',
      WRONG_PIN: 'Wrong PIN.',
      NO_PIN: 'No PIN has been created on the server yet. It has to be created on the device the server runs on.',
      LOCKED_OUT: `Too many attempts. Try again in ${result.retryAfterSeconds ?? 60} s.`,
      UNKNOWN_CONNECTION: 'Not connected to the backend; try again in a moment.',
    }[result.status];
    await refreshRemoteInfo();
  }

  async function lockAgain() {
    await api.cdjRemoteLock();
    await refreshRemoteInfo();
  }
</script>

{#if $remoteInfo}
  {#if !$remoteInfo.unlocked}
    <section class="card">
      <h3>PIN required</h3>
      <p class="hint">
        Changing settings on a mixer or CDJ and pressing player buttons acts on live equipment, so this device has to enter
        the PIN that was created on the server device. The unlock lasts for this connection only.
      </p>
      <form class="row" on:submit|preventDefault={unlock}>
        <input type="password" inputmode="numeric" autocomplete="off" placeholder="PIN" aria-label="PIN" bind:value={pin} />
        <button class="primary" type="submit" disabled={!pin || !$remoteInfo.pinSet}>Unlock</button>
      </form>
      {#if !$remoteInfo.pinSet}<p class="warn">No PIN has been created on the server yet.</p>{/if}
      {#if message}<p class="warn">{message}</p>{/if}
    </section>
  {:else if !$remoteInfo.local}
    <div class="row">
      <span class="hint">Unlocked for this connection.</span>
      <button on:click={lockAgain}>Lock again</button>
    </div>
  {/if}
{/if}
