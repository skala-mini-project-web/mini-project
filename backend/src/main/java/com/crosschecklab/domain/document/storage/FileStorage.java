package com.crosschecklab.domain.document.storage;

import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

// 업로드 파일의 저장 전략. Mock 프로파일은 체크섬·크기만 계산하고,
// real-extraction 프로파일은 원본 바이트를 불변 저장소에 보존한다.
public interface FileStorage {

    /**
     * @param fixtureKey Mock 구현에서만 storage_key 의 추출 시나리오 코드로 사용한다.
     *                   실제 저장 구현은 경로 구성에 이 값을 사용하지 않는다.
     */
    StoredFile store(MultipartFile file, String fixtureKey);

    // 저장된 바이트를 읽되 소비하거나 삭제하지 않는다. Mock 구현은 항상 비어 있다.
    Optional<byte[]> read(String storageKey);
}
