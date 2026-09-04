package com.crosschecklab.analysis.rag;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PgVectorEvidenceRetriever {

    public static final int TOP_K = 6;

    private final JdbcTemplate jdbcTemplate;
    private final OllamaEmbeddingClient embeddingClient;

    public PgVectorEvidenceRetriever(JdbcTemplate jdbcTemplate, OllamaEmbeddingClient embeddingClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
    }

    public List<RagRetrievedChunk> retrieve(String query, Collection<Long> selectedEvidenceDocumentIds) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("RAG retrieval query must not be blank");
        }
        List<Long> selectedIds = normalizeIds(selectedEvidenceDocumentIds);
        if (selectedIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(selectedIds.size(), "?"));
        String sql = """
                with current_sources as (
                    select distinct on (evidence_document_id)
                           evidence_document_id, source_hash
                    from evidence_document_chunks
                    where embedding_model = ? and chunking_version = ?
                    order by evidence_document_id, created_at desc, id desc
                ), candidates as (
                    select c.id as chunk_id,
                           c.evidence_document_id,
                           c.source_hash,
                           c.chunk_hash,
                           c.chunk_ordinal,
                           c.chunking_version,
                           c.embedding_model,
                           c.chunk_text,
                           c.embedding <=> ?::vector as distance
                    from evidence_document_chunks c
                    join current_sources current_source
                      on current_source.evidence_document_id = c.evidence_document_id
                     and current_source.source_hash = c.source_hash
                    join evidence_documents evidence on evidence.id = c.evidence_document_id
                    where evidence.active = true
                      and c.embedding_model = ?
                      and c.chunking_version = ?
                      and c.evidence_document_id in (%s)
                )
                select chunk_id,
                       evidence_document_id,
                       source_hash,
                       chunk_hash,
                       chunk_ordinal,
                       chunking_version,
                       embedding_model,
                       chunk_text,
                       row_number() over (
                           order by distance asc, evidence_document_id asc, chunk_ordinal asc, chunk_id asc
                       )::integer as rank,
                       1.0 - distance as similarity
                from candidates
                order by distance asc, evidence_document_id asc, chunk_ordinal asc, chunk_id asc
                limit %d
                """.formatted(placeholders, TOP_K);

        double[] queryEmbedding = embeddingClient.embed(query);
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>(selectedIds.size() + 5);
        parameters.add(embeddingClient.embeddingModel());
        parameters.add(EvidenceChunkIndexer.CHUNK_VERSION);
        parameters.add(EvidenceChunkIndexer.vectorLiteral(queryEmbedding));
        parameters.add(embeddingClient.embeddingModel());
        parameters.add(EvidenceChunkIndexer.CHUNK_VERSION);
        parameters.addAll(selectedIds);

        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new RagRetrievedChunk(
                resultSet.getLong("chunk_id"),
                resultSet.getLong("evidence_document_id"),
                resultSet.getString("source_hash"),
                resultSet.getString("chunk_hash"),
                resultSet.getInt("chunk_ordinal"),
                resultSet.getString("chunking_version"),
                resultSet.getString("embedding_model"),
                resultSet.getString("chunk_text"),
                resultSet.getInt("rank"),
                resultSet.getDouble("similarity")), parameters.toArray());
    }

    private static List<Long> normalizeIds(Collection<Long> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("Selected evidence document ids must not be null");
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Selected evidence document ids must not contain null");
        }
        return new LinkedHashSet<>(ids).stream().sorted(Comparator.naturalOrder()).toList();
    }
}
