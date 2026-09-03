package com.crosschecklab.global.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * classpath의 {@code fixtures/*.json}을 읽는 범용 로더.
 *
 * <p>트랙 A의 MockDocumentTextExtractor와 트랙 B의 FixtureRepository가 함께 사용한다.
 * 도메인 지식을 갖지 않으며 역직렬화 대상 타입은 호출 측이 지정한다.
 */
@Component
@RequiredArgsConstructor
public class JsonFixtureLoader {

    private static final String BASE_PATH = "fixtures/";

    private final ObjectMapper objectMapper;

    /**
     * @param fileName {@code fixtures/} 하위 파일명 (예: {@code document-extraction-scenarios.json})
     */
    public <T> T load(String fileName, Class<T> type) {
        try (InputStream in = open(fileName)) {
            return objectMapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Fixture 로딩 실패: " + fileName, e);
        }
    }

    /** 제네릭 컬렉션 등 {@link TypeReference}가 필요한 경우 사용한다. */
    public <T> T load(String fileName, TypeReference<T> typeReference) {
        try (InputStream in = open(fileName)) {
            return objectMapper.readValue(in, typeReference);
        } catch (IOException e) {
            throw new UncheckedIOException("Fixture 로딩 실패: " + fileName, e);
        }
    }

    private InputStream open(String fileName) throws IOException {
        ClassPathResource resource = new ClassPathResource(BASE_PATH + fileName);
        if (!resource.exists()) {
            throw new IOException("Fixture 파일이 없습니다: " + BASE_PATH + fileName);
        }
        return resource.getInputStream();
    }
}
