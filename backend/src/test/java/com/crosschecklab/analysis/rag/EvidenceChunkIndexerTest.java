package com.crosschecklab.analysis.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosschecklab.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("EvidenceChunkIndexer")
class EvidenceChunkIndexerTest extends IntegrationTestSupport {

    private static final String EMBEDDING_MODEL = "bge-m3:latest";
    private static final int EMBEDDING_DIMENSION = 1_024;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("한글과 보조 평면 문자를 코드 포인트 기준으로 결정적으로 자르고 200자를 겹친다")
    void chunksKoreanAndSupplementaryCharactersDeterministically() {
        EvidenceChunkIndexer indexer = new EvidenceChunkIndexer(jdbcTemplate, embeddingClient());
        String content = "한😀".repeat(700);

        List<EvidenceChunkIndexer.ChunkDraft> first = indexer.chunk(content);
        List<EvidenceChunkIndexer.ChunkDraft> second = indexer.chunk(content);

        assertThat(first).isEqualTo(second).hasSize(2);
        assertThat(first).extracting(EvidenceChunkIndexer.ChunkDraft::chunkOrdinal)
                .containsExactly(0, 1);
        assertThat(first.get(0).chunkText().codePointCount(0, first.get(0).chunkText().length()))
                .isEqualTo(EvidenceChunkIndexer.MAX_CHUNK_CODE_POINTS);
        assertThat(first.get(1).chunkText().codePointCount(0, first.get(1).chunkText().length()))
                .isEqualTo(400);

        String overlap = suffixByCodePoints(first.get(0).chunkText(), EvidenceChunkIndexer.OVERLAP_CODE_POINTS);
        assertThat(prefixByCodePoints(first.get(1).chunkText(), EvidenceChunkIndexer.OVERLAP_CODE_POINTS))
                .isEqualTo(overlap)
                .contains("😀");
        assertThat(first).allSatisfy(chunk -> assertThat(chunk.chunkHash())
                .isEqualTo(sha256(chunk.chunkText()))
                .matches("[0-9a-f]{64}"));
    }

    @Test
    @Transactional
    @DisplayName("동일한 원문은 임베딩하지 않고 기존 청크를 그대로 사용한다")
    void doesNotReembedAnUnchangedSource() {
        long documentId = insertEvidence("동일한 근거 원문", true);
        OllamaEmbeddingClient client = embeddingClient();
        EvidenceChunkIndexer indexer = new EvidenceChunkIndexer(jdbcTemplate, client);

        EvidenceChunkIndexer.IndexingResult initial = indexer.indexSelected(List.of(documentId));
        EvidenceChunkIndexer.IndexingResult unchanged = indexer.indexSelected(List.of(documentId));

        assertThat(initial).isEqualTo(new EvidenceChunkIndexer.IndexingResult(1, 0, 1));
        assertThat(unchanged).isEqualTo(new EvidenceChunkIndexer.IndexingResult(0, 1, 0));
        verify(client, times(1)).embedAll(anyList());
        assertThat(chunkCount(documentId)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("원문이 바뀌면 새 source hash로 다시 인덱싱한다")
    void reindexesAChangedSource() {
        long documentId = insertEvidence("변경 전 근거 원문", true);
        OllamaEmbeddingClient client = embeddingClient();
        EvidenceChunkIndexer indexer = new EvidenceChunkIndexer(jdbcTemplate, client);
        indexer.indexSelected(List.of(documentId));

        jdbcTemplate.update("update evidence_documents set content = ?, updated_at = now() where id = ?",
                "변경 후 근거 원문", documentId);
        EvidenceChunkIndexer.IndexingResult changed = indexer.indexSelected(List.of(documentId));

        assertThat(changed).isEqualTo(new EvidenceChunkIndexer.IndexingResult(1, 0, 1));
        verify(client, times(2)).embedAll(anyList());
        assertThat(jdbcTemplate.queryForList("""
                        select distinct source_hash
                        from evidence_document_chunks
                        where evidence_document_id = ?
                        """, String.class, documentId))
                .hasSize(2);
    }

    @Test
    @Transactional
    @DisplayName("선택 목록에 있어도 비활성 근거는 인덱싱하지 않는다")
    void excludesInactiveEvidenceFromIndexing() {
        long activeId = insertEvidence("활성 근거", true);
        long inactiveId = insertEvidence("비활성 근거", false);
        OllamaEmbeddingClient client = embeddingClient();
        EvidenceChunkIndexer indexer = new EvidenceChunkIndexer(jdbcTemplate, client);

        EvidenceChunkIndexer.IndexingResult result = indexer.indexSelected(List.of(inactiveId, activeId));

        assertThat(result).isEqualTo(new EvidenceChunkIndexer.IndexingResult(1, 0, 1));
        verify(client).embedAll(List.of("활성 근거"));
        assertThat(chunkCount(activeId)).isEqualTo(1);
        assertThat(chunkCount(inactiveId)).isZero();
    }

    private OllamaEmbeddingClient embeddingClient() {
        OllamaEmbeddingClient client = mock(OllamaEmbeddingClient.class);
        when(client.embeddingModel()).thenReturn(EMBEDDING_MODEL);
        when(client.embeddingDimension()).thenReturn(EMBEDDING_DIMENSION);
        when(client.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(ignored -> unitVector()).toList();
        });
        return client;
    }

    private long insertEvidence(String content, boolean active) {
        return jdbcTemplate.queryForObject("""
                insert into evidence_documents
                    (source_type, title, version, content, active, created_at, updated_at)
                values ('REGULATION', 'RAG indexer test', '1', ?, ?, now(), now())
                returning id
                """, Long.class, content, active);
    }

    private int chunkCount(long documentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from evidence_document_chunks where evidence_document_id = ?",
                Integer.class, documentId);
    }

    private static double[] unitVector() {
        double[] vector = new double[EMBEDDING_DIMENSION];
        vector[0] = 1.0;
        return vector;
    }

    private static String prefixByCodePoints(String value, int count) {
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    private static String suffixByCodePoints(String value, int count) {
        int start = value.offsetByCodePoints(value.length(), -count);
        return value.substring(start);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
