<script setup>
import { computed } from 'vue'
import { PhCaretLeft, PhCaretRight } from '@phosphor-icons/vue'

// Numbered pagination. Model is a 0-based page index; labels render 1-based.
const props = defineProps({
  modelValue: { type: Number, default: 0 },
  total: { type: Number, default: 0 },
  size: { type: Number, default: 10 },
})
const emit = defineEmits(['update:modelValue'])
const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const pages = computed(() => {
  const n = pageCount.value
  const c = props.modelValue
  if (n <= 7) return Array.from({ length: n }, (_, i) => i)
  const keep = new Set([0, n - 1, c, c - 1, c + 1])
  const sorted = [...keep].filter((i) => i >= 0 && i < n).sort((a, b) => a - b)
  const out = []
  let prev = -1
  for (const i of sorted) {
    if (i - prev > 1) out.push(-1) // ellipsis marker
    out.push(i)
    prev = i
  }
  return out
})
function go(p) {
  if (p < 0 || p >= pageCount.value || p === props.modelValue) return
  emit('update:modelValue', p)
}
</script>

<template>
  <nav v-if="pageCount > 1" class="pg" aria-label="페이지 이동">
    <button class="pg-b" :disabled="modelValue === 0" aria-label="이전 페이지" @click="go(modelValue - 1)">
      <PhCaretLeft :size="15" />
    </button>
    <template v-for="(p, i) in pages" :key="i">
      <span v-if="p === -1" class="pg-gap" aria-hidden="true">···</span>
      <button v-else class="pg-b mono" :class="{ on: p === modelValue }" :aria-current="p === modelValue ? 'page' : undefined" @click="go(p)">
        {{ p + 1 }}
      </button>
    </template>
    <button class="pg-b" :disabled="modelValue >= pageCount - 1" aria-label="다음 페이지" @click="go(modelValue + 1)">
      <PhCaretRight :size="15" />
    </button>
  </nav>
</template>

<style scoped>
.pg { display: flex; align-items: center; gap: var(--s-6); justify-content: center; }
.pg-b {
  display: grid; place-items: center; min-width: 34px; height: 34px; padding: 0 6px;
  border: 1px solid var(--line-strong); background: var(--surface); color: var(--ink-soft);
  border-radius: var(--r-sm); font-size: var(--text-sm); cursor: pointer;
  transition: background var(--fast) var(--ease), color var(--fast) var(--ease), border-color var(--fast) var(--ease);
}
.pg-b:hover:not(:disabled):not(.on) { background: var(--surface-2); color: var(--ink); }
.pg-b.on { background: var(--ink); border-color: var(--ink); color: #fff; }
.pg-b:disabled { opacity: 0.4; cursor: not-allowed; }
.pg-gap { color: var(--ink-faint); padding: 0 2px; letter-spacing: 0.1em; }
</style>
