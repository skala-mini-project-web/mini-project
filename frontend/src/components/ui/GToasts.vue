<script setup>
import { PhCheckCircle, PhWarningCircle, PhInfo, PhX } from '@phosphor-icons/vue'
import { useToastStore } from '@/stores/toast'
const toasts = useToastStore()
const icon = { success: PhCheckCircle, error: PhWarningCircle, info: PhInfo }
const tone = { success: 'ok', error: 'high', info: 'accent' }
</script>

<template>
  <div class="toasts" aria-live="polite">
    <transition-group name="t">
      <div v-for="t in toasts.items" :key="t.id" class="toast" :class="`tone-${tone[t.type]}`">
        <component :is="icon[t.type]" class="ti" :size="18" weight="fill" />
        <div class="tb">
          <p class="t-sm fw-semibold tt">{{ t.title }}</p>
          <p v-if="t.message" class="t-sm soft">{{ t.message }}</p>
          <p v-if="t.traceId" class="t-xs mono trace">{{ t.traceId }}</p>
        </div>
        <button class="tx" aria-label="닫기" @click="toasts.remove(t.id)"><PhX :size="13" /></button>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toasts { position: fixed; top: var(--s-20); right: var(--s-20); display: flex; flex-direction: column; gap: var(--s-10); z-index: 200; width: min(380px, calc(100vw - 40px)); }
.toast { display: flex; gap: var(--s-12); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r); box-shadow: var(--shadow-2); padding: var(--s-14, 14px); }
.ti { color: var(--fg); flex: none; margin-top: 1px; }
.tb { flex: 1; min-width: 0; }
.tt { color: var(--ink); }
.trace { color: var(--ink-mute); margin-top: 5px; }
.tx { border: 0; background: transparent; color: var(--ink-faint); cursor: pointer; height: 18px; }
.t-enter-active, .t-leave-active { transition: all var(--base) var(--ease); }
.t-enter-from, .t-leave-to { opacity: 0; transform: translateX(14px); }
</style>
