<script lang="ts">
  import { answerConfirm, pendingConfirm } from '../stores/confirm';

  // Focus the safe choice (Cancel) so a stray Enter or Space never confirms a write.
  function focusOnMount(node: HTMLElement) {
    node.focus();
  }

  function onKey(event: KeyboardEvent) {
    if (event.key === 'Escape' && $pendingConfirm) answerConfirm(false);
  }
</script>

<svelte:window on:keydown={onKey} />

{#if $pendingConfirm}
  <div class="ui">
    <div class="backdrop" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">
      <div class="dialog card">
        <h3 id="confirm-title">{$pendingConfirm.title}</h3>
        <p class="detail">{$pendingConfirm.detail}</p>
        <div class="row">
          <span class="spacer"></span>
          <button use:focusOnMount on:click={() => answerConfirm(false)}>Cancel</button>
          <button class="primary" on:click={() => answerConfirm(true)}>{$pendingConfirm.confirmLabel}</button>
        </div>
      </div>
    </div>
  </div>
{/if}

<style>
  .backdrop {
    position: fixed;
    inset: 0;
    z-index: 1100;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 16px;
    background: rgba(0, 0, 0, 0.7);
  }
  .dialog {
    width: min(420px, 100%);
    max-height: 100%;
    overflow: auto;
  }
  .detail {
    margin: 0 0 14px;
    white-space: pre-line;
  }
</style>
