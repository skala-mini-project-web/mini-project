package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.DocumentMediaType;
import java.io.InputStream;

// 실제 파일 형식별 텍스트 추출기. 형식 하나당 구현 하나.
public interface BinaryTextExtractor {

    DocumentMediaType supportedMediaType();

    String extract(InputStream content);
}
