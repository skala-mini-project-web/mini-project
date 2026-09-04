<script setup>
defineProps({ w: { type: String, default: '100%' }, h: { type: String, default: '14px' }, radius: { type: String, default: 'var(--r-sm)' } })
</script>

<template><span class="sk" :style="{ width: w, height: h, borderRadius: radius }" aria-hidden="true" /></template>

<style scoped>
.sk { display: block; background: linear-gradient(100deg, var(--surface-2) 30%, var(--surface-3) 50%, var(--surface-2) 70%); background-size: 200% 100%; animation: sh 1.3s ease-in-out infinite; }
@keyframes sh { from { background-position: 200% 0; } to { background-position: -200% 0; } }
@media (prefers-reduced-motion: reduce) { .sk { animation: none; } }
</style>
