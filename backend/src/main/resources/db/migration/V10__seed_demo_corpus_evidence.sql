-- Canonical Issue #59 corpus provenance. All artifacts are synthetic and demo-only.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM evidence_documents
        WHERE id = 1 AND source_type = 'INTERNAL_POLICY'
    ) THEN
        RAISE EXCEPTION
            'V10 requires the V2 evidence_documents row id=1, source_type=INTERNAL_POLICY';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM evidence_documents
        WHERE id = 2 AND source_type = 'REGULATION'
    ) THEN
        RAISE EXCEPTION
            'V10 requires the V2 evidence_documents row id=2, source_type=REGULATION';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM evidence_documents
        WHERE id = 3 AND source_type = 'PRODUCT_POLICY'
    ) THEN
        RAISE EXCEPTION
            'V10 requires the V2 evidence_documents row id=3, source_type=PRODUCT_POLICY';
    END IF;
END
$$;

CREATE TABLE demo_corpus_artifacts (
    logical_id          VARCHAR(120) PRIMARY KEY,
    artifact_path       VARCHAR(500) NOT NULL UNIQUE,
    artifact_sha256     CHAR(64)     NOT NULL UNIQUE,
    artifact_version    VARCHAR(50)  NOT NULL,
    license             VARCHAR(50)  NOT NULL,
    synthetic           BOOLEAN      NOT NULL,
    demo_only           BOOLEAN      NOT NULL,
    evidence_document_id BIGINT UNIQUE REFERENCES evidence_documents (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_demo_corpus_artifact_sha256
        CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_demo_corpus_artifact_synthetic
        CHECK (synthetic = TRUE AND demo_only = TRUE),
    CONSTRAINT ck_demo_corpus_artifact_license
        CHECK (btrim(license) <> ''),
    CONSTRAINT ck_demo_corpus_artifact_version
        CHECK (btrim(artifact_version) <> ''),
    CONSTRAINT uq_demo_corpus_artifact_evidence
        UNIQUE (logical_id, evidence_document_id)
);

COMMENT ON TABLE demo_corpus_artifacts IS
    'Auditable logical-id-to-file/database mapping for the 100% synthetic, demo-only canonical corpus; it confers no legal or regulatory authority.';
COMMENT ON COLUMN demo_corpus_artifacts.artifact_sha256 IS
    'Lowercase SHA-256 of the canonical PDF bytes recorded in data/demo-corpus/manifest.v1.json.';
COMMENT ON COLUMN demo_corpus_artifacts.evidence_document_id IS
    'Active retrieval evidence row, or NULL when the artifact is product input rather than retrieval evidence.';

CREATE TABLE demo_corpus_evidence_mappings (
    product_logical_id     VARCHAR(120) NOT NULL
        REFERENCES demo_corpus_artifacts (logical_id),
    evidence_logical_id    VARCHAR(120) NOT NULL,
    evidence_document_id   BIGINT       NOT NULL,
    selection_order        SMALLINT     NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_logical_id, evidence_logical_id),
    CONSTRAINT fk_demo_corpus_mapping_evidence
        FOREIGN KEY (evidence_logical_id, evidence_document_id)
        REFERENCES demo_corpus_artifacts (logical_id, evidence_document_id),
    CONSTRAINT uq_demo_corpus_mapping_document
        UNIQUE (product_logical_id, evidence_document_id),
    CONSTRAINT uq_demo_corpus_mapping_order
        UNIQUE (product_logical_id, selection_order),
    CONSTRAINT ck_demo_corpus_mapping_distinct_artifacts
        CHECK (product_logical_id <> evidence_logical_id),
    CONSTRAINT ck_demo_corpus_mapping_selection_order
        CHECK (selection_order > 0)
);

COMMENT ON TABLE demo_corpus_evidence_mappings IS
    'Explicit demo-only product logical-id to selected evidence logical-id and evidence_documents database-id mapping.';

UPDATE evidence_documents
SET source_type = 'PRODUCT_POLICY',
    title = 'SMART 인컴 상품 운영정책 (완전 합성 데모)',
    version = '1.0',
    content = $canonical$
ARGUS | SYNTHETIC DEMO CORPUS SMART-INCOME-PRODUCT-POLICY-v1 · 1/2
SMART 인컴 상품 운영정책
문서번호 SYN-PROD-001 · 버전 1.0
완전 합성 내부정책 — 데모 상품에만 적용되며 실제 회사 정책이 아닙니다.
1. 상품 정의
SMART 인컴 플랜은 12개월 만기의 A-밸런스 인컴 지수 연동형 합성 상품이다. 수익률과 월별 분배금은 변동하며 확정 수익 또는 원금 보장을 제공하지 않는다.
2. 승인된 위험 고지
모든 판매 접점은 “원금의 전부 또는 일부 손실 가능”, “분배금 0원 가능”, “예상 연 환산 수익률 -8.0%~+10.0%는 비확정 범위”를 명시한다.
3. 금지 홍보 표현
“매월 수익을 보장하는 안정형 선택”, “원금 안전”, “손실 없는 월수익”은 단독 또는 강조 형태로 사용할 수 없다. 인용·검토 목적으로 표시할 때에는 바로 인접한 위치에 오인 방지 정정문을 둔다.
완전 합성 데모 문서 · 실제 상품, 회사, 소비자, 법령 또는 규제기관을 나타내지 않음
ARGUS | SYNTHETIC DEMO CORPUS SMART-INCOME-PRODUCT-POLICY-v1 · 2/2
비용·정산·판매 통제
4. 중도 종료 산식
정률 비용은 투자원금 기준으로 가입일부터 30일 이내 3.0%, 31~90일 2.0%, 91~180일 1.0%, 181일 이후 0.0%이다. 종료일 지수 수준에 따른 시장가치 조정은 정률 비용과 별도로 더하거나 뺀다.
5. 예시 정합성
투자원금 10,000,000원, 가입 15일째 종료, 시장가치 9,200,000원인 합성 사례의 정률 비용은 300,000원이고 지급 예시는 8,900,000원이다. 예시는 보장값으로 표시하지 않는다.
6. 판매자료 필수 항목
상품명과 버전, 변동 수익 구조, 원금손실 고지, 보유 기간별 정률 비용, 시장가치 조정, 합성 자료 표시를 포함한다. 누락 시 자료를 배포하지 않는다.
7. 데모 권위 범위
이 정책은 Issue #59 데모에서 SMART 인컴 상품 사실관계를 검증하는 내부 기준 문서로만 취급한다. 실제 금융·법률 권위를 갖지 않는다.
완전 합성 데모 문서 · 실제 상품, 회사, 소비자, 법령 또는 규제기관을 나타내지 않음
$canonical$,
    active = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 3;

UPDATE evidence_documents
SET source_type = 'INTERNAL_POLICY',
    title = '중요정보 표시 내부정책 (완전 합성 데모)',
    version = '2026.1',
    content = $canonical$
ARGUS | SYNTHETIC DEMO CORPUS IMPORTANT-INFO-DISPLAY-POLICY-v2026.1 · 1/2
중요정보 표시 내부정책
버전 2026.1 · 문서번호 SYN-DISC-2026-1
완전 합성 내부 표시기준 — 실제 법령이나 업계 표준이 아닙니다.
1. 목적과 적용 범위
데모 금융상품의 홍보 문구가 손실·비용 정보를 가리지 않도록 화면, PDF, 상담 스크립트의 표시 순서와 근접성을 정한다.
2. 동일 화면 원칙
“안정”, “보장”, “확정”과 같은 표현이 있으면 원금손실 가능성과 변동 수익 정정문을 같은 페이지, 같은 화면, 같은 음성 구간에 표시한다. 각 고지는 홍보 문구 다음 핵심 정보 블록보다 늦게 나타나면 안 된다.
3. 시각적 동등성
정정문 글자 크기는 홍보 문구의 70% 이상이며 최소 10포인트로 한다. 낮은 대비, 접힌 영역, 각주만을 이용한 핵심 위험 고지는 허용하지 않는다.
완전 합성 데모 문서 · 실제 상품, 회사, 소비자, 법령 또는 규제기관을 나타내지 않음
ARGUS | SYNTHETIC DEMO CORPUS IMPORTANT-INFO-DISPLAY-POLICY-v2026.1 · 2/2
필수 고지와 검수
4. 비용의 정량 표시
중도 종료 비용은 “비용이 발생할 수 있음”으로만 쓰지 않고 기간별 요율과 하나 이상의 원화 계산 예시를 함께 제시한다. 시장가치 조정이 별도 적용되고 정률 비용을 초과하는 손실이 가능함을 명시한다.
5. 원금손실 문구
“원금의 전부 또는 일부를 잃을 수 있습니다”를 완결된 문장으로 표시한다. 변동 수익 상품은 분배금 0원 가능성과 수익률 비확정성을 별도로 설명한다.
6. 검수 기록
검수자는 판매자료 버전, 검수일, 확인 항목과 근거 문서 논리 ID를 기록한다. RAG 데모 데이터베이스의 연결은 사전 시드된 검색 매핑일 뿐 법적 권위나 규제기관 승인을 의미하지 않는다.
7. 합성 표시
모든 페이지 바닥글에 완전 합성 문서임과 실제 상품·법령이 아님을 표시한다.
완전 합성 데모 문서 · 실제 상품, 회사, 소비자, 법령 또는 규제기관을 나타내지 않음
$canonical$,
    active = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1;

UPDATE evidence_documents
SET source_type = 'REGULATION',
    title = '합성 금융소비자 설명의무 규정 발췌 (완전 합성 데모)',
    version = '1.0',
    content = $canonical$
ARGUS | SYNTHETIC DEMO CORPUS FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1 · 1/2
합성 금융소비자 설명의무 규정 발췌
데모 규정 SYN-REG-EXPLAIN-01 · 버전 1.0
주의: 아래 조문은 검색·검증 데모를 위해 창작한 가상 규정입니다. 대한민국 또는 다른 관할의 실제 법령이 아니며 법적 효력이 없습니다.
제12조(중요사항의 설명)
가상 판매자는 변동 수익 상품을 설명할 때 수익이 확정되지 않는다는 사실, 원금의 전부 또는 일부 손실 가능성, 계약 종료 비용과 계산 기준을 소비자가 이해할 수 있는 순서로 제시한다.
제13조(강조 표현의 정정)
안정, 보장 또는 확정을 연상시키는 표현을 사용한 경우 동일한 전달 단위에서 그 표현의 한계와 반대되는 손실 가능성을 명확히 정정한다.
제14조(정량 예시)
비용이 보유 기간 또는 시장가치에 따라 달라지는 경우 기간별 요율과 합성 계산 예시를 표시하고, 예시가 미래 결과를 보장하지 않는다고 밝힌다.
완전 합성 데모 문서 · 실제 상품, 회사, 소비자, 법령 또는 규제기관을 나타내지 않음
ARGUS | SYNTHETIC DEMO CORPUS FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1 · 2/2
합성 조문 해설
발췌 범위
본 발췌는 가상의 제12조부터 제14조까지만 포함한다. 다른 조항, 시행령, 감독규정 또는 판례가 존재한다고 추론해서는 안 된다.
데모 적용 예
“매월 수익을 보장하는 안정형 선택”과 같은 문구가 나타나면 변동 수익, 원금손실 가능성, 중도 종료 비용을 함께 검색하여 설명하는 시나리오에 사용한다.
비권위 선언
이 문서는 실제 규제기관이 작성·승인·공표한 자료가 아니다. 법률 준수 판단, 소비자 분쟁, 투자 의사결정 또는 전문 자문에 인용할 수 없다.
출처
Argus Issue #59 canonical demo corpus를 위해 2026년에 독자적으로 작성한 100% 합성 텍스트이다. 실존 법률 문구를 복제하지 않았다.
완전 합성 데모 문서 · 실제 상품, 회사, 소비자, 법령 또는 규제기관을 나타내지 않음
$canonical$,
    active = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 2;

INSERT INTO demo_corpus_artifacts (
    logical_id, artifact_path, artifact_sha256, artifact_version,
    license, synthetic, demo_only, evidence_document_id
) VALUES
    ('product.smart-income.sales',
     'documents/product/SMART-INCOME-SALES-v1.pdf',
     'f7d1b5887dc9e55b58ac7c244e65c23daeeac7be6d017d23e61ec0e4804be0c9',
     '1.0', 'CC0-1.0', TRUE, TRUE, NULL),
    ('evidence.smart-income.product-policy',
     'documents/evidence/SMART-INCOME-PRODUCT-POLICY-v1.pdf',
     '47dae5a632f32c12da39135756ecc20590cfe7bc1b42cb323290851c3fd1fb74',
     '1.0', 'CC0-1.0', TRUE, TRUE, 3),
    ('evidence.important-info-display-policy',
     'documents/evidence/IMPORTANT-INFO-DISPLAY-POLICY-v2026.1.pdf',
     '595f9f81aba4941b514b50a5eac128e2f5376ac62be8d3ae916339a79ad5060e',
     '2026.1', 'CC0-1.0', TRUE, TRUE, 1),
    ('evidence.financial-consumer-explanation-duty-excerpt',
     'documents/evidence/FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1.pdf',
     '38b2ac8007db5ed162173dedb8f2ddeb470424147be695d4bbb3eef11d0c1999',
     '1.0', 'CC0-1.0', TRUE, TRUE, 2);

INSERT INTO demo_corpus_evidence_mappings (
    product_logical_id, evidence_logical_id, evidence_document_id, selection_order
) VALUES
    ('product.smart-income.sales',
     'evidence.smart-income.product-policy', 3, 1),
    ('product.smart-income.sales',
     'evidence.important-info-display-policy', 1, 2),
    ('product.smart-income.sales',
     'evidence.financial-consumer-explanation-duty-excerpt', 2, 3);
