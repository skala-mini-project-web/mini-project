package com.crosschecklab.global.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

// 페이징 응답 공통 래퍼. 목록 키는 content 가 아니라 items
// { "items": [...], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
// Spring Page 를 그대로 직렬화하면 내부 구조가 노출되고 버전마다 모양이 바뀌므로 이 래퍼로 고정
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    // 이미 응답 DTO 로 변환해 둔 Page 를 그대로 감쌀 때
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    // 엔티티 Page 를 응답 DTO 로 변환하면서 감쌀 때
    public static <T, R> PageResponse<R> of(Page<T> page, Function<T, R> mapper) {
        return from(page.map(mapper));
    }
}
