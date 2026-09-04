<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  id: String, placeholder: String, type: { type: String, default: 'text' },
  invalid: Boolean, disabled: Boolean,
  maximumCount: { type: Number, default: null },
})
defineEmits(['update:modelValue'])

const isInvalid = computed(() => {
  const isOverLimit = props.maximumCount !== null
    && String(props.modelValue).length > props.maximumCount
  return props.invalid || isOverLimit
})
</script>

<template>
  <input :id="id" class="input" :class="{ invalid: isInvalid }" :type="type" :value="modelValue"
    :placeholder="placeholder" :disabled="disabled" :aria-invalid="isInvalid"
    @input="$emit('update:modelValue', $event.target.value)" />
</template>

<style scoped>
.input {
  width: 100%; background: var(--surface); border: 1px solid var(--line-strong);
  border-radius: var(--r-sm); padding: 10px 12px; font-size: var(--text-base); color: var(--ink);
  transition: border-color var(--fast) var(--ease), box-shadow var(--fast) var(--ease);
}
.input::placeholder { color: var(--ink-faint); }
.input:focus-visible { outline: none; border-color: var(--accent); box-shadow: var(--focus); }
.input:disabled { background: var(--surface-2); color: var(--ink-mute); }
.input.invalid { border-color: var(--risk-high); }
</style>
