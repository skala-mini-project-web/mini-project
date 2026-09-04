package com.crosschecklab.global.common.enums;

/**
 * 실제 서비스 사용자 역할. AI 소비자 Persona는 {@link PersonaCode}로 별도 관리한다.
 */
public enum UserRole {

    /** 분석 요청자 */
    PRODUCT_MANAGER,

    /** 검증자 */
    COMPLIANCE_REVIEWER;

    /** Spring Security 권한 이름 (ROLE_ 접두사 포함) */
    public String authority() {
        return "ROLE_" + name();
    }
}
