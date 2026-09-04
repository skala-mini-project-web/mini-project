// 업로드 전 클라이언트 파일 검증. 확장자/크기/빈 파일 + 매직바이트로 위조·손상 파일을 사전 차단.
// 서버(mock)의 UNSUPPORTED_FILE/FILE_TOO_LARGE 검증과 이중 방어.
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024 // 10MB (명세)
const EXT_OK = /\.(pdf|pptx)$/i

export async function validateUploadFile(file) {
  if (!file) return { ok: false, code: 'NO_FILE', message: '파일이 선택되지 않았습니다.' }
  if (!EXT_OK.test(file.name)) return { ok: false, code: 'UNSUPPORTED_FILE', message: 'PDF 또는 PPTX 파일만 업로드할 수 있습니다.' }
  if (file.size === 0) return { ok: false, code: 'EMPTY_FILE', message: '빈 파일은 업로드할 수 없습니다.' }
  if (file.size > MAX_UPLOAD_BYTES) {
    return { ok: false, code: 'FILE_TOO_LARGE', message: `파일이 너무 큽니다. 최대 ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)}MB까지 업로드할 수 있습니다.` }
  }
  // 매직바이트: PDF는 %PDF, PPTX(및 zip 계열)는 PK.. 로 시작
  let head
  try {
    head = new Uint8Array(await file.slice(0, 8).arrayBuffer())
  } catch {
    return { ok: false, code: 'READ_ERROR', message: '파일을 읽을 수 없습니다.' }
  }
  const isPdf = head[0] === 0x25 && head[1] === 0x50 && head[2] === 0x44 && head[3] === 0x46 // %PDF
  const isZip = head[0] === 0x50 && head[1] === 0x4b && [0x03, 0x05, 0x07].includes(head[2]) // PK..
  const expectPptx = /\.pptx$/i.test(file.name)
  if (expectPptx ? !isZip : !isPdf) {
    return { ok: false, code: 'INVALID_CONTENT', message: '파일 내용이 형식과 일치하지 않습니다. 손상되었거나 확장자만 바꾼 파일일 수 있습니다.' }
  }
  return { ok: true }
}
