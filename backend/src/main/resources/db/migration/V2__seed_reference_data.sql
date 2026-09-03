-- =============================================================================
-- 데모/기준 데이터 시드
-- 기준: GuardLab_MVP_데이터_명세서 v0.3 §4(Persona 5종) · §5(Red Team Pack)
--
-- persona_templates의 id는 API 명세 TEST-001 예시(1~5)와 일치시켜야 하므로
-- 명시적으로 지정하고, 마지막에 identity 시퀀스를 재정렬한다.
-- =============================================================================

INSERT INTO users (id, username, name, role, active, created_at, updated_at) VALUES
    (1, 'pm_park',      '박서준 대리', 'PRODUCT_MANAGER',      TRUE, NOW(), NOW()),
    (2, 'reviewer_kim', '김민지 과장', 'COMPLIANCE_REVIEWER', TRUE, NOW(), NOW());

INSERT INTO evidence_documents (id, source_type, title, version, content, active, created_at, updated_at) VALUES
    (1, 'INTERNAL_POLICY', '금융상품 중요정보 표시 내부준칙 (데모)', 'DEMO-2026.1',
     '원금손실 가능성은 안정성 표현과 인접하여 동일한 시각적 비중으로 표시해야 합니다. 수익률 표현 뒤에는 반드시 변동 가능성을 함께 안내합니다.',
     TRUE, NOW(), NOW()),
    (2, 'REGULATION', '금융소비자보호법 설명의무 요지 (데모)', 'DEMO-2026.1',
     '금융상품 판매업자는 일반금융소비자가 이해할 수 있도록 상품의 내용, 투자 위험, 수수료를 설명해야 합니다. 체크박스 확인만으로 설명의무를 다한 것으로 보지 않습니다.',
     TRUE, NOW(), NOW()),
    (3, 'PRODUCT_POLICY', '투자상품 판매 자료 작성 지침 (데모)', 'DEMO-2026.1',
     '중도해지 수수료와 환매 지연 가능성은 별도 항목으로 분리하여 명시합니다. 과거 수익률은 미래 수익을 보장하지 않는다는 문구를 병기합니다.',
     TRUE, NOW(), NOW());

INSERT INTO persona_templates (id, code, name, criteria, risk_focus, active, created_at, updated_at) VALUES
    (1, 'FINANCIAL_BEGINNER', '금융 초보자',
     '{"financialLiteracy": "LOW", "investmentExperience": "NONE"}'::jsonb,
     '["확정수익 오해", "원금보장 오해"]'::jsonb,
     TRUE, NOW(), NOW()),
    (2, 'SENIOR', '고령 금융소비자',
     '{"digitalLiteracy": "LOW", "stabilityPreference": "HIGH"}'::jsonb,
     '["안정성 표현 오해", "인지 접근성"]'::jsonb,
     TRUE, NOW(), NOW()),
    (3, 'LOSS_EXPERIENCED', '손실 경험자',
     '{"riskSensitivity": "HIGH", "lossRecoveryDesire": "HIGH"}'::jsonb,
     '["손실 범위 인식", "위험 민감도"]'::jsonb,
     TRUE, NOW(), NOW()),
    (4, 'SHORT_TERM_LIQUIDITY', '단기 자금 필요',
     '{"liquidityNeed": "HIGH", "investmentHorizon": "SHORT"}'::jsonb,
     '["중도해지 비용", "유동성 제약"]'::jsonb,
     TRUE, NOW(), NOW()),
    (5, 'SELF_EMPLOYED', '자영업자',
     '{"incomeStability": "LOW", "cashFlowSensitivity": "HIGH"}'::jsonb,
     '["금리 변동", "납부조건", "현금흐름"]'::jsonb,
     TRUE, NOW(), NOW());

INSERT INTO red_team_packs (id, code, name, description, active, created_at, updated_at) VALUES
    (1, 'CORE_FINANCIAL_RISK_V1', '금융소비자 핵심 위험 Pack',
     '금융상품 설명의 대표적인 오해 유발 패턴 6종을 점검합니다.', TRUE, NOW(), NOW());

INSERT INTO red_team_rules (id, code, pack_id, name, description, sort_order, active, created_at, updated_at) VALUES
    (1, 'RETURN_FRAMING',          1, '수익 강조 편향',   '수익을 위험보다 먼저·크게 강조',              1, TRUE, NOW(), NOW()),
    (2, 'LOSS_SOFTENING',          1, '손실 완화 표현',   '손실 가능성을 추상적으로 완화',                2, TRUE, NOW(), NOW()),
    (3, 'COST_OMISSION',           1, '비용 누락',        '수수료·해지 비용 누락 또는 분산',              3, TRUE, NOW(), NOW()),
    (4, 'STABILITY_KEYWORD',       1, '안정성 키워드',    '안정·보장 유사 표현으로 오인 유발',            4, TRUE, NOW(), NOW()),
    (5, 'FORMAL_CONFIRMATION',     1, '형식적 확인',      '체크박스만으로 이해를 간주',                   5, TRUE, NOW(), NOW()),
    (6, 'COGNITIVE_ACCESSIBILITY', 1, '인지 접근성',      '긴 문장·작은 글씨·전문용어 집중',              6, TRUE, NOW(), NOW());

-- 명시적 id를 사용했으므로 identity 시퀀스를 현재 최대값 다음으로 맞춘다.
SELECT setval(pg_get_serial_sequence('users', 'id'),              (SELECT MAX(id) FROM users));
SELECT setval(pg_get_serial_sequence('evidence_documents', 'id'), (SELECT MAX(id) FROM evidence_documents));
SELECT setval(pg_get_serial_sequence('persona_templates', 'id'),  (SELECT MAX(id) FROM persona_templates));
SELECT setval(pg_get_serial_sequence('red_team_packs', 'id'),     (SELECT MAX(id) FROM red_team_packs));
SELECT setval(pg_get_serial_sequence('red_team_rules', 'id'),     (SELECT MAX(id) FROM red_team_rules));
