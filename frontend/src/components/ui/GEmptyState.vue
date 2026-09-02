<script setup>
defineProps({ title: { type: String, required: true }, description: String })
</script>

<template>
  <div class="empty">
    <div class="ei"><slot name="icon" /></div>
    <div class="et">
      <p class="t-base fw-semibold">{{ title }}</p>
      <p v-if="description" class="t-sm mute">{{ description }}</p>
    </div>
    <div v-if="$slots.action" class="ea"><slot name="action" /></div>
  </div>
</template>

<style scoped>
.empty { display: flex; flex-direction: column; align-items: flex-start; gap: var(--s-12); padding: var(--s-40) 0; }
.ei { display: grid; place-items: center; width: 40px; height: 40px; border-radius: var(--r); background: var(--surface-2); color: var(--ink-mute); }
.et { display: flex; flex-direction: column; gap: 4px; }
.ea { margin-top: var(--s-4); }
</style>
