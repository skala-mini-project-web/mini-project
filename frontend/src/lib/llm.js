// 로컬 LLM(Ollama) 클라이언트. 브라우저에서는 Vite 프록시(/ollama), 노드에서는 직접 호출.
// 클라우드 키 불필요. 모델: 분석 qwen2.5:7b-instruct, 임베딩 bge-m3.
const BASE = typeof window !== 'undefined' ? '/ollama' : 'http://localhost:11434'

export const AI = { chatModel: 'qwen2.5:7b-instruct', embedModel: 'bge-m3', temperature: 0.1 }

export async function llmAvailable() {
  try {
    const r = await fetch(`${BASE}/api/tags`, { method: 'GET' })
    return r.ok
  } catch {
    return false
  }
}

export async function chatJson(messages, { model = AI.chatModel, temperature = AI.temperature } = {}) {
  const res = await fetch(`${BASE}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, stream: false, format: 'json', options: { temperature }, messages }),
  })
  if (!res.ok) throw new Error(`LLM ${res.status}`)
  const data = await res.json()
  const content = data?.message?.content || ''
  try {
    return JSON.parse(content)
  } catch {
    throw new Error('LLM 응답 JSON 파싱 실패')
  }
}

export async function embed(input, { model = AI.embedModel } = {}) {
  const res = await fetch(`${BASE}/api/embed`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, input }),
  })
  if (!res.ok) throw new Error(`embed ${res.status}`)
  const data = await res.json()
  return data.embeddings || []
}
