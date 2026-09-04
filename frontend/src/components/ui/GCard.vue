<script setup>
defineProps({
  pad: { type: String, default: 'lg' }, // lg 28 | md 20 | sm 14 | none
  flat: Boolean, // no border, no bg (pure grouping)
  inset: Boolean, // nested surface
  elevated: Boolean,
})
</script>

<template>
  <div class="card" :class="[{ flat, inset, elevated }, `pad-${pad}`]"><slot /></div>
</template>

<style scoped>
.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
}
.card.inset { background: var(--surface-2); border-color: transparent; }
.card.flat { background: transparent; border-color: transparent; }
.card.elevated { box-shadow: var(--shadow-2); border-color: var(--line-soft); }
.pad-lg { padding: var(--s-28); }
.pad-md { padding: var(--s-20); }
.pad-sm { padding: var(--s-14, 14px); }
.pad-none { padding: 0; }
</style>
