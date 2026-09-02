<script setup>
defineProps({
  modelValue: { type: [String, Number], default: '' },
  id: String, placeholder: String, type: { type: String, default: 'text' },
  invalid: Boolean, disabled: Boolean,
})
defineEmits(['update:modelValue'])
</script>

<template>
  <input :id="id" class="input" :class="{ invalid }" :type="type" :value="modelValue"
    :placeholder="placeholder" :disabled="disabled"
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
