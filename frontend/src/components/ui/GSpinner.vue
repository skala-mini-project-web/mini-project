<script setup>
import { PhCircleNotch } from '@phosphor-icons/vue'
defineProps({ size: { type: Number, default: 20 } })
</script>

<template><PhCircleNotch class="spin" :size="size" weight="bold" /></template>

<style scoped>
.spin { color: var(--accent); animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
