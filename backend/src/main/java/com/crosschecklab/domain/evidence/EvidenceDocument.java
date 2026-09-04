package com.crosschecklab.domain.evidence;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.EvidenceSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 분석 결과가 인용하는 근거 문서(내부준칙 / 법규 / 상품정책).
// 행은 V2 시드로만 생성되며 MVP 에서는 조회만 한다.
@Entity
@Getter
@Table(name = "evidence_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvidenceDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private EvidenceSourceType sourceType;

    @Column(nullable = false)
    private String title;

    @Column(length = 50)
    private String version;

    @Column(columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private boolean active;
}
