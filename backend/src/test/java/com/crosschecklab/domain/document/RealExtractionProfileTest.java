package com.crosschecklab.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crosschecklab.domain.document.extraction.PdfBoxTextExtractor;
import com.crosschecklab.domain.document.extraction.PptxTextExtractor;
import com.crosschecklab.domain.document.extraction.RealDocumentTextExtractor;
import com.crosschecklab.domain.document.extraction.TextExtractionException;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import com.crosschecklab.domain.document.storage.DurableLocalFileStorage;
import com.crosschecklab.domain.document.storage.FileStorage;
import com.crosschecklab.domain.document.storage.StoredFile;
import com.crosschecklab.support.IntegrationTestSupport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

// real-extraction 프로파일은 평소 켜지지 않으므로 배선이 깨져도 다른 테스트가 잡아주지 못한다.
// 구현이 Mock 과 정확히 교체되는지와 원본 바이트가 재시도 후에도 보존되는지 확인한다.
@ActiveProfiles("real-extraction")
@DisplayName("real-extraction 프로파일 배선")
class RealExtractionProfileTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private TextExtractionService textExtractionService;

    @Autowired
    private DocumentExtractionRunner documentExtractionRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    private Path temporaryStorageRoot;

    @Test
    @DisplayName("Mock 구현 대신 실제 추출 구현이 유일한 빈으로 등록된다")
    void swapsMockImplementations() {
        assertThat(fileStorage).isInstanceOf(DurableLocalFileStorage.class);
        assertThat(textExtractionService).isInstanceOf(RealDocumentTextExtractor.class);

        assertThat(applicationContext.getBeansOfType(FileStorage.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(TextExtractionService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(PdfBoxTextExtractor.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(PptxTextExtractor.class)).hasSize(1);
    }

    @Test
    @DisplayName("원본을 내용 기반 안전한 키로 저장하고 같은 바이트를 반복해서 읽는다")
    void preservesOriginalBytesAcrossReads() throws Exception {
        byte[] original = "synthetic durable evidence".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(original));
        DurableLocalFileStorage storage = new DurableLocalFileStorage(temporaryStorageRoot.toString());
        MockMultipartFile upload = new MockMultipartFile(
                "file", "../../사용자-파일.pdf", "application/pdf", original);

        StoredFile stored = storage.store(upload, "../../fixture-key");

        assertThat(stored.storageKey())
                .isEqualTo(DurableLocalFileStorage.STORAGE_KEY_PREFIX + checksum)
                .doesNotContain("fixture-key", "사용자-파일.pdf", "..");
        assertThat(stored.checksum()).isEqualTo(checksum);
        assertThat(stored.size()).isEqualTo(original.length);
        assertThat(storage.read(stored.storageKey()))
                .hasValueSatisfying(content -> assertThat(content).containsExactly(original));
        assertThat(storage.read(stored.storageKey()))
                .hasValueSatisfying(content -> assertThat(content).containsExactly(original));
    }

    @Test
    @DisplayName("실제 born-digital PDF 추출은 현재 PDFBOX run과 page를 저장하고 확정을 해제한다")
    void persistsCurrentPdfBoxRunAndPageBeforeReady() throws Exception {
        byte[] pdf = bornDigitalPdf();
        MockMultipartFile upload =
                new MockMultipartFile("file", "born-digital.pdf", "application/pdf", pdf);
        StoredFile stored = fileStorage.store(upload, "born-digital-provenance");
        String requestToken = UUID.randomUUID().toString();
        Long productId = jdbcTemplate.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (1, 'OCR provenance regression', 'INVESTMENT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
        Long documentId = jdbcTemplate.queryForObject("""
                INSERT INTO product_documents (
                    product_id, file_name, media_type, file_size, checksum, storage_key,
                    extract_status, extracted_text, extracted_text_hash,
                    extraction_token, extraction_lease_until,
                    confirmed, confirmed_by, confirmed_at, confirmed_text_hash,
                    created_at, updated_at
                ) VALUES (
                    ?, 'born-digital.pdf', 'application/pdf', ?, ?, ?,
                    'UPLOADED', 'previous confirmed text', ?,
                    ?, clock_timestamp() + interval '5 minutes',
                    TRUE, 1, CURRENT_TIMESTAMP, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """,
                Long.class,
                productId,
                stored.size(),
                stored.checksum(),
                stored.storageKey(),
                stored.checksum(),
                requestToken,
                stored.checksum());

        documentExtractionRunner.run(documentId, requestToken);

        Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                SELECT d.extract_status,
                       d.current_extraction_run_id,
                       d.confirmed,
                       d.confirmed_by,
                       d.confirmed_at,
                       d.confirmed_extraction_run_id,
                       d.confirmed_text_hash,
                       r.state AS run_state,
                       r.page_count,
                       p.page_number,
                       p.selected_method
                FROM product_documents d
                JOIN document_extraction_runs r
                  ON r.id = d.current_extraction_run_id
                JOIN document_extraction_pages p
                  ON p.extraction_run_id = r.id
                WHERE d.id = ?
                """, documentId);
        assertThat(persisted)
                .containsEntry("extract_status", "READY")
                .containsEntry("confirmed", false)
                .containsEntry("run_state", "SUCCEEDED")
                .containsEntry("page_count", 1)
                .containsEntry("page_number", 1)
                .containsEntry("selected_method", "PDFBOX_TEXT");
        assertThat(persisted.get("current_extraction_run_id")).isNotNull();
        assertThat(persisted.get("confirmed_by")).isNull();
        assertThat(persisted.get("confirmed_at")).isNull();
        assertThat(persisted.get("confirmed_extraction_run_id")).isNull();
        assertThat(persisted.get("confirmed_text_hash")).isNull();
    }

    @Test
    @DisplayName("OCR이 필요한 거대 PDF 페이지는 이미지 할당 전에 거부한다")
    void rejectsHugeOcrPageBeforeRendering() throws Exception {
        PdfBoxTextExtractor extractor =
                applicationContext.getBean(PdfBoxTextExtractor.class);

        assertThatThrownBy(() -> extractor.extractResult(hugeBlankPdf()))
                .isInstanceOf(TextExtractionException.class)
                .hasMessageContaining("안전하게 렌더할 수 없습니다")
                .hasMessageContaining("최대 렌더 픽셀 수");
    }

    private static byte[] hugeBlankPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(
                    10_000, 10_000)));
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] bornDigitalPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(
                        "Born digital extraction provenance regression page with readable text.");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
