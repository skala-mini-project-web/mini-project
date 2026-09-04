package com.crosschecklab.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 인증된 DemoUser 를 컨트롤러 파라미터로 주입받는다.
// 이 애노테이션이 붙은 엔드포인트는 인증이 필수이며, 인증되지 않았으면 401 로 끊긴다.
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
