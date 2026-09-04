<script setup>
import { onMounted, onUnmounted, ref, nextTick } from 'vue'
import { PhX } from '@phosphor-icons/vue'
const props = defineProps({ title: String, open: { type: Boolean, default: true } })
const emit = defineEmits(['close'])
const dialog = ref(null)
let prevFocus = null

const FOCUSABLE = 'a[href],button:not([disabled]),textarea,input,select,[tabindex]:not([tabindex="-1"])'
function focusables() {
  return dialog.value ? Array.from(dialog.value.querySelectorAll(FOCUSABLE)).filter((el) => el.offsetParent !== null) : []
}
function onKey(e) {
  if (e.key === 'Escape') return emit('close')
  if (e.key !== 'Tab') return
  const els = focusables()
  if (!els.length) return
  const first = els[0]
  const last = els[els.length - 1]
  if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
}
onMounted(async () => {
  prevFocus = document.activeElement
  document.addEventListener('keydown', onKey)
  await nextTick()
  const els = focusables()
  ;(els[0] || dialog.value)?.focus()
})
onUnmounted(() => {
  document.removeEventListener('keydown', onKey)
  if (prevFocus && prevFocus.focus) prevFocus.focus()
})
</script>

<template>
  <teleport to="body">
    <div v-if="open" class="ov" @click.self="emit('close')">
      <div ref="dialog" class="dlg" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1">
        <header class="dh">
          <h2 class="d-h3">{{ title }}</h2>
          <button class="x" type="button" aria-label="닫기" @click="emit('close')"><PhX :size="17" /></button>
        </header>
        <div class="db"><slot /></div>
        <footer v-if="$slots.footer" class="df"><slot name="footer" /></footer>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.ov { position: fixed; inset: 0; background: rgba(14, 17, 22, 0.35); display: grid; place-items: center; padding: var(--s-24); z-index: 100; animation: fade var(--fast) var(--ease); }
@keyframes fade { from { opacity: 0; } }
.dlg { width: 100%; max-width: 500px; background: var(--surface); border-radius: var(--r-lg); box-shadow: var(--shadow-pop); max-height: 90dvh; display: flex; flex-direction: column; }
.dlg:focus-visible { outline: none; }
.dh { display: flex; align-items: center; justify-content: space-between; padding: var(--s-20) var(--s-24); border-bottom: 1px solid var(--line); }
.x { display: grid; place-items: center; width: 34px; height: 34px; border: 0; background: transparent; color: var(--ink-mute); border-radius: var(--r-sm); cursor: pointer; }
.x:hover { background: var(--surface-2); color: var(--ink); }
.db { padding: var(--s-24); overflow-y: auto; }
.df { display: flex; justify-content: flex-end; gap: var(--s-10); padding: var(--s-16) var(--s-24); border-top: 1px solid var(--line); }
</style>
