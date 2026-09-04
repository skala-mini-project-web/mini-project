package com.crosschecklab.analysis.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosschecklab.analysis.application.AnalysisJobService;
import com.crosschecklab.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("PgVectorEvidenceRetriever")
class PgVectorEvidenceRetrieverTest extends IntegrationTestSupport {

    private static final String EMBEDDING_MODEL = "bge-m3:latest";
    private static final int EMBEDDING_DIMENSION = 1_024;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AnalysisJobService analysisJobService;

    @Test
    @Transactional
    @DisplayName("활성화되고 선택된 최신 청크만 cosine 유사도와 안정적인 tie-break 순으로 6개 반환한다")
    void returnsTopSixInDeterministicSimilarityOrderAndExcludesOtherEvidence() {
        long selectedA = insertEvidence("선택 근거 A의 전체 원문", true);
        long selectedB = insertEvidence("선택 근거 B의 전체 원문", true);
        long inactive = insertEvidence("비활성 근거의 전체 원문", false);
        long unselected = insertEvidence("선택하지 않은 근거의 전체 원문", true);

        insertChunk(selectedA, 0, 0.95, 1);
        insertChunk(selectedA, 1, 0.90, 2);
        insertChunk(selectedB, 0, 0.90, 3);
        insertChunk(selectedB, 1, 0.80, 4);
        insertChunk(selectedA, 2, 0.70, 5);
        insertChunk(selectedB, 2, 0.60, 6);
        insertChunk(selectedA, 3, 0.50, 7);
        insertChunk(inactive, 0, 0.999, 8);
        insertChunk(unselected, 0, 0.999, 9);

        OllamaEmbeddingClient client = queryEmbeddingClient();
        PgVectorEvidenceRetriever retriever = new PgVectorEvidenceRetriever(jdbcTemplate, client);
        List<RagRetrievedChunk> result = retriever.retrieve(
                "원금 손실 가능성과 중도해지 위험", List.of(selectedB, inactive, selectedA));

        assertThat(result).hasSize(PgVectorEvidenceRetriever.TOP_K);
        assertThat(result).extracting(RagRetrievedChunk::evidenceDocumentId)
                .containsExactly(selectedA, selectedA, selectedB, selectedB, selectedA, selectedB)
                .doesNotContain(inactive, unselected);
        assertThat(result).extracting(RagRetrievedChunk::chunkOrdinal)
                .containsExactly(0, 1, 0, 1, 2, 2);
        assertThat(result).extracting(RagRetrievedChunk::rank)
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(result).extracting(RagRetrievedChunk::similarity)
                .satisfiesExactly(
                        value -> assertThat(value).isCloseTo(0.95, offset(0.0001)),
                        value -> assertThat(value).isCloseTo(0.90, offset(0.0001)),
                        value -> assertThat(value).isCloseTo(0.90, offset(0.0001)),
                        value -> assertThat(value).isCloseTo(0.80, offset(0.0001)),
                        value -> assertThat(value).isCloseTo(0.70, offset(0.0001)),
                        value -> assertThat(value).isCloseTo(0.60, offset(0.0001)));
        assertThat(result).allSatisfy(chunk -> {
            assertThat(chunk.embeddingModel()).isEqualTo(EMBEDDING_MODEL);
            assertThat(chunk.chunkingVersion()).isEqualTo(EvidenceChunkIndexer.CHUNK_VERSION);
            assertThat(chunk.chunkText()).startsWith("indexed chunk ");
        });
        verify(client).embed("원금 손실 가능성과 중도해지 위험");
    }

    @Test
    @Transactional
    @DisplayName("선택 근거에 벡터 청크가 없으면 전체 원문이나 lexical 결과로 대체하지 않고 분석 준비를 실패시킨다")
    void noVectorResultFailsRatherThanFallingBackToFullDocument() {
        long selected = insertEvidence("이 전체 원문은 검색 결과로 직접 반환되면 안 된다", true);
        OllamaEmbeddingClient client = queryEmbeddingClient();
        PgVectorEvidenceRetriever retriever = new PgVectorEvidenceRetriever(jdbcTemplate, client);

        List<RagRetrievedChunk> result = retriever.retrieve("전체 원문", List.of(selected));

        assertThat(result).isEmpty();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                analysisJobService, "requireConsistentRetrieval", (Object) result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("검색된 청크가 없습니다");
        verify(client).embed("전체 원문");
    }

    private OllamaEmbeddingClient queryEmbeddingClient() {
        OllamaEmbeddingClient client = mock(OllamaEmbeddingClient.class);
        when(client.embeddingModel()).thenReturn(EMBEDDING_MODEL);
        when(client.embeddingDimension()).thenReturn(EMBEDDING_DIMENSION);
        when(client.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(vector(1.0));
        return client;
    }

    private long insertEvidence(String content, boolean active) {
        return jdbcTemplate.queryForObject("""
                insert into evidence_documents
                    (source_type, title, version, content, active, created_at, updated_at)
                values ('REGULATION', 'RAG retriever test', '1', ?, ?, now(), now())
                returning id
                """, Long.class, content, active);
    }

    private void insertChunk(long documentId, int ordinal, double similarity, int hashSeed) {
        String sourceHash = "%064x".formatted(documentId);
        String chunkHash = "%064x".formatted(hashSeed);
        jdbcTemplate.update("""
                insert into evidence_document_chunks (
                    evidence_document_id, source_hash, chunk_ordinal, chunking_version,
                    chunk_hash, chunk_text, embedding_model, embedding, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?::vector, now())
                """, documentId, sourceHash, ordinal, EvidenceChunkIndexer.CHUNK_VERSION,
                chunkHash, "indexed chunk " + hashSeed, EMBEDDING_MODEL,
                EvidenceChunkIndexer.vectorLiteral(vector(similarity)));
    }

    private static double[] vector(double cosineSimilarity) {
        double[] vector = new double[EMBEDDING_DIMENSION];
        vector[0] = cosineSimilarity;
        vector[1] = Math.sqrt(1.0 - cosineSimilarity * cosineSimilarity);
        return vector;
    }
}
