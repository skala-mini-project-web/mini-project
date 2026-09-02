<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import GToasts from '@/components/ui/GToasts.vue'

const session = useSessionStore()
const route = useRoute()
const chrome = computed(() => session.isAuthed && route.meta.layout !== 'bare')
</script>

<template>
  <div :class="chrome ? 'shell' : 'bare'">
    <AppSidebar v-if="chrome" />
    <main :class="chrome ? 'content' : 'bare-main'">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
  <GToasts />
</template>

<style scoped>
.shell { min-height: 100dvh; }
.content {
  margin-left: var(--rail-w);
  padding: var(--s-48) var(--s-48) var(--s-96);
}
.bare-main { min-height: 100dvh; }
.fade-enter-active, .fade-leave-active { transition: opacity var(--base) var(--ease); }
.fade-enter-from, .fade-leave-to { opacity: 0; }

@media (max-width: 900px) {
  .content { margin-left: 0; padding: var(--s-24) var(--s-20) var(--s-64); }
}
</style>
