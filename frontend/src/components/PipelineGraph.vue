<script setup>
import { ref, onMounted } from 'vue'
import { PhFileText, PhClipboardText, PhSparkle, PhScales, PhShieldCheck } from '@phosphor-icons/vue'

// GuardLab flow as an animated node-graph (adapted from the Cloudflare Workers
// web-section style: light blueprint grid + connectors + a traveling packet).
// Motion is motivated: it shows the product pipeline. Frozen under reduced-motion.
const nodes = [
  { x: 150, y: 300, icon: PhFileText, label: '문서', n: '01' },
  { x: 400, y: 150, icon: PhClipboardText, label: '추출', n: '02' },
  { x: 620, y: 320, icon: PhSparkle, label: 'AI 분석', n: '03', accent: true },
  { x: 860, y: 150, icon: PhScales, label: '검토', n: '04' },
  { x: 1080, y: 300, icon: PhShieldCheck, label: 'GuardFit', n: '05' },
]
const D = 'M150 300 C 300 300 260 150 400 150 C 540 150 490 320 620 320 C 760 320 720 150 860 150 C 1000 150 940 300 1080 300'

const reduce = ref(false)
onMounted(() => {
  reduce.value = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
})
</script>

<template>
  <div class="graph" :class="{ still: reduce }" aria-hidden="true">
    <svg class="wires" viewBox="0 0 1200 460" preserveAspectRatio="none">
      <defs>
        <pattern id="gl-grid" width="48" height="48" patternUnits="userSpaceOnUse">
          <path class="grid-line" d="M48 0H0V48" />
        </pattern>
      </defs>
      <rect class="grid-rect" x="0" y="0" width="1200" height="460" fill="url(#gl-grid)" />
      <path :d="D" class="wire" />
      <path v-if="!reduce" :d="D" class="wire-flow" />
      <circle v-if="!reduce" r="5" class="packet">
        <animateMotion dur="5.5s" repeatCount="indefinite" :path="D" rotate="auto" />
      </circle>
    </svg>

    <div
      v-for="(node, i) in nodes"
      :key="node.label"
      class="node"
      :class="{ acc: node.accent }"
      :style="{ left: (node.x / 1200) * 100 + '%', top: (node.y / 460) * 100 + '%', '--d': i * 0.11 + 's' }"
    >
      <span class="tile">
        <span class="tile-n mono">{{ node.n }}</span>
        <component :is="node.icon" :size="22" :weight="node.accent ? 'fill' : 'regular'" />
      </span>
      <span class="node-label mono">{{ node.label }}</span>
    </div>
  </div>
</template>

<style scoped>
.graph {
  position: relative;
  width: 100%;
  aspect-ratio: 1200 / 460;
  /* blueprint grid + wires fade toward the edges */
  -webkit-mask-image: radial-gradient(120% 110% at 60% 40%, #000 55%, transparent 100%);
  mask-image: radial-gradient(120% 110% at 60% 40%, #000 55%, transparent 100%);
}
.wires { position: absolute; inset: 0; width: 100%; height: 100%; overflow: visible; }
/* schematic blueprint grid, drawn inside the diagram itself */
.grid-line { fill: none; stroke: var(--line-soft); stroke-width: 1; }
.grid-rect { color: var(--line-soft); }
.wire { fill: none; stroke: var(--line-strong); stroke-width: 1.5; }
.wire-flow {
  fill: none; stroke: var(--accent); stroke-width: 2;
  stroke-dasharray: 14 320; stroke-linecap: round;
  animation: flow 5.5s linear infinite;
}
@keyframes flow { to { stroke-dashoffset: -334; } }
.packet { fill: var(--accent); filter: drop-shadow(0 0 5px rgba(39, 67, 240, 0.5)); }

.node { position: absolute; transform: translate(-50%, -50%); display: flex; flex-direction: column; align-items: center; gap: 8px; animation: pop 0.5s var(--ease) both; animation-delay: var(--d); }
.tile {
  position: relative; display: grid; place-items: center;
  width: 60px; height: 60px; border-radius: 14px;
  background: var(--surface); border: 1px solid var(--line);
  box-shadow: var(--shadow-2); color: var(--ink);
}
.node.acc .tile { border-color: var(--accent-line); color: var(--accent); box-shadow: var(--shadow-2), 0 0 0 4px var(--accent-wash); }
.tile-n { position: absolute; top: 6px; right: 8px; font-size: 9px; color: var(--ink-faint); }
.node-label { font-size: 11px; letter-spacing: 0.06em; color: var(--ink-mute); }
.node.acc .node-label { color: var(--accent); }

@keyframes pop { from { opacity: 0; transform: translate(-50%, -50%) scale(0.82); } to { opacity: 1; transform: translate(-50%, -50%) scale(1); } }
.still .node { animation: none; }

@media (prefers-reduced-motion: reduce) { .node { animation: none; } }
@media (max-width: 760px) { .graph { aspect-ratio: 1200 / 620; } .node-label { display: none; } .tile { width: 48px; height: 48px; } }
</style>
