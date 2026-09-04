<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  PhGauge, PhStack, PhScales, PhShieldCheck, PhFingerprint, PhFolders,
  PhSignOut, PhArrowsLeftRight,
} from '@phosphor-icons/vue'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useJobsStore } from '@/stores/jobs'
import { useToastStore } from '@/stores/toast'

const session = useSessionStore()
const jobs = useJobsStore()
const toast = useToastStore()
const router = useRouter()
const route = useRoute()
const pendingReviews = ref(null)
let reviewPollTimer = null
let reviewPollInFlight = false

const nav = computed(() =>
  session.isPM
    ? [
        { to: '/dashboard', label: '대시보드', icon: PhGauge },
        { to: '/products', label: '상품', icon: PhFolders },
        { to: '/guardfit', label: 'GuardFit', icon: PhShieldCheck },
      ]
    : [
        { to: '/dashboard', label: '대시보드', icon: PhGauge },
        { to: '/reviews', label: '검토함', icon: PhScales },
        { to: '/risk-library', label: 'Risk Library', icon: PhStack },
        { to: '/guardfit', label: 'GuardFit', icon: PhShieldCheck },
        { to: '/audit', label: '감사 로그', icon: PhFingerprint },
      ],
)
const isActive = (to) => route.path === to || route.path.startsWith(to + '/')
async function pollPendingReviews() {
  if (!session.isReviewer || reviewPollInFlight) return
  reviewPollInFlight = true
  try {
    const { totalElements } = await api.listReviews({ status: 'PENDING', page: 0, size: 1 })
    if (!session.isReviewer) return
    const nextCount = Number(totalElements) || 0
    const previousCount = pendingReviews.value
    pendingReviews.value = nextCount
    if (previousCount !== null && nextCount > previousCount) {
      toast.info('새 검토 요청', `검토 요청 ${nextCount - previousCount}건이 새로 도착했습니다.`)
    }
  } catch {
    // 일시적인 폴링 실패에는 마지막으로 확인한 건수를 유지한다.
  } finally {
    reviewPollInFlight = false
  }
}
onMounted(() => {
  if (!session.isReviewer) return
  pollPendingReviews()
  reviewPollTimer = setInterval(pollPendingReviews, 10000)
})
onUnmounted(() => {
  if (reviewPollTimer) clearInterval(reviewPollTimer)
})
function leave() {
  session.logout()
  router.push('/')
}
</script>

<template>
  <aside class="rail">
    <div class="rail-top">
      <RouterLink to="/" class="brand" title="메인으로">
        <img src="/argus-logo.png" alt="ARGUS" class="logo-img" />
      </RouterLink>
    </div>

    <nav class="nav">
      <RouterLink v-for="item in nav" :key="item.to" :to="item.to" class="nav-item" :class="{ on: isActive(item.to) }">
        <component :is="item.icon" :size="18" :weight="isActive(item.to) ? 'fill' : 'regular'" />
        <span>{{ item.label }}</span>
        <span v-if="item.to === '/products' && jobs.unread" class="nav-badge mono">{{ jobs.unread }}</span>
        <span v-if="item.to === '/reviews' && pendingReviews !== null" class="nav-badge mono">{{ pendingReviews }}</span>
      </RouterLink>
    </nav>

    <div class="rail-foot">
      <div class="who">
        <span class="who-name t-sm fw-semibold">{{ session.user?.name }}</span>
        <span class="who-role mono">{{ session.isPM ? 'PRODUCT_MANAGER' : 'COMPLIANCE_REVIEWER' }}</span>
      </div>
      <div class="who-actions">
        <button class="ico" title="역할 전환" aria-label="역할 전환" @click="leave"><PhArrowsLeftRight :size="16" /></button>
        <button class="ico" title="로그아웃" aria-label="로그아웃" @click="leave"><PhSignOut :size="16" /></button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  position: fixed;
  inset: 0 auto 0 0;
  width: var(--rail-w);
  background: var(--surface);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  padding: var(--s-24) var(--s-16) var(--s-16);
  z-index: 40;
}
.rail-top { padding: 0 var(--s-8) var(--s-8); }
.brand { display: inline-flex; align-items: center; gap: var(--s-8); color: var(--ink); }
/* 로고 이미지: 투명 PNG, 높이 고정·폭 자동 */
.logo-img { height: 28px; width: auto; display: block; }
.rail-tag { margin-top: var(--s-10); }

.nav { display: flex; flex-direction: column; gap: 1px; margin-top: var(--s-28); }
.nav-item {
  display: flex; align-items: center; gap: var(--s-12);
  padding: var(--s-10) var(--s-8);
  color: var(--ink-soft);
  font-size: var(--text-sm);
  font-weight: var(--fw-medium);
  border-radius: var(--r-sm);
  position: relative;
  transition: color var(--fast) var(--ease), background var(--fast) var(--ease);
}
.nav-badge {
  margin-left: auto; display: grid; place-items: center; min-width: 18px; height: 18px; padding: 0 5px;
  border-radius: var(--r-pill); background: var(--accent); color: #fff; font-size: 11px; line-height: 1;
}
.nav-item:hover { color: var(--ink); background: var(--surface-2); }
.nav-item.on { color: var(--ink); background: var(--surface-2); }
.nav-item.on::before {
  content: ''; position: absolute; left: -16px; top: 6px; bottom: 6px;
  width: 2px; background: var(--accent); border-radius: 2px;
}

.rail-foot { margin-top: auto; padding-top: var(--s-16); border-top: 1px solid var(--line); display: flex; flex-direction: column; align-items: stretch; gap: var(--s-12); }
.who { display: flex; flex-direction: column; gap: 3px; min-width: 0; padding-left: var(--s-8); }
.who-name { color: var(--ink); }
.who-role { font-size: 11px; color: var(--ink-mute); letter-spacing: 0.02em; white-space: nowrap; }
.who-actions { display: flex; gap: 6px; padding-left: var(--s-8); }
.ico {
  display: grid; place-items: center; width: 30px; height: 30px;
  border: 1px solid var(--line); background: var(--surface); color: var(--ink-soft);
  border-radius: var(--r-sm); cursor: pointer; transition: background var(--fast) var(--ease), color var(--fast) var(--ease);
}
.ico:hover { background: var(--surface-2); color: var(--ink); }

@media (max-width: 900px) {
  .rail {
    position: sticky; inset: auto; width: 100%; flex-direction: row;
    align-items: center; padding: var(--s-12) var(--s-16);
    border-right: 0; border-bottom: 1px solid var(--line); gap: var(--s-16);
  }
  .rail-top { padding: 0; }
  .rail-tag { display: none; }
  .nav { flex-direction: row; margin-top: 0; overflow-x: auto; flex: 1; }
  .nav-item.on::before { left: 4px; right: 4px; top: auto; bottom: -2px; width: auto; height: 2px; }
  .rail-foot { margin-top: 0; padding-top: 0; border-top: 0; flex: none; }
  .who { display: none; }
}
</style>
