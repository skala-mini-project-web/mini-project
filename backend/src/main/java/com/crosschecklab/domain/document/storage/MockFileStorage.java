package com.crosschecklab.domain.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// 기본 저장 전략. 스트림을 끝까지 흘려 SHA-256 과 크기만 얻고 바이트는 버린다.
// 파일 내용을 어디에도 남기지 않으므로 데모 환경에서 개인정보가 축적되지 않는다.
@Component
@Profile("!real-extraction")
public class MockFileStorage implements FileStorage {

    public static final String STORAGE_KEY_PREFIX = "mock://documents/";

    private static final int BUFFER_SIZE = 8192;

    @Override
    public StoredFile store(MultipartFile file, String fixtureKey) {
        MessageDigest digest = FileDigest.newDigest();
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림을 읽지 못했습니다: " + file.getOriginalFilename(), e);
        }

        return new StoredFile(STORAGE_KEY_PREFIX + fixtureKey, FileDigest.toHex(digest.digest()), size);
    }

    // 바이너리를 보관하지 않으므로 읽을 것이 없다.
    @Override
    public Optional<byte[]> read(String storageKey) {
        return Optional.empty();
    }
}
