package io.github.yongyong14.parking_server.global.security;

import java.util.UUID;
import io.github.yongyong14.parking_server.global.exception.*;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;

public final class CurrentUserResolver implements HandlerMethodArgumentResolver {
    @Override public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }
    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory factory) {
        if (parameter.getParameterType() != UUID.class) throw new IllegalStateException("@CurrentUser requires UUID");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof UUID id))
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return id;
    }
}
