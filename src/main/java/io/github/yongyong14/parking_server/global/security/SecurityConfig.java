package io.github.yongyong14.parking_server.global.security;

import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.*;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(TokenSettings.class)
public class SecurityConfig {
    @Bean Clock tokenClock() { return Clock.systemUTC(); }
    @Bean AccessTokenService accessTokenService(TokenSettings settings, Clock tokenClock) {
        return new AccessTokenService(settings, tokenClock);
    }
    @Bean UserExistence userExistence(ObjectProvider<JdbcClient> jdbc) {
        // Lazy resolution permits existing DB-free HTTP contexts; never assume a user exists.
        return id -> new JdbcUserExistence(jdbc.getObject()).exists(id);
    }
    @Bean SecurityErrorWriter securityErrorWriter(ObjectMapper mapper) { return new SecurityErrorWriter(mapper); }

    public static boolean publicApi(String method, String path) {
        return (method.equals("GET") && path.equals("/api/v1/health"))
                || (method.equals("POST") && Set.of("/api/v1/auth/kakao", "/api/v1/auth/refresh",
                    "/api/v1/webhooks/kakao/unlink").contains(path));
    }
    public static boolean documentation(String path) {
        return path.equals("/v3/api-docs") || path.equals("/v3/api-docs.yaml")
                || path.startsWith("/v3/api-docs/") || path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/");
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, AccessTokenService tokens,
            UserExistence users, SecurityErrorWriter errors, Environment env) throws Exception {
        boolean docs = env.getProperty("app.security.swagger-public", Boolean.class, false)
                && !env.matchesProfiles("prod");
        RequestMatcher publicRequests = request -> publicApi(request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()))
                || (docs && request.getMethod().equals("GET") && documentation(request.getRequestURI().substring(request.getContextPath().length())));
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable()) // Bearer header only; no ambient cookie authentication.
                .formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .requestCache(c -> c.disable())
                .cors(c -> c.disable())
                .addFilterBefore(corsFilter(env, errors), org.springframework.web.filter.CorsFilter.class)
                .exceptionHandling(c -> c.authenticationEntryPoint((request, response, exception) -> errors.write(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) -> errors.write(response, ErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(c -> c.requestMatchers(publicRequests).permitAll().anyRequest().authenticated())
                .addFilterBefore(new AccessTokenFilter(tokens, users, errors, publicRequests), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    private org.springframework.web.filter.CorsFilter corsFilter(Environment env, SecurityErrorWriter errors) {
        var filter = new org.springframework.web.filter.CorsFilter(corsConfiguration(env));
        filter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override protected void rejectRequest(org.springframework.http.server.ServerHttpResponse response)
                    throws java.io.IOException {
                errors.write(((org.springframework.http.server.ServletServerHttpResponse) response).getServletResponse(),
                        ErrorCode.FORBIDDEN);
            }
        });
        return filter;
    }
    private CorsConfigurationSource corsConfiguration(Environment env) {
        var config = new CorsConfiguration();
        var origins = env.getProperty("app.security.allowed-origins", "");
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (allowed.stream().anyMatch(s -> s.contains("*"))) throw new IllegalArgumentException("CORS requires explicit origins");
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", config);
        return source;
    }
}
