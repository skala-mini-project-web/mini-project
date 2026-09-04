<script setup>
import { computed } from 'vue'
import { severityMeta } from '@/lib/format'
const props = defineProps({ severity: { type: String, required: true } })
const meta = computed(() => severityMeta(props.severity))
</script>

<template>
  <span class="sev mono" :class="`tone-${meta.tone}`">{{ meta.label }}</span>
</template>

<style scoped>
.sev {
  display: inline-flex; align-items: center;
  color: var(--fg); background: var(--bg);
  font-size: 11px; font-weight: var(--fw-medium); letter-spacing: 0.08em;
  padding: 3px 7px; border-radius: var(--r-xs); line-height: 1.2;
}
</style>
