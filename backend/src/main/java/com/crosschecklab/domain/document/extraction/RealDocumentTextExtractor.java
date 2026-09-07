package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.DocumentMediaType;
import com.crosschecklab.domain.document.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// real-extraction 프로파일의 추출기. 영구 저장된 원본 바이트를 형식별 추출기에 넘긴다.
// 읽기는 원본을 소비하지 않으므로 같은 storage_key 로 안전하게 재추출할 수 있다.
@Component
@Profile("real-extraction")
public class RealDocumentTextExtractor implements TextExtractionService {

    private final FileStorage fileStorage;
    private final Map<DocumentMediaType, BinaryTextExtractor> extractorsByMediaType =
            new EnumMap<>(DocumentMediaType.class);

    public RealDocumentTextExtractor(FileStorage fileStorage, List<BinaryTextExtractor> extractors) {
        this.fileStorage = fileStorage;
        extractors.forEach(extractor ->
                extractorsByMediaType.put(extractor.supportedMediaType(), extractor));
    }

    // A static factory avoids a dependency cycle between this aggregate extractor and the
    // PdfBoxTextExtractor that optionally consumes the client.
    @Bean
    @Profile("real-extraction")
    public static OcrClient ocrClient(
            @Value("${ocr.base-url}") String baseUrl,
            @Value("${ocr.bearer-token}") String bearerToken,
            @Value("${ocr.connect-timeout}") Duration connectTimeout,
            @Value("${ocr.request-timeout}") Duration requestTimeout,
            @Value("${ocr.allow-insecure-http:false}") boolean allowInsecureHttp,
            ObjectMapper objectMapper
    ) {
        return new HttpOcrClient(
                baseUrl,
                bearerToken,
                connectTimeout,
                requestTimeout,
                allowInsecureHttp,
                objectMapper);
    }

    @Override
    public String extract(ExtractionTarget target) {
        return extractResult(target).text();
    }

    @Override
    public DocumentExtractionResult extractResult(ExtractionTarget target) {
        DocumentMediaType mediaType = DocumentMediaType.resolve(target.mediaType(), target.fileName())
                .orElseThrow(() -> new TextExtractionException("추출할 수 없는 형식입니다: " + target.mediaType()));

        BinaryTextExtractor extractor = extractorsByMediaType.get(mediaType);
        if (extractor == null) {
            throw new TextExtractionException(mediaType + " 추출기가 등록되어 있지 않습니다.");
        }

        byte[] content = fileStorage.read(target.storageKey())
                .orElseThrow(() -> new TextExtractionException(
                        "저장된 업로드 원본을 찾을 수 없습니다: " + target.storageKey()));

        if (extractor instanceof PdfBoxTextExtractor pdfExtractor) {
            return pdfExtractor.extractResult(content);
        }
        String text = extractor.extract(new ByteArrayInputStream(content));
        return DocumentExtractionResult.withoutPageProvenance(
                text, PageExtractionResult.hashBytes(content));
    }
}
