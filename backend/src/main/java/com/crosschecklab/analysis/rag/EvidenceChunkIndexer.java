package com.crosschecklab.analysis.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class EvidenceChunkIndexer {

    public static final int MAX_CHUNK_CODE_POINTS = 1_200;
    public static final int OVERLAP_CODE_POINTS = 200;
    public static final String CHUNK_VERSION = "korean-boundary-v1-1200-200";

    private final JdbcTemplate jdbcTemplate;
    private final OllamaEmbeddingClient embeddingClient;

    public EvidenceChunkIndexer(JdbcTemplate jdbcTemplate, OllamaEmbeddingClient embeddingClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
    }

    @Transactional
    public IndexingResult indexSelected(Collection<Long> selectedEvidenceDocumentIds) {
        List<Long> selectedIds = normalizeIds(selectedEvidenceDocumentIds);
        if (selectedIds.isEmpty()) {
            return new IndexingResult(0, 0, 0);
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(selectedIds.size(), "?"));
        List<EvidenceSource> sources = jdbcTemplate.query("""
                        select id, content
                        from evidence_documents
                        where active = true and id in (%s)
                        order by id asc
                        for update
                        """.formatted(placeholders),
                (resultSet, rowNumber) -> new EvidenceSource(
                        resultSet.getLong("id"), resultSet.getString("content")),
                selectedIds.toArray());

        int indexedDocuments = 0;
        int unchangedDocuments = 0;
        int indexedChunks = 0;
        for (EvidenceSource source : sources) {
            List<ChunkDraft> chunks = chunk(source.content());
            String sourceHash = sha256(normalizeContent(source.content()));
            Optional<String> latestSource = latestIndexedSource(source.id());
            List<String> desiredHashes = chunks.stream().map(ChunkDraft::chunkHash).toList();

            if (latestSource.filter(sourceHash::equals).isPresent()) {
                requireCompleteChunkSet(source.id(), sourceHash, desiredHashes);
                unchangedDocuments++;
                continue;
            }

            List<String> historicalHashes = existingHashes(source.id(), sourceHash);
            if (historicalHashes.equals(desiredHashes)) {
                jdbcTemplate.update("""
                                update evidence_document_chunks
                                set created_at = current_timestamp
                                where evidence_document_id = ? and source_hash = ?
                                  and chunking_version = ? and embedding_model = ?
                                """,
                        source.id(), sourceHash, CHUNK_VERSION, embeddingClient.embeddingModel());
            } else {
                if (!historicalHashes.isEmpty()) {
                    throw new IllegalStateException("Indexed evidence chunk set is incomplete for document "
                            + source.id());
                }
                List<double[]> embeddings = embeddingClient.embedAll(
                        chunks.stream().map(ChunkDraft::chunkText).toList());
                insertChunks(source.id(), sourceHash, chunks, embeddings);
                indexedChunks += chunks.size();
            }
            indexedDocuments++;
        }
        return new IndexingResult(indexedDocuments, unchangedDocuments, indexedChunks);
    }

    public List<ChunkDraft> chunk(String content) {
        String normalized = normalizeContent(content);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("Evidence document content must not be blank");
        }
        int[] codePoints = normalized.codePoints().toArray();
        List<ChunkDraft> chunks = new ArrayList<>();
        int start = 0;
        while (start < codePoints.length) {
            int maximumEnd = Math.min(start + MAX_CHUNK_CODE_POINTS, codePoints.length);
            int end = maximumEnd == codePoints.length
                    ? maximumEnd
                    : preferredEnd(codePoints, start, maximumEnd);
            if (end <= start) {
                end = maximumEnd;
            }
            String chunkText = new String(codePoints, start, end - start).strip();
            if (!chunkText.isEmpty()) {
                chunks.add(new ChunkDraft(chunks.size(), chunkText, sha256(chunkText)));
            }
            if (end == codePoints.length) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP_CODE_POINTS);
        }
        return List.copyOf(chunks);
    }

    private Optional<String> latestIndexedSource(long evidenceDocumentId) {
        List<String> values = jdbcTemplate.query("""
                        select source_hash
                        from evidence_document_chunks
                        where evidence_document_id = ? and chunking_version = ? and embedding_model = ?
                        order by created_at desc, id desc
                        limit 1
                        """,
                (resultSet, rowNumber) -> resultSet.getString(1), evidenceDocumentId, CHUNK_VERSION,
                embeddingClient.embeddingModel());
        return values.stream().findFirst();
    }

    private void requireCompleteChunkSet(long evidenceDocumentId, String sourceHash,
                                         List<String> desiredHashes) {
        if (!existingHashes(evidenceDocumentId, sourceHash).equals(desiredHashes)) {
            throw new IllegalStateException("Indexed evidence chunk set is incomplete for document "
                    + evidenceDocumentId);
        }
    }

    private List<String> existingHashes(long evidenceDocumentId, String sourceHash) {
        return jdbcTemplate.query("""
                        select chunk_hash
                        from evidence_document_chunks
                        where evidence_document_id = ? and source_hash = ?
                          and chunking_version = ? and embedding_model = ?
                        order by chunk_ordinal asc
                        """,
                (resultSet, rowNumber) -> resultSet.getString(1), evidenceDocumentId, sourceHash,
                CHUNK_VERSION, embeddingClient.embeddingModel());
    }

    private void insertChunks(long evidenceDocumentId, String sourceHash,
                              List<ChunkDraft> chunks, List<double[]> embeddings) {
        jdbcTemplate.batchUpdate("""
                        insert into evidence_document_chunks (
                            evidence_document_id, source_hash, chunk_ordinal, chunking_version,
                            chunk_hash, chunk_text, embedding_model, embedding
                        ) values (?, ?, ?, ?, ?, ?, ?, ?::vector)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        ChunkDraft chunk = chunks.get(index);
                        statement.setLong(1, evidenceDocumentId);
                        statement.setString(2, sourceHash);
                        statement.setInt(3, chunk.chunkOrdinal());
                        statement.setString(4, CHUNK_VERSION);
                        statement.setString(5, chunk.chunkHash());
                        statement.setString(6, chunk.chunkText());
                        statement.setString(7, embeddingClient.embeddingModel());
                        statement.setString(8, vectorLiteral(embeddings.get(index)));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
    }

    static String vectorLiteral(double[] embedding) {
        StringBuilder literal = new StringBuilder(embedding.length * 12).append('[');
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            double value = embedding[index];
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains a non-finite value");
            }
            literal.append(Double.toString(value));
        }
        return literal.append(']').toString();
    }

    private static int preferredEnd(int[] codePoints, int start, int maximumEnd) {
        int paragraph = -1;
        int sentence = -1;
        int line = -1;
        for (int position = start + 1; position <= maximumEnd; position++) {
            int previous = codePoints[position - 1];
            if (previous == '\n') {
                line = position;
                if (position >= 2 && codePoints[position - 2] == '\n') {
                    paragraph = position;
                }
            }
            if (isSentenceTerminator(previous)
                    && (position == codePoints.length || Character.isWhitespace(codePoints[position]))) {
                sentence = position;
            }
        }
        int minimumUsefulEnd = start + OVERLAP_CODE_POINTS + 1;
        int semanticBoundary = Math.max(paragraph, sentence);
        if (semanticBoundary >= minimumUsefulEnd) {
            return semanticBoundary;
        }
        return line >= minimumUsefulEnd ? line : maximumEnd;
    }

    private static boolean isSentenceTerminator(int codePoint) {
        return codePoint == '.' || codePoint == '!' || codePoint == '?'
                || codePoint == '。' || codePoint == '！' || codePoint == '？';
    }

    private static String normalizeContent(String content) {
        return Objects.requireNonNull(content, "Evidence document content must not be null")
                .replace("\r\n", "\n").replace('\r', '\n').strip();
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record EvidenceSource(long id, String content) {
    }

    public record ChunkDraft(int chunkOrdinal, String chunkText, String chunkHash) {
    }

    public record IndexingResult(int indexedDocumentCount, int unchangedDocumentCount,
                                 int indexedChunkCount) {
    }
}
