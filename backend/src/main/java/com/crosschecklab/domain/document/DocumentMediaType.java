package com.crosschecklab.domain.document;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

// 업로드를 허용하는 문서 형식. PDF 와 PPTX 만 받는다 (API 명세 DOC-001).
public enum DocumentMediaType {

    PDF("application/pdf", ".pdf"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");

    private final String contentType;
    private final String extension;

    DocumentMediaType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    // 브라우저·클라이언트가 application/octet-stream 을 보내는 경우가 있어 확장자로도 판정한다.
    // 둘 중 하나라도 맞으면 허용하고, 저장할 media_type 은 여기서 정한 정규 값으로 통일한다.
    public static Optional<DocumentMediaType> resolve(String contentType, String fileName) {
        Optional<DocumentMediaType> byContentType = Arrays.stream(values())
                .filter(type -> type.contentType.equalsIgnoreCase(stripParameters(contentType)))
                .findFirst();
        if (byContentType.isPresent()) {
            return byContentType;
        }

        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> lowerName.endsWith(type.extension))
                .findFirst();
    }

    // "application/pdf; charset=UTF-8" 처럼 파라미터가 붙어 오는 경우를 잘라낸다.
    private static String stripParameters(String contentType) {
        if (contentType == null) {
            return "";
        }
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
    }
}
