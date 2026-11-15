package io.github.yongyong14.parking_server.global.security;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AccessTokenFilter extends OncePerRequestFilter {
    private final AccessTokenService tokens;
    private final UserExistence users;
    private final SecurityErrorWriter errors;
    private final RequestMatcher publicRequests;
    public AccessTokenFilter(AccessTokenService tokens, UserExistence users, SecurityErrorWriter errors,
            RequestMatcher publicRequests) {
        this.tokens = tokens; this.users = users; this.errors = errors; this.publicRequests = publicRequests;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return publicRequests.matches(request); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            List<String> headers = Collections.list(request.getHeaders("Authorization"));
            if (!headers.isEmpty()) {
                String header = headers.getFirst();
                if (headers.size() != 1 || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                        || header.substring(7).isBlank() || header.substring(7).contains(" "))
                    throw new BusinessException(ErrorCode.UNAUTHORIZED);
                UUID id = tokens.verify(header.substring(7));
                if (!users.exists(id)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(id, null, List.of()));
                SecurityContextHolder.setContext(context);
            }
        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            errors.write(response, e.getErrorCode()); return;
        } catch (DataAccessException e) {
            SecurityContextHolder.clearContext();
            // Do not log JDBC details, bound values, headers or token material.
            logger.error("Authentication user lookup failed");
            errors.write(response, ErrorCode.INTERNAL_SERVER_ERROR); return;
        }
        // Downstream MVC/authorization exceptions must retain their own handling.
        chain.doFilter(request, response);
    }
}
