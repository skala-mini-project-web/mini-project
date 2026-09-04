<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: String,
  hint: String,
  error: String,
  required: Boolean,
  forId: String,
  currentCount: { type: Number, default: null },
  maximumCount: { type: Number, default: null },
})

const hasCount = computed(() => props.currentCount !== null && props.maximumCount !== null)
const isOverLimit = computed(() => hasCount.value && props.currentCount > props.maximumCount)
</script>

<template>
  <div class="field">
    <label v-if="label" :for="forId" class="lbl t-sm fw-semibold">
      {{ label }}<span v-if="required" class="req">*</span>
    </label>
    <slot />
    <div v-if="error || hint || hasCount" class="meta">
      <p v-if="error" class="err t-xs">{{ error }}</p>
      <p v-else-if="hint" class="hint t-xs mute">{{ hint }}</p>
      <p v-if="hasCount" class="count t-xs" :class="{ over: isOverLimit }" aria-live="polite">
        {{ currentCount }} / {{ maximumCount }}자
      </p>
    </div>
  </div>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: var(--s-8); }
.lbl { color: var(--ink); }
.req { color: var(--risk-high); margin-left: 3px; }
.err { color: var(--risk-high); }
.meta { display: flex; justify-content: space-between; gap: var(--s-8); }
.count { margin-left: auto; color: var(--ink-mute); white-space: nowrap; }
.count.over { color: var(--risk-high); }
</style>
