package io.github.yongyong14.parking_server.global.security;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import io.github.yongyong14.parking_server.global.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;

class CurrentUserResolverTests {
    void probe(@CurrentUser UUID userId) {}
    @Test void anonymousCannotBecomeNullOrInventedUser() throws Exception {
        var parameter = new MethodParameter(getClass().getDeclaredMethod("probe", UUID.class), 0);
        var resolver = new CurrentUserResolver();
        SecurityContextHolder.clearContext();
        assertThat(resolver.supportsParameter(parameter)).isTrue();
        assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null)).isInstanceOf(BusinessException.class);
        UUID id = UUID.randomUUID();
        try {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(id, null, List.of()));
            assertThat(resolver.resolveArgument(parameter, null, null, null)).isEqualTo(id);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
