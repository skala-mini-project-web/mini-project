<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { PhFiles, PhTrash } from '@phosphor-icons/vue'
import { documentBatchesApi } from '@/api/documentBatches'
import { validateUploadFile } from '@/lib/upload'
import { formatBytes } from '@/lib/format'
import { useToastStore } from '@/stores/toast'
import GModal from '@/components/ui/GModal.vue'
import GButton from '@/components/ui/GButton.vue'

const props = defineProps({ productId: { type: String, required: true } })
const emit = defineEmits(['close'])
const router = useRouter()
const toast = useToastStore()
const input = ref(null)
const files = ref([])
const error = ref('')
const submitting = ref(false)

function pick() { input.value?.click() }
function remove(index) { files.value.splice(index, 1); error.value = '' }
async function selectFiles(event) {
  const selected = Array.from(event.target.files || [])
  event.target.value = ''
  error.value = ''
  if (selected.length < 1 || selected.length > 100) {
    error.value = '파일은 1개 이상 100개 이하로 선택해 주세요.'
    files.value = []
    return
  }
  for (const file of selected) {
    const result = await validateUploadFile(file)
    if (!result.ok) {
      error.value = `${file.name}: ${result.message}`
      files.value = []
      return
    }
  }
  files.value = selected
}
async function submit() {
  if (!files.value.length || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const batch = await documentBatchesApi.create(props.productId, files.value)
    toast.success('일괄 업로드 접수', `${batch.acceptedItemCount}개 문서의 처리를 시작했습니다.`)
    emit('close')
    router.push(`/document-batches/${batch.batchId}`)
  } catch (err) {
    toast.fromError(err)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <GModal title="문서 일괄 업로드" @close="!submitting && emit('close')">
    <input ref="input" class="sr-only" type="file" accept=".pdf,.pptx" multiple @change="selectFiles" />
    <button class="picker" type="button" :disabled="submitting" @click="pick">
      <PhFiles :size="26" />
      <strong>PDF/PPTX 파일 선택</strong>
      <span>한 번에 1–100개, 파일당 최대 10MB</span>
    </button>
    <p v-if="error" class="error t-sm" role="alert">{{ error }}</p>
    <div v-if="files.length" class="selection">
      <p class="t-sm fw-medium">선택한 파일 <span class="mono">{{ files.length }}</span></p>
      <ul>
        <li v-for="(file, index) in files" :key="`${file.name}-${file.size}-${index}`">
          <span class="name">{{ file.name }}</span>
          <span class="mono size">{{ formatBytes(file.size) }}</span>
          <button type="button" :aria-label="`${file.name} 제거`" :disabled="submitting" @click="remove(index)"><PhTrash :size="15" /></button>
        </li>
      </ul>
    </div>
    <template #footer>
      <GButton variant="secondary" :disabled="submitting" @click="emit('close')">취소</GButton>
      <GButton variant="primary" :loading="submitting" :disabled="!files.length" @click="submit">일괄 처리 시작</GButton>
    </template>
  </GModal>
</template>

<style scoped>
.picker { width: 100%; min-height: 138px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--s-8); border: 1px dashed var(--line-strong); border-radius: var(--r-md); color: var(--ink-mute); background: var(--surface-2); cursor: pointer; }
.picker strong { color: var(--ink); }
.picker span { font-size: var(--text-sm); }
.picker:hover:not(:disabled) { border-color: var(--ink-mute); }
.error { color: var(--risk-high); margin-top: var(--s-12); }
.selection { margin-top: var(--s-20); }
ul { list-style: none; margin-top: var(--s-8); max-height: 220px; overflow-y: auto; border-top: 1px solid var(--line); }
li { display: flex; align-items: center; gap: var(--s-10); padding: var(--s-10) 0; border-bottom: 1px solid var(--line); }
.name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.size { color: var(--ink-mute); font-size: var(--text-xs); }
li button { display: grid; place-items: center; border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; }
</style>
