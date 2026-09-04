// =============================================================================
// Real client-side document extraction (runs in the browser, no backend).
//   - PDF text      -> pdf.js getTextContent
//   - PPTX text     -> JSZip + slide XML <a:t> parsing
//   - image/scan PDF-> tesseract.js OCR (kor+eng), page rendered to canvas
// All libraries are lazy-imported so they never touch the initial bundle.
// OCR fetches Korean language data on first use (needs internet).
// =============================================================================

const MIN_TEXT = 12 // below this many non-space chars, treat a PDF as image/scan

export async function extractDocument(file, { onProgress } = {}) {
  const name = (file?.name || '').toLowerCase()
  const type = file?.type || ''
  if (name.endsWith('.pptx') || type.includes('presentationml')) return extractPptx(file)
  if (name.endsWith('.pdf') || type.includes('pdf')) return extractPdf(file, { onProgress })
  throw new Error('지원하지 않는 형식입니다. PDF 또는 PPTX만 가능합니다.')
}

async function loadPdfjs() {
  const pdfjs = await import('pdfjs-dist')
  const workerUrl = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default
  pdfjs.GlobalWorkerOptions.workerSrc = workerUrl
  return pdfjs
}

async function extractPdf(file, { onProgress } = {}) {
  const pdfjs = await loadPdfjs()
  const data = new Uint8Array(await file.arrayBuffer())
  const pdf = await pdfjs.getDocument({ data }).promise
  const pageTexts = []
  for (let i = 1; i <= pdf.numPages; i++) {
    const page = await pdf.getPage(i)
    const tc = await page.getTextContent()
    pageTexts.push(tc.items.map((it) => it.str).join(' ').trim())
    onProgress?.({ phase: 'text', page: i, pages: pdf.numPages })
  }
  const text = pageTexts.join('\n\n').trim()
  const dense = text.replace(/\s/g, '').length
  if (dense >= MIN_TEXT) return { text, method: 'pdf-text', pages: pdf.numPages }

  // No usable text layer -> OCR each page.
  const ocr = await ocrPdf(pdf, { onProgress })
  return { text: ocr, method: 'ocr', pages: pdf.numPages }
}

async function ocrPdf(pdf, { onProgress } = {}) {
  const { default: Tesseract } = await import('tesseract.js')
  const worker = await Tesseract.createWorker('kor+eng')
  try {
    const out = []
    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i)
      const viewport = page.getViewport({ scale: 2 })
      const canvas = document.createElement('canvas')
      canvas.width = Math.ceil(viewport.width)
      canvas.height = Math.ceil(viewport.height)
      const ctx = canvas.getContext('2d')
      await page.render({ canvasContext: ctx, viewport }).promise
      onProgress?.({ phase: 'ocr', page: i, pages: pdf.numPages })
      const { data } = await worker.recognize(canvas)
      out.push((data.text || '').trim())
      canvas.width = canvas.height = 0
    }
    return out.join('\n\n').trim()
  } finally {
    await worker.terminate()
  }
}

async function extractPptx(file) {
  const { default: JSZip } = await import('jszip')
  const zip = await JSZip.loadAsync(await file.arrayBuffer())
  const slideNames = Object.keys(zip.files)
    .filter((n) => /^ppt\/slides\/slide\d+\.xml$/.test(n))
    .sort((a, b) => (parseInt(a.match(/\d+/)[0], 10) - parseInt(b.match(/\d+/)[0], 10)))
  const slides = []
  for (const n of slideNames) {
    const xml = await zip.files[n].async('string')
    const runs = [...xml.matchAll(/<a:t>([\s\S]*?)<\/a:t>/g)].map((m) => decodeXml(m[1]))
    const text = runs.join(' ').replace(/\s+/g, ' ').trim()
    if (text) slides.push(text)
  }
  return { text: slides.join('\n\n').trim(), method: 'pptx', pages: slideNames.length }
}

function decodeXml(s) {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
}
