package com.crosschecklab.global.common;

import java.util.List;
import java.util.function.Function;

// 페이징이 없는 목록 응답 공통 래퍼. { "items": [...] }
// 최상위를 배열로 내리면 나중에 필드를 못 붙이므로 항상 객체로 감싼다.
// 페이징이 필요한 목록은 PageResponse 를 쓴다.
public record ListResponse<T>(List<T> items) {

    public static <T> ListResponse<T> of(List<T> items) {
        return new ListResponse<>(items);
    }

    // 엔티티 목록을 응답 DTO 로 변환하면서 감쌀 때
    public static <T, R> ListResponse<R> of(List<T> items, Function<T, R> mapper) {
        return new ListResponse<>(items.stream().map(mapper).toList());
    }
}
