package com.crosschecklab.domain.document.storage;

import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

// 업로드 파일의 저장 전략.
// MVP 는 바이너리를 영구 저장하지 않으므로 store() 는 체크섬·크기만 계산하고 스트림을 버린다.
// 실제 파일 저장이 필요해지면 이 인터페이스에 S3/로컬 구현을 추가한다.
public interface FileStorage {

    /**
     * @param fixtureKey storage_key 의 마지막 경로 조각. Mock 구현에서는 추출 시나리오 코드가 된다.
     */
    StoredFile store(MultipartFile file, String fixtureKey);

    // 바이너리를 보관하는 구현만 값을 돌려준다. 기본(Mock) 구현은 항상 비어 있다.
    Optional<byte[]> read(String storageKey);
}
