// Korean initial-consonant (초성) search support.
const CHO = ['ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ']
const CHO_SET = new Set(CHO)

// Map every Hangul syllable to its leading consonant; leave other chars as-is.
export function toChoseong(str) {
  let out = ''
  for (const ch of str || '') {
    const c = ch.charCodeAt(0)
    if (c >= 0xac00 && c <= 0xd7a3) out += CHO[Math.floor((c - 0xac00) / 588)]
    else out += ch
  }
  return out
}

// True when the query is composed only of leading-consonant jamo (초성 검색 의도).
export function isChoseongOnly(q) {
  const s = (q || '').replace(/\s/g, '')
  return s.length > 0 && [...s].every((ch) => CHO_SET.has(ch))
}

// Match a text by plain substring OR, when the query is all 초성, by the
// text's 초성 string ("ㅅㅁㅌ" matches "스마트 인컴").
export function matchesQuery(text, query) {
  const t = (text || '').toLowerCase()
  const q = (query || '').trim().toLowerCase()
  if (!q) return true
  if (t.includes(q)) return true
  if (isChoseongOnly(q)) {
    return toChoseong(text || '').replace(/\s/g, '').includes(q.replace(/\s/g, ''))
  }
  return false
}
