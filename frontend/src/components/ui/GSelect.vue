<script setup>
import { PhCaretDown } from '@phosphor-icons/vue'
defineProps({
  modelValue: { type: [String, Number], default: '' },
  id: String, options: { type: Array, default: () => [] }, invalid: Boolean, disabled: Boolean,
})
defineEmits(['update:modelValue'])
</script>

<template>
  <div class="sel" :class="{ invalid, disabled }">
    <select :id="id" :value="modelValue" :disabled="disabled"
      @change="$emit('update:modelValue', $event.target.value)">
      <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
    </select>
    <PhCaretDown class="caret" :size="15" />
  </div>
</template>

<style scoped>
.sel {
  position: relative; display: flex; align-items: center;
  background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--r-sm);
}
.sel:focus-within { border-color: var(--accent); box-shadow: var(--focus); }
.sel.invalid { border-color: var(--risk-high); }
.sel.disabled { background: var(--surface-2); }
select {
  appearance: none; width: 100%; background: transparent; border: 0;
  padding: 10px 36px 10px 12px; font-size: var(--text-base); color: var(--ink); cursor: pointer;
}
select:focus-visible { outline: none; }
.caret { position: absolute; right: 11px; color: var(--ink-mute); pointer-events: none; }
</style>
