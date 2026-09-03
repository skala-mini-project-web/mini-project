package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.storage.MockFileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 기본 추출기. 업로드 시 정해진 시나리오 코드가 storage_key 에 박혀 있으므로 그것으로 fixture 텍스트를 찾는다.
// 같은 시나리오는 언제나 같은 텍스트를 돌려준다 (트랙 B 의 분석 fixture 가 이 결정성에 의존한다).
@Slf4j
@Component
@Profile("!real-extraction")
@RequiredArgsConstructor
public class MockDocumentTextExtractor implements TextExtractionService {

    private final ExtractionScenarioResolver scenarioResolver;
    private final MockExtractionContext extractionContext;

    @Override
    public String extract(ExtractionTarget target) {
        ExtractionScenario scenario = scenarioResolver.require(scenarioCodeOf(target.storageKey()));
        int attempt = extractionContext.nextAttempt(target.documentId());

        // 재추출 데모용. 1차 시도만 실패시키고 이후 시도는 정상 텍스트를 돌려준다.
        if (scenario.failFirstAttempt() && attempt == 1) {
            log.info("문서 {} 시나리오 {} 1차 시도를 의도적으로 실패시킵니다.", target.documentId(), scenario.code());
            throw new TextExtractionException("추출 시간이 초과되었습니다 (시나리오: " + scenario.code() + ")");
        }

        return scenario.extractedText();
    }

    // storage_key 형식: mock://documents/{시나리오 코드}
    private String scenarioCodeOf(String storageKey) {
        if (storageKey == null || !storageKey.startsWith(MockFileStorage.STORAGE_KEY_PREFIX)) {
            throw new TextExtractionException("Mock 추출기가 해석할 수 없는 storage_key 입니다: " + storageKey);
        }
        return storageKey.substring(MockFileStorage.STORAGE_KEY_PREFIX.length());
    }
}
