<script setup>
import { computed } from 'vue'
const props = defineProps({ value: { type: Number, default: 0 }, tone: { type: String, default: 'accent' } })
const ratio = computed(() => Math.min(100, Math.max(0, props.value)) / 100)
</script>

<template>
  <div class="track" :class="`tone-${tone}`" role="progressbar" :aria-valuenow="value" aria-valuemin="0" aria-valuemax="100">
    <div class="fill" :style="{ transform: `scaleX(${ratio})` }" />
  </div>
</template>

<style scoped>
.track { width: 100%; height: 4px; border-radius: var(--r-pill); background: var(--surface-3); overflow: hidden; }
.fill { height: 100%; width: 100%; transform-origin: left center; border-radius: var(--r-pill); background: var(--fg, var(--accent)); transition: transform var(--base) var(--ease); }
</style>
