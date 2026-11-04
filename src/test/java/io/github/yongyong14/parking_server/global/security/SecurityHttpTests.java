package io.github.yongyong14.parking_server.global.security;

import io.github.yongyong14.parking_server.global.response.ApiResponse;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityHttpTests.Probe.class)
class SecurityHttpTests {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService tokens;
    @MockitoBean UserExistence users;
    @MockitoBean java.time.Clock clock;
    private final UUID id = UUID.randomUUID();
    private String bearer() { return "Bearer " + tokens.issue(id).accessToken(); }
    @BeforeEach void existing() {
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-11-04T00:00:00Z"));
        when(users.exists(id)).thenReturn(true);
    }
    @Test void requiresTokenAndInjectsOnlyAuthenticatedId() throws Exception {
        mvc.perform(get("/api/v1/security-probe")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED")).andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/v1/security-probe").param("userId", UUID.randomUUID().toString()).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(id.toString()))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/v1/security-probe")).andExpect(status().isUnauthorized());
    }
    @Test void rejectsDeletedUsersAndSeparatesDatabaseFailures() throws Exception {
        when(users.exists(id)).thenReturn(false);
        mvc.perform(get("/api/v1/security-probe").header("Authorization", bearer())).andExpect(status().isUnauthorized());
        when(users.exists(id)).thenThrow(new DataAccessResourceFailureException("private database details"));
        mvc.perform(get("/api/v1/security-probe").header("Authorization", bearer())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
    @Test void rejectsInvalidAndOpaqueRefreshTokens() throws Exception {
        for (String value : new String[]{"Bearer opaque-refresh", "Basic abc", "Bearer ", "Bearer a b"})
            mvc.perform(get("/api/v1/security-probe").header("Authorization", value)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(users);
    }
    @Test void publicRoutesIgnoreBadBearerButMethodsStayProtected() throws Exception {
        mvc.perform(get("/api/v1/health").header("Authorization", "Bearer broken")).andExpect(status().isOk());
        for (String path : new String[]{"/api/v1/auth/kakao", "/api/v1/auth/refresh", "/api/v1/webhooks/kakao/unlink"}) {
            // No account endpoints are implemented yet; public dispatch must reach MVC's 404.
            mvc.perform(post(path).header("Authorization", "Bearer broken")).andExpect(status().isNotFound());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/unregistered")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }
    @Test void expiredTokenReturnsSpecificCodeAndDoesNotQueryUser() throws Exception {
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-11-03T23:30:00Z"));
        String expired = bearer();
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-11-04T00:00:00Z"));
        mvc.perform(get("/api/v1/security-probe").header("Authorization", expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
        verifyNoInteractions(users);
    }
    @Test void corsIsClosedByDefaultAndFailureIsJson() throws Exception {
        mvc.perform(options("/api/v1/security-probe").header("Origin", "https://unconfigured.example")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
    @Test void permissionFailureUsesCommonJsonWithoutRedirect() throws Exception {
        mvc.perform(get("/api/v1/security-probe/admin").header("Authorization", bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN")).andExpect(header().doesNotExist("Location"));
    }
    @Test void authenticatedWrongMethodKeepsMvcErrorAndSwaggerHidesInjectedId() throws Exception {
        mvc.perform(post("/api/v1/health").header("Authorization", bearer()))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/security-probe'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/security-probe'].get.parameters").doesNotExist());
    }
    @RestController
    static class Probe {
        @GetMapping("/api/v1/security-probe") ApiResponse<UUID> me(@CurrentUser UUID id) { return ApiResponse.success(id); }
        @PreAuthorize("hasAuthority('ADMIN')")
        @GetMapping("/api/v1/security-probe/admin") ApiResponse<Void> admin() { return ApiResponse.success(null); }
    }
}
