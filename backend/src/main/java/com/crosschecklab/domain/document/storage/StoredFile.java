package com.crosschecklab.domain.document.storage;

// 업로드 스트림을 소비한 결과. 바이너리는 담지 않는다.
public record StoredFile(String storageKey, String checksum, long size) {
}
