import { analyzeDocument } from '../src/lib/analyze.js'
const sourceText = '본 상품은 최근 3년 연속 안정적인 수익률을 기록한 투자상품입니다. 연 5.8% 수준의 수익을 기대할 수 있으며, 운용 보수와 판매 수수료가 부과됩니다. 원금 손실 가능성이 있습니다.'
const personaCodes = ['FINANCIAL_BEGINNER','SENIOR','LOW_LITERACY']
const ruleCodes = ['STABILITY_KEYWORD','RETURN_FRAMING','COST_OMISSION','LOSS_SOFTENING','FORMAL_CONFIRMATION','COGNITIVE_ACCESSIBILITY']
const t=Date.now()
const r = await analyzeDocument({ sourceText, personaCodes, ruleCodes })
console.log('provider:', r.provider)
console.log('grounding:', r.grounding.map(g=>`${g.documentId}:${g.score}`).join('  '))
console.log('findings:', r.findings.length, '| score:', r.riskScore, '| dropped:', r.violations.length)
for(const f of r.findings) console.log(` - [${f.severity}] ${f.ruleCode} :: "${f.sourceReference.excerpt}"  →  ${f.recommendation.slice(0,50)}`)
if(r.violations.length) console.log('violations:', r.violations.slice(0,6))
console.log('elapsed:', ((Date.now()-t)/1000).toFixed(1)+'s')
