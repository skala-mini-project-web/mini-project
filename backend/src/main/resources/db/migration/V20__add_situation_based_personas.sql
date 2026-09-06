-- Retain legacy Persona templates for historical analyses, but prevent them from
-- being selected for new analyses.
UPDATE persona_templates
SET active = FALSE,
    updated_at = NOW()
WHERE code IN (
    'FINANCIAL_BEGINNER',
    'SENIOR',
    'LOSS_EXPERIENCED',
    'SHORT_TERM_LIQUIDITY',
    'SELF_EMPLOYED'
);

ALTER TABLE persona_templates
    DROP CONSTRAINT ck_persona_templates_code;

ALTER TABLE persona_templates
    ALTER COLUMN code TYPE VARCHAR(60);

ALTER TABLE persona_templates
    ADD CONSTRAINT ck_persona_templates_code CHECK (code IN (
        'FINANCIAL_BEGINNER',
        'SENIOR',
        'LOSS_EXPERIENCED',
        'SHORT_TERM_LIQUIDITY',
        'SELF_EMPLOYED',
        'LIMITED_PRODUCT_FAMILIARITY',
        'LOSS_RECOVERY_PRESSURE',
        'NEAR_TERM_LIQUIDITY_NEED',
        'VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT',
        'EXPLANATION_ACCESS_SUPPORT',
        'DIGITAL_CHANNEL_SUPPORT',
        'LIFE_EVENT_FINANCIAL_STRESS'
    ));

INSERT INTO persona_templates (code, name, criteria, risk_focus, active, created_at, updated_at) VALUES
    ('LIMITED_PRODUCT_FAMILIARITY', '상품 이해 지원 필요 상황',
     '{"testSituation": "상품 구조·용어 이해 지원이 필요한 상황"}'::jsonb,
     '["상품 구조 설명", "전문용어 이해", "핵심 조건 오해"]'::jsonb,
     TRUE, NOW(), NOW()),
    ('LOSS_RECOVERY_PRESSURE', '손실 만회 압박 상황',
     '{"testSituation": "손실 만회 기대가 수익·안정성 표현에 영향을 받을 수 있는 상황"}'::jsonb,
     '["수익 강조", "안정성 표현", "손실 가능성"]'::jsonb,
     TRUE, NOW(), NOW()),
    ('NEAR_TERM_LIQUIDITY_NEED', '단기 유동성 필요 상황',
     '{"testSituation": "가까운 시일 내 자금 사용·해지 가능성을 확인하는 상황"}'::jsonb,
     '["중도해지 비용", "환매 지연", "유동성 제약"]'::jsonb,
     TRUE, NOW(), NOW()),
    ('VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT', '변동 현금흐름·상환 제약 상황',
     '{"testSituation": "월별 현금흐름·상환 부담을 확인하는 상황"}'::jsonb,
     '["납부 조건", "상환 부담", "금리 변동"]'::jsonb,
     TRUE, NOW(), NOW()),
    ('EXPLANATION_ACCESS_SUPPORT', '설명 접근 지원 필요 상황',
     '{"testSituation": "복잡한 정보에 추가 설명·이해 확인이 필요한 상황"}'::jsonb,
     '["추가 설명", "이해 확인", "인지 접근성"]'::jsonb,
     TRUE, NOW(), NOW()),
    ('DIGITAL_CHANNEL_SUPPORT', '디지털 채널 지원 필요 상황',
     '{"testSituation": "디지털 채널·전자 고지에서 추가 확인이 필요한 상황"}'::jsonb,
     '["전자 고지", "채널 접근성", "중요 정보 확인"]'::jsonb,
     TRUE, NOW(), NOW()),
    ('LIFE_EVENT_FINANCIAL_STRESS', '생애 사건 재무 스트레스 상황',
     '{"testSituation": "일시적 소득·돌봄·건강·상실 등 변화 상황에서 핵심 조건을 확인하는 상황"}'::jsonb,
     '["핵심 조건 확인", "비용·손실 설명", "의사결정 압박"]'::jsonb,
     TRUE, NOW(), NOW());

COMMENT ON TABLE persona_templates IS
    '문서 설명 실패를 검증하는 합성 상황 Persona. 비활성 legacy 행은 역사 결과 조회를 위해 보존한다.';
