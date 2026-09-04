import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const routes = [
  { path: '/', name: 'role-select', component: () => import('@/views/RoleSelectView.vue'), meta: { public: true, layout: 'bare' } },
  { path: '/dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
  { path: '/products', name: 'products', component: () => import('@/views/ProductsView.vue'), meta: { role: 'PRODUCT_MANAGER' } },
  { path: '/products/:productId', name: 'product-detail', component: () => import('@/views/ProductDetailView.vue'), props: true },
  { path: '/products/:productId/analyze', name: 'analysis-new', component: () => import('@/views/AnalysisNewView.vue'), props: true, meta: { role: 'PRODUCT_MANAGER' } },
  { path: '/documents/:documentId', name: 'document', component: () => import('@/views/DocumentWorkspaceView.vue'), props: true },
  { path: '/analyses/:analysisId', name: 'analysis', component: () => import('@/views/AnalysisResultView.vue'), props: true },
  { path: '/reviews', name: 'reviews', component: () => import('@/views/ReviewsView.vue'), meta: { role: 'COMPLIANCE_REVIEWER' } },
  { path: '/reviews/:reviewId', name: 'review-detail', component: () => import('@/views/ReviewDetailView.vue'), props: true, meta: { role: 'COMPLIANCE_REVIEWER' } },
  { path: '/risk-library', name: 'risk-library', component: () => import('@/views/RiskLibraryView.vue'), meta: { role: 'COMPLIANCE_REVIEWER' } },
  { path: '/guardfit', name: 'guardfit', component: () => import('@/views/GuardFitView.vue') },
  { path: '/audit', name: 'audit', component: () => import('@/views/AuditLogView.vue'), meta: { role: 'COMPLIANCE_REVIEWER' } },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const session = useSessionStore()
  // Role-select (main page) is always reachable, incl. via the back button.
  if (to.meta.public) return true
  if (!session.isAuthed) return { name: 'role-select' }
  if (to.meta.role && session.role !== to.meta.role) return { name: 'dashboard' }
  return true
})
