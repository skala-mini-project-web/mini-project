package com.crosschecklab.domain.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosschecklab.domain.document.extraction.PdfBoxTextExtractor;
import com.crosschecklab.domain.document.extraction.PptxTextExtractor;
import com.crosschecklab.domain.document.extraction.RealDocumentTextExtractor;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import com.crosschecklab.domain.document.storage.BufferedFileStorage;
import com.crosschecklab.domain.document.storage.FileStorage;
import com.crosschecklab.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

// real-extraction 프로파일은 평소 켜지지 않으므로 배선이 깨져도 다른 테스트가 잡아주지 못한다.
// 구현이 Mock 과 정확히 교체되는지(중복 빈이 생기지 않는지)만 확인한다.
@ActiveProfiles("real-extraction")
@DisplayName("real-extraction 프로파일 배선")
class RealExtractionProfileTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private TextExtractionService textExtractionService;

    @Test
    @DisplayName("Mock 구현 대신 실제 추출 구현이 유일한 빈으로 등록된다")
    void swapsMockImplementations() {
        assertThat(fileStorage).isInstanceOf(BufferedFileStorage.class);
        assertThat(textExtractionService).isInstanceOf(RealDocumentTextExtractor.class);

        assertThat(applicationContext.getBeansOfType(FileStorage.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(TextExtractionService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(PdfBoxTextExtractor.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(PptxTextExtractor.class)).hasSize(1);
    }
}
