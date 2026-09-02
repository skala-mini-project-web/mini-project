<script setup>
defineProps({ label: String, hint: String, error: String, required: Boolean, forId: String })
</script>

<template>
  <div class="field">
    <label v-if="label" :for="forId" class="lbl t-sm fw-semibold">
      {{ label }}<span v-if="required" class="req">*</span>
    </label>
    <slot />
    <p v-if="error" class="err t-xs">{{ error }}</p>
    <p v-else-if="hint" class="hint t-xs mute">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: var(--s-8); }
.lbl { color: var(--ink); }
.req { color: var(--risk-high); margin-left: 3px; }
.err { color: var(--risk-high); }
</style>
