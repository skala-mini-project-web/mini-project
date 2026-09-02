<script setup>
defineProps({
  modelValue: { type: String, default: '' },
  id: String, placeholder: String, rows: { type: Number, default: 5 },
  invalid: Boolean, disabled: Boolean,
})
defineEmits(['update:modelValue'])
</script>

<template>
  <textarea :id="id" class="ta" :class="{ invalid }" :rows="rows" :value="modelValue"
    :placeholder="placeholder" :disabled="disabled"
    @input="$emit('update:modelValue', $event.target.value)" />
</template>

<style scoped>
.ta {
  width: 100%; background: var(--surface); border: 1px solid var(--line-strong);
  border-radius: var(--r-sm); padding: 11px 12px; font-size: var(--text-base);
  line-height: 1.6; color: var(--ink); resize: vertical;
  transition: border-color var(--fast) var(--ease), box-shadow var(--fast) var(--ease);
}
.ta::placeholder { color: var(--ink-faint); }
.ta:focus-visible { outline: none; border-color: var(--accent); box-shadow: var(--focus); }
.ta:disabled { background: var(--surface-2); }
.ta.invalid { border-color: var(--risk-high); }
</style>
