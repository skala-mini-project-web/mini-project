<script setup>
defineProps({
  kicker: String,
  title: { type: String, required: true },
  subtitle: String,
})
</script>

<template>
  <header class="ph">
    <div class="ph-text">
      <p v-if="kicker" class="kicker ph-kick">{{ kicker }}</p>
      <h1 class="d-h1">{{ title }}</h1>
      <p v-if="subtitle" class="t-base soft ph-sub">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="ph-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.ph { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--s-24); flex-wrap: wrap; }
.ph-kick { margin-bottom: var(--s-12); }
.ph-sub { margin-top: var(--s-12); max-width: 64ch; }
.ph-actions { display: flex; gap: var(--s-10); flex: none; }
</style>
