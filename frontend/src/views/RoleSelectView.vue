<script setup>
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowRight } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useToastStore } from '@/stores/toast'
import GSpinner from '@/components/ui/GSpinner.vue'
import PipelineGraph from '@/components/PipelineGraph.vue'

const router = useRouter()
const session = useSessionStore()
const toast = useToastStore()
const users = ref([])
const loading = ref(true)
const entering = ref(null)

const roles = computed(() => [
  { role: 'PRODUCT_MANAGER', title: '상품 담당자', line: '상품을 등록하고 문서를 올려 표현 리스크를 분석합니다', user: users.value.find((u) => u.role === 'PRODUCT_MANAGER') },
  { role: 'COMPLIANCE_REVIEWER', title: '컴플라이언스 검토자', line: '근거를 확인하고 승인, 반려하며 보호조치를 만듭니다', user: users.value.find((u) => u.role === 'COMPLIANCE_REVIEWER') },
])

onMounted(async () => {
  try { users.value = await api.listUsers() } catch (e) { toast.fromError(e) } finally { loading.value = false }
})
async function enter(r) {
  if (!r.user || entering.value) return
  entering.value = r.role
  try { await session.login(r.user.id, r.role); router.push('/dashboard') }
  catch (e) { toast.fromError(e); entering.value = null }
}
</script>

<template>
  <div class="entry">
    <div class="frame">
      <!-- meta bar -->
      <header class="meta">
        <span class="brand"><img src="/argus-logo.png" alt="ARGUS" class="logo-img" /></span>
      </header>

      <!-- hero: copy (dominant) + a real product instrument, off-balance -->
      <!-- hero: animated pipeline graph over a grid, headline below (ref style) -->
      <section class="hero">
        <PipelineGraph class="hero-graph" />
        <div class="copy">
          <h1 class="d-display title">리스크를 출시 전에 잡습니다</h1>
          <p class="t-lg soft lede">
            금융상품 문서에서 오인 유발 표현을 근거와 함께 찾아, 컴플라이언스 검토와 보호조치까지 한 흐름으로 연결하는 워크스페이스.
          </p>
        </div>
      </section>

      <!-- role entry: two hairline-divided cells, not cards -->
      <section class="enter">
        <p class="mono enter-label">역할 선택</p>
        <div v-if="loading" class="roles-loading"><GSpinner :size="20" /></div>
        <div v-else class="roles">
          <button v-for="r in roles" :key="r.role" class="role" :disabled="!r.user || entering" @click="enter(r)">
            <span class="role-arrow">
              <GSpinner v-if="entering === r.role" :size="16" />
              <PhArrowRight v-else :size="18" />
            </span>
            <span class="role-body">
              <span class="role-title d-h3">{{ r.title }}</span>
              <span class="t-sm soft role-line">{{ r.line }}</span>
              <span v-if="r.user" class="mono role-id">{{ r.user.id }} · {{ r.user.name }}</span>
            </span>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.entry { min-height: 100dvh; background: var(--paper); display: flex; }
.frame { width: 100%; max-width: 1120px; margin-inline: auto; padding: clamp(24px, 4vw, 56px); display: flex; flex-direction: column; }

.meta { display: flex; align-items: center; justify-content: space-between; padding-bottom: var(--s-20); border-bottom: 1px solid var(--line); }
.brand { display: inline-flex; align-items: center; gap: var(--s-10); }
.logo-img { height: 26px; width: auto; display: block; }
.sub { font-size: 11px; color: var(--ink-mute); letter-spacing: 0.18em; padding-left: var(--s-10); border-left: 1px solid var(--line-strong); }
.deploy { font-size: 11px; color: var(--ink-mute); letter-spacing: 0.08em; }

.hero { display: flex; flex-direction: column; padding-block: clamp(20px, 3vw, 40px) clamp(40px, 6vw, 72px); }
.hero-graph { width: 100%; max-width: 1000px; margin: 0 auto clamp(20px, 3vw, 44px); }
.copy { text-align: center; }
.title { letter-spacing: -0.03em; overflow-wrap: normal; text-wrap: nowrap; }
.lede { margin: clamp(28px, 3.5vw, 44px) auto 0; max-width: 50ch; }
@media (max-width: 640px) { .title { text-wrap: balance; white-space: normal; } }

.art { margin: 0; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); box-shadow: var(--shadow-2); padding: var(--s-24); align-self: center; }
.art-head { display: flex; align-items: center; justify-content: space-between; }
.art-id { font-size: 11px; color: var(--ink-faint); }
.art-score { display: flex; align-items: baseline; gap: var(--s-8); margin-top: var(--s-16); }
.art-num { font-size: 56px; font-weight: var(--fw-semibold); line-height: 0.9; letter-spacing: -0.05em; color: var(--risk-high); }
.art-unit { font-size: var(--text-sm); color: var(--ink-faint); }
.art-band { margin-left: auto; align-self: center; font-size: var(--text-xs); font-weight: var(--fw-semibold); color: var(--risk-high); background: var(--risk-high-wash); padding: 4px 9px; border-radius: var(--r-xs); }
.art-bar { height: 4px; border-radius: var(--r-pill); background: var(--surface-3); overflow: hidden; margin-top: var(--s-16); }
.art-bar span { display: block; height: 100%; background: var(--risk-high); border-radius: var(--r-pill); }
.art-find { margin-top: var(--s-20); padding-top: var(--s-16); border-top: 1px solid var(--line); }
.art-tags { display: flex; gap: var(--s-8); }
.art-sev { font-size: 10.5px; font-weight: var(--fw-medium); letter-spacing: 0.08em; color: var(--risk-high); background: var(--risk-high-wash); padding: 3px 7px; border-radius: var(--r-xs); }
.art-rule { font-size: 10.5px; color: var(--ink-mute); background: var(--surface-2); padding: 3px 7px; border-radius: var(--r-xs); letter-spacing: 0.02em; }
.art-msg { margin-top: var(--s-12); font-size: var(--text-sm); font-weight: var(--fw-medium); }
.art-q { margin-top: var(--s-8); font-size: var(--text-sm); color: var(--ink-soft); background: var(--surface-2); border-radius: var(--r-sm); padding: var(--s-10) var(--s-12); line-height: 1.5; }

.enter { margin-top: auto; }
.enter-label { font-size: var(--text-xs); color: var(--ink-mute); letter-spacing: 0.14em; text-transform: uppercase; margin-bottom: var(--s-16); }
.roles-loading { display: grid; place-items: center; padding: var(--s-32); border-top: 1px solid var(--line-strong); }
.roles { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--line-strong); }
.role {
  position: relative; display: flex; align-items: center; justify-content: center;
  text-align: center; background: transparent; border: 0; border-bottom: 1px solid var(--line);
  padding: var(--s-24) var(--s-12); cursor: pointer; transition: background var(--fast) var(--ease);
}
.role:first-child { border-right: 1px solid var(--line); }
.role:hover:not(:disabled) { background: var(--surface-2); }
.role:disabled { opacity: 0.5; cursor: progress; }
.role-body { display: flex; flex-direction: column; align-items: center; gap: var(--s-6); min-width: 0; }
.role-title { letter-spacing: -0.02em; }
.role-line { max-width: 34ch; }
.role-id { font-size: 11px; color: var(--ink-mute); margin-top: 2px; }
.role-arrow {
  position: absolute; right: var(--s-20); display: grid; place-items: center; width: 40px; height: 40px;
  border-radius: var(--r-pill); border: 1px solid var(--line-strong); color: var(--ink);
  transition: transform var(--base) var(--ease), background var(--fast) var(--ease), border-color var(--fast) var(--ease), color var(--fast) var(--ease);
}
.role:hover:not(:disabled) .role-arrow { background: var(--ink); border-color: var(--ink); color: #fff; transform: translateX(3px); }

@media (max-width: 860px) {
  .hero { flex-direction: column; align-items: stretch; gap: var(--s-32); padding-block: var(--s-40); }
  .art { flex-basis: auto; }
  .roles { grid-template-columns: 1fr; }
  .role:first-child { border-right: 0; }
}
</style>
