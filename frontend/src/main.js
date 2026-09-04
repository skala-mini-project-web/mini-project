import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import { router } from './router'
import { useSessionStore } from './stores/session'
import { useJobsStore } from './stores/jobs'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

// Restore demo identity before the router guard runs.
useSessionStore(pinia).restore()
// Resume background job notifications (extraction / analysis / review).
useJobsStore(pinia).restore()

app.use(router)
app.mount('#app')
