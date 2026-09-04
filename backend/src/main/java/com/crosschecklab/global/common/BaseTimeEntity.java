package com.crosschecklab.global.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.OffsetDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// created_at / updated_at 자동 채움. TIMESTAMPTZ 컬럼과 맞춰 OffsetDateTime 을 쓴다.
// 주의: PostgreSQL timestamptz 는 UTC 로 정규화(+09:00 으로 넣어도 읽으면 ...Z 로 돌아옴)
// KST 로 보여줘야 하면 DTO 변환 시 바꾼다.
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
