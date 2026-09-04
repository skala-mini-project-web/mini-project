<script setup>
import { PhCircleNotch } from '@phosphor-icons/vue'

defineProps({
  variant: { type: String, default: 'primary' }, // primary | secondary | ghost | danger
  size: { type: String, default: 'md' }, // md | sm
  type: { type: String, default: 'button' },
  loading: Boolean,
  disabled: Boolean,
  block: Boolean,
})
</script>

<template>
  <button
    :type="type"
    class="btn"
    :class="[`v-${variant}`, `s-${size}`, { block, loading }]"
    :disabled="disabled || loading"
  >
    <PhCircleNotch v-if="loading" class="spin" :size="size === 'sm' ? 14 : 16" weight="bold" />
    <slot v-else name="icon" />
    <span v-if="$slots.default"><slot /></span>
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: var(--s-8);
  border-radius: var(--r); border: 1px solid transparent;
  font-family: var(--font-sans); font-weight: var(--fw-semibold);
  font-size: var(--text-sm); line-height: 1; white-space: nowrap; cursor: pointer;
  transition: transform var(--fast) var(--ease), background var(--fast) var(--ease),
    border-color var(--fast) var(--ease), color var(--fast) var(--ease);
}
.s-md { padding: 11px 18px; }
.s-sm { padding: 8px 12px; font-size: var(--text-xs); }
.block { width: 100%; }
.btn:active:not(:disabled) { transform: translateY(1px); }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn.loading { cursor: progress; }

.v-primary { background: var(--ink); color: #fff; }
.v-primary:hover:not(:disabled) { background: #000; }

.v-secondary { background: var(--surface); color: var(--ink); border-color: var(--line-strong); }
.v-secondary:hover:not(:disabled) { background: var(--surface-2); border-color: var(--ink-faint); }

.v-ghost { background: transparent; color: var(--ink-soft); }
.v-ghost:hover:not(:disabled) { background: var(--surface-2); color: var(--ink); }

.v-danger { background: var(--risk-high); color: #fff; }
.v-danger:hover:not(:disabled) { background: #b62b23; }

.spin { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
