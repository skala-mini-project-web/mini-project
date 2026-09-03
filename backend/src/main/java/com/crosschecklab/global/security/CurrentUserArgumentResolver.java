package com.crosschecklab.global.security;

import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

// @CurrentUser DemoUser 파라미터를 채운다.
// DemoAuthenticationFilter 가 남긴 실패 사유가 있으면 그 ErrorCode 로, 아예 헤더가 없었으면
// DEMO_AUTHENTICATION_REQUIRED 로 401 을 던진다. 예외는 GlobalExceptionHandler 가 ErrorResponse 로 바꾼다.
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && DemoUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object failure = webRequest.getAttribute(
                DemoAuthenticationFilter.FAILURE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (failure instanceof ErrorCode errorCode) {
            throw new BusinessException(errorCode);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof DemoUser demoUser) {
            return demoUser;
        }
        throw new BusinessException(ErrorCode.DEMO_AUTHENTICATION_REQUIRED);
    }
}
