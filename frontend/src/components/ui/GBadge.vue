<script setup>
defineProps({
  tone: { type: String, default: 'neutral' }, // accent|high|med|low|ok|neutral
  mono: { type: Boolean, default: true },
})
</script>

<template>
  <span class="badge" :class="[`tone-${tone}`, { mono }]"><slot /></span>
</template>

<style scoped>
.badge {
  display: inline-flex; align-items: center; gap: 5px;
  color: var(--fg); background: var(--bg);
  font-size: var(--text-micro); font-weight: var(--fw-medium);
  padding: 3px 7px; border-radius: var(--r-xs); line-height: 1.35;
  letter-spacing: 0.04em; white-space: nowrap;
}
.badge.mono { font-family: var(--font-mono); letter-spacing: 0.02em; }
</style>
