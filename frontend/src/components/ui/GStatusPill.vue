<script setup>
import { computed } from 'vue'
import { statusMeta } from '@/lib/format'
const props = defineProps({ status: { type: String, required: true } })
const meta = computed(() => statusMeta(props.status))
</script>

<template>
  <span class="pill" :class="`tone-${meta.tone}`">
    <span class="dot" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex; align-items: center; gap: 6px;
  color: var(--fg); background: transparent;
  font-size: var(--text-xs); font-weight: var(--fw-medium);
  line-height: 1.3; white-space: nowrap;
}
.dot { width: 6px; height: 6px; border-radius: var(--r-pill); background: var(--fg); flex: none; }
</style>
