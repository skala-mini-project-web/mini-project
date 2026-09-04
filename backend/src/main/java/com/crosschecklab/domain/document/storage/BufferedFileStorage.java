package com.crosschecklab.domain.document.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// real-extraction 프로파일 전용. PDFBox/POI 가 실제 바이트를 필요로 하므로
// 비동기 추출이 끝날 때까지만 메모리에 들고 있다가 즉시 버린다 (디스크·DB 에는 남기지 않는다).
// 업로드 크기 상한이 10MB 이고 데모용 동시 업로드가 소수라는 전제 위에서만 성립하는 구현이다.
@Component
@Profile("real-extraction")
public class BufferedFileStorage implements FileStorage {

    public static final String STORAGE_KEY_PREFIX = "memory://documents/";

    private final Map<String, byte[]> buffers = new ConcurrentHashMap<>();

    @Override
    public StoredFile store(MultipartFile file, String fixtureKey) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림을 읽지 못했습니다: " + file.getOriginalFilename(), e);
        }

        // 같은 시나리오로 올린 파일끼리 덮어쓰지 않도록 키를 유일하게 만든다.
        String storageKey = STORAGE_KEY_PREFIX + fixtureKey + "/" + UUID.randomUUID();
        buffers.put(storageKey, content);

        return new StoredFile(storageKey, FileDigest.toHex(FileDigest.newDigest().digest(content)), content.length);
    }

    // 한 번 읽으면 버린다. 추출은 문서당 한 번만 수행되므로 재시도 시에는 재업로드가 필요하다.
    @Override
    public Optional<byte[]> read(String storageKey) {
        return Optional.ofNullable(buffers.remove(storageKey));
    }
}
