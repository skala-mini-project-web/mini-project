// RAG retrieval. 근거(법령·내부준칙) 코퍼스에서 문서와 관련된 발췌 top-k를 뽑아 분석 근거로 주입.
// - retrieveEvidenceEmbed: bge-m3 임베딩 기반(실제 RAG, Ollama 필요)
// - retrieveEvidence: 키워드 기반 오프라인 폴백(임베딩/네트워크 불필요)
import { embed } from './llm.js'

const STOP = new Set(['그리고', '또는', '있습니다', '됩니다', '합니다', '수', '및', '이', '그', '저', '등', '더', '때', '의', '를', '을', '은', '는', '가'])

function tokenize(s) {
  return (s || '').toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, ' ').split(/\s+/).filter((t) => t.length >= 2 && !STOP.has(t))
}

function bestSentence(text, queryText) {
  const q = new Set(tokenize(queryText))
  const sents = text.split(/(?<=[.。])\s*/).map((s) => s.trim()).filter(Boolean)
  let best = sents[0] || text
  let bestOverlap = -1
  for (const s of sents) {
    const ov = tokenize(s).filter((t) => q.has(t)).length
    if (ov > bestOverlap) { bestOverlap = ov; best = s }
  }
  return best
}

function cosine(a, b) {
  let dot = 0, na = 0, nb = 0
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
  return dot / (Math.sqrt(na) * Math.sqrt(nb) || 1)
}

// 승인된 근거 코퍼스. 실제 RAG에서는 EvidenceDocument 벡터 색인으로 대체.
export const EVIDENCE_CORPUS = [
  { documentId: 'POLICY-003', sourceType: 'INTERNAL_POLICY', title: '금융상품 중요정보 표시 내부준칙', text: '원금 손실 가능성은 안정성 표현과 인접하여 표시해야 한다. 총비용과 수수료는 수익 표현과 함께 제시한다. 예금자보호 여부와 한도를 명확히 안내한다.' },
  { documentId: 'LAW-014', sourceType: 'LAW', title: '금융소비자보호 표시광고 준칙(발췌)', text: '과거 수익률은 미래 성과를 보장하지 않는다는 사실을 함께 표시해야 한다. 최저금리 등 유리한 조건만을 강조하지 않는다.' },
  { documentId: 'POLICY-007', sourceType: 'INTERNAL_POLICY', title: '취약 금융소비자 보호 준칙', text: '전문용어에는 쉬운 설명을 병기한다. 고령 및 디지털 취약 소비자를 위해 인지 부담을 낮춘다.' },
]

export function retrieveEvidence(queryText, k = 3, corpus = EVIDENCE_CORPUS) {
  const q = new Set(tokenize(queryText))
  if (!q.size) return []
  return corpus
    .map((d) => {
      const toks = tokenize(d.text)
      const seen = new Set()
      let hit = 0
      for (const t of toks) if (q.has(t) && !seen.has(t)) { hit += 1; seen.add(t) }
      return { documentId: d.documentId, sourceType: d.sourceType, title: d.title, excerpt: bestSentence(d.text, queryText), score: Number((hit / Math.sqrt(toks.length || 1)).toFixed(4)) }
    })
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, k)
}

let _corpusVectors = null
export async function retrieveEvidenceEmbed(queryText, k = 3, corpus = EVIDENCE_CORPUS) {
  if (!_corpusVectors || _corpusVectors.length !== corpus.length) {
    _corpusVectors = await embed(corpus.map((d) => d.text))
  }
  const [qv] = await embed([queryText])
  return corpus
    .map((d, i) => ({ documentId: d.documentId, sourceType: d.sourceType, title: d.title, excerpt: bestSentence(d.text, queryText), score: Number(cosine(qv, _corpusVectors[i]).toFixed(4)) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, k)
}
