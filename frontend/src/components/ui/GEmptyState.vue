<script setup>
defineProps({ title: { type: String, required: true }, description: String })
</script>

<template>
  <!-- 빈 상태: 큰 중앙 블록 아님 — 아이콘·문구 가로 컴팩트, 액션은 우측 -->
  <div class="empty">
    <div class="ei"><slot name="icon" /></div>
    <div class="et">
      <p class="t-sm fw-semibold">{{ title }}</p>
      <p v-if="description" class="t-sm mute">{{ description }}</p>
    </div>
    <div v-if="$slots.action" class="ea"><slot name="action" /></div>
  </div>
</template>

<style scoped>
.empty { display: flex; align-items: center; gap: var(--s-12); padding: var(--s-16) 0; flex-wrap: wrap; }
.ei { display: grid; place-items: center; width: 30px; height: 30px; flex: none; border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink-mute); }
.et { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ea { margin-left: auto; }
</style>
