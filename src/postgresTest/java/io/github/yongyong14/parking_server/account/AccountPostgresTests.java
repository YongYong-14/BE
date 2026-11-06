package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false",
        "app.auth.issuer=isolated-account-test", "app.auth.secret-base64=${random.value}${random.value}",
        "app.account.kakao-app-id=7", "app.account.kakao-admin-key=mock-admin",
        "app.account.terms-versions=terms-v1", "app.account.privacy-versions=privacy-v1",
        "app.account.location-versions=location-v1"
})
@EntityScan(basePackageClasses = User.class)
@AutoConfigureMockMvc
class AccountPostgresTests {
    private static final String BASE = "/api/v1";
    private static final AtomicLong KAKAO_IDS = new AtomicLong(100);
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @MockitoSpyBean KakaoClient kakao;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String url = required("AUTH_TEST_JDBC_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/auth_verify")) {
            throw new IllegalStateException("Expected isolated auth_verify database on loopback");
        }
        String password = required("AUTH_TEST_PASSWORD");
        attest(url, "auth_tester", password);
        attest(url, "auth_app", password);
        Flyway.configure().dataSource(url, "auth_tester", password)
                .defaultSchema("flyway").schemas("flyway").cleanDisabled(true)
                .placeholderReplacement(false).load().migrate();
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "auth_app");
        properties.add("spring.datasource.password", () -> password);
    }

    private static void attest(String url, String user, String password) {
        var db = JdbcClient.create(new DriverManagerDataSource(url, user, password));
        var identity = db.sql("SELECT current_database() || ':' || current_user || ':' || current_setting('verification.identity', true)")
                .query(String.class).single();
        if (!identity.equals("auth_verify:" + user + ":" + required("AUTH_TEST_IDENTITY"))) {
            throw new IllegalStateException("Isolated PostgreSQL database identity mismatch");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Use Verify-Authentication.ps1 -Account");
        return value;
    }

    @Test
    void createsUserAndDefaultsThenUpdatesTheSameAccountAndOnlyStoresRefreshHash() throws Exception {
        long kakaoId = stubProfile();
        JsonNode first = login(kakaoId);
        UUID id = userId(first);
        assertThat(first.path("isNewUser").asBoolean()).isTrue();
        assertThat(first.path("expiresIn").asLong()).isEqualTo(1800);
        assertThat(first.at("/agreements/missing").size()).isEqualTo(3);
        assertThat(jdbc.sql("select token_hash from refresh_tokens where user_id = :id")
                .param("id", id).query(String.class).single()).matches("[0-9a-f]{64}").isNotEqualTo(refresh(first));
        assertThat(jdbc.sql("""
                select extract(epoch from expires_at - created_at) from refresh_tokens where user_id = :id
                """).param("id", id).query(Double.class).single()).isBetween(2591995.0, 2592005.0);

        doReturn(new KakaoClient.Profile(kakaoId, "updated", null, null)).when(kakao).verify(kakaoToken(kakaoId));
        JsonNode second = login(kakaoId);
        assertThat(second.path("isNewUser").asBoolean()).isFalse();
        assertThat(userId(second)).isEqualTo(id);
        mvc.perform(get(BASE + "/me").header("Authorization", bearer(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("updated"))
                .andExpect(jsonPath("$.data.hasVehicle").value(false));
        mvc.perform(get(BASE + "/notification-settings").header("Authorization", bearer(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dangerEnabled").value(true))
                .andExpect(jsonPath("$.data.cautionEnabled").value(true))
                .andExpect(jsonPath("$.data.nightEnabled").value(false));
    }

    @Test
    void concurrentLoginsCreateOneAccountAndOneSettingsRow() throws Exception {
        long kakaoId = stubProfile();
        var both = concurrently(() -> login(kakaoId));
        assertThat(userId(both.getFirst())).isEqualTo(userId(both.getLast()));
        assertThat(both.stream().map(v -> v.path("isNewUser").asBoolean())).containsExactlyInAnyOrder(true, false);
        assertThat(count("notification_settings", userId(both.getFirst()))).isEqualTo(1);
    }

    @Test
    void providerFailureAndPartialInsertFailureDoNotCreateUserOrDefaults() throws Exception {
        long kakaoId = stubProfile();
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(kakao).verify(kakaoToken(kakaoId));
        mvc.perform(post(BASE + "/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(loginJson(kakaoId)))
                .andExpect(status().isServiceUnavailable());
        assertThat(userCount(kakaoId)).isZero();

        doReturn(new KakaoClient.Profile(kakaoId, "n".repeat(101), null, null)).when(kakao).verify(kakaoToken(kakaoId));
        mvc.perform(post(BASE + "/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(loginJson(kakaoId)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
        assertThat(userCount(kakaoId)).isZero();
    }

    @Test
    void rotatingThenReplayingCommitsFamilyRevocationAndLeavesOtherFamilyUsable() throws Exception {
        long kakaoId = stubProfile();
        JsonNode first = login(kakaoId);
        JsonNode otherDevice = login(kakaoId);
        JsonNode next = data(mvc.perform(renew(refresh(first))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(refresh(next)).isNotEqualTo(refresh(first));
        assertThat(jdbc.sql("select count(*) from refresh_tokens where user_id = :id and replaced_by_id is not null")
                .param("id", userId(first)).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(renew(refresh(first))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"));
        assertThat(activeTokens(userId(first))).isEqualTo(1);
        mvc.perform(renew(refresh(next))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"));
        mvc.perform(renew(refresh(otherDevice))).andExpect(status().isOk());
    }

    @Test
    void concurrentRotationCommitsOneSuccessAndOneReplayWithNoActiveSuccessor() throws Exception {
        JsonNode login = login(stubProfile());
        var results = concurrently(() -> mvc.perform(renew(refresh(login))).andReturn().getResponse());
        assertThat(results.stream().map(r -> r.getStatus())).containsExactlyInAnyOrder(200, 401);
        var replay = results.stream().filter(r -> r.getStatus() == 401).findFirst().orElseThrow();
        assertThat(json.readTree(replay.getContentAsString()).at("/error/code").asString()).isEqualTo("TOKEN_REUSED");
        assertThat(activeTokens(userId(login))).isZero();
    }

    @Test
    void logoutChecksOwnerIsIdempotentAndKeepsExistingAccessTokenUsable() throws Exception {
        JsonNode first = login(stubProfile());
        JsonNode stranger = login(stubProfile());
        JsonNode successor = data(mvc.perform(renew(refresh(first))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        mvc.perform(logout(stranger, refresh(first))).andExpect(status().isForbidden());
        assertThat(activeTokens(userId(first))).isEqualTo(1);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(logout(first, refresh(first))).andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        mvc.perform(renew(refresh(successor))).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/me").header("Authorization", bearer(first))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/me").header("Authorization", "Bearer fake")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAndUnknownRefreshTokensAreRejected() throws Exception {
        JsonNode account = login(stubProfile());
        jdbc.sql("update refresh_tokens set expires_at = now() - interval '1 minute' where user_id = :id")
                .param("id", userId(account)).update();
        mvc.perform(renew(refresh(account))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(renew("unknown-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void agreementIsAppendOnlyReturnsLatestAndDefersLocationWithdrawal() throws Exception {
        JsonNode account = login(stubProfile());
        mvc.perform(agreement(account, "TERMS", "unknown", "AGREE")).andExpect(status().isBadRequest());
        mvc.perform(agreement(account, "TERMS", "terms-v1", "AGREE")).andExpect(status().isCreated());
        mvc.perform(agreement(account, "TERMS", "terms-v1", "WITHDRAW")).andExpect(status().isCreated());
        mvc.perform(agreement(account, "LOCATION_SERVICE", "location-v1", "AGREE")).andExpect(status().isCreated());
        mvc.perform(agreement(account, "LOCATION_SERVICE", "location-v1", "WITHDRAW"))
                .andExpect(status().isServiceUnavailable());
        assertThat(count("user_agreements", userId(account))).isEqualTo(3);
        mvc.perform(get(BASE + "/me").header("Authorization", bearer(account)))
                .andExpect(jsonPath("$.data.agreements.length()").value(2))
                .andExpect(jsonPath("$.data.agreements[0].action").value("WITHDRAW"))
                .andExpect(jsonPath("$.data.agreements[1].action").value("AGREE"));
    }

    @Test
    void settingsPatchIsPartialAndProtected() throws Exception {
        JsonNode account = login(stubProfile());
        mvc.perform(patch(BASE + "/notification-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nightEnabled\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(settingsPatch(account, "{\"nightEnabled\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cautionEnabled").value(true)).andExpect(jsonPath("$.data.nightEnabled").value(true));
        for (String body : new String[]{"{\"dangerEnabled\":true}", "{\"nightEnabled\":null}",
                "{\"cautionEnabled\":\"false\"}", "{\"userId\":\"other\"}"}) {
            mvc.perform(settingsPatch(account, body)).andExpect(status().isBadRequest());
        }
        mvc.perform(settingsPatch(account, "{\"cautionEnabled\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cautionEnabled").value(false)).andExpect(jsonPath("$.data.nightEnabled").value(true));
    }

    @Test
    void withdrawCascadesEvenIfProviderUnlinkFailsAndRejectsTheOldAccessToken() throws Exception {
        long kakaoId = stubProfile();
        JsonNode account = login(kakaoId);
        UUID id = userId(account);
        mvc.perform(agreement(account, "TERMS", "terms-v1", "AGREE")).andExpect(status().isCreated());
        UUID vehicle = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID assessment = UUID.randomUUID();
        UUID reason = UUID.randomUUID();
        jdbc.sql("""
                insert into vehicles(id, user_id, name, bluetooth_address)
                values (:vehicle, :user, 'test', 'AA:BB:CC:DD:EE:FF')
                """).param("vehicle", vehicle).param("user", id).update();
        jdbc.sql("""
                insert into parking_events(id, user_id, vehicle_id, client_event_id, request_hash, vehicle_name_snapshot,
                    detected_at, confirmed_at, original_location, confirmed_location, confirmation_method)
                values (:event, :user, :vehicle, gen_random_uuid(), 'test', 'test', now(), now(),
                    'SRID=4326;POINT(127 37)', 'SRID=4326;POINT(127 37)', 'CONFIRMED')
                """).param("event", event).param("user", id).param("vehicle", vehicle).update();
        jdbc.sql("insert into risk_assessments(id, parking_event_id, status) values (:id, :event, 'FAILED')")
                .param("id", assessment).param("event", event).update();
        jdbc.sql("""
                insert into assessment_reasons(id, assessment_id, rank, reason_type, description)
                values (:id, :assessment, 1, 'MODEL', 'test')
                """).param("id", reason).param("assessment", assessment).update();
        jdbc.sql("insert into location_access_logs(user_id, parking_event_id, action, purpose) values (:id, :event, 'USE', 'test')")
                .param("id", id).param("event", event).update();
        mvc.perform(get(BASE + "/me").header("Authorization", bearer(account)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasVehicle").value(true));
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(kakao).unlink(kakaoId);
        mvc.perform(delete(BASE + "/me").header("Authorization", bearer(account)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(kakao).unlink(kakaoId);
        assertThat(userCount(kakaoId)).isZero();
        for (String child : new String[]{"notification_settings", "refresh_tokens", "user_agreements", "location_access_logs", "vehicles", "parking_events"}) {
            assertThat(count(child, id)).isZero();
        }
        assertThat(jdbc.sql("select count(*) from risk_assessments where id = :id")
                .param("id", assessment).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from assessment_reasons where id = :id")
                .param("id", reason).query(Integer.class).single()).isZero();
        mvc.perform(get(BASE + "/me").header("Authorization", bearer(account))).andExpect(status().isUnauthorized());
    }

    @Test
    void webhookUsesSeparateAuthChecksAppIgnoresDuplicatesAndNeverCallsProviderUnlink() throws Exception {
        long kakaoId = stubProfile();
        login(kakaoId);
        mvc.perform(webhook(7, kakaoId, "Bearer mock-admin")).andExpect(status().isUnauthorized());
        mvc.perform(webhook(8, kakaoId, "KakaoAK mock-admin")).andExpect(status().isOk());
        assertThat(userCount(kakaoId)).isEqualTo(1);
        mvc.perform(webhook(7, kakaoId, "KakaoAK mock-admin")).andExpect(status().isOk());
        mvc.perform(webhook(7, kakaoId, "KakaoAK mock-admin")).andExpect(status().isOk());
        assertThat(userCount(kakaoId)).isZero();
        verify(kakao, never()).unlink(anyLong());
    }

    @Test
    void webhookReturns200WithinDeadlineWhenUserRowIsLockedAndCanBeRetried() throws Exception {
        long kakaoId = stubProfile();
        var login = login(kakaoId);
        try (var lock = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try {
                try (var statement = lock.prepareStatement("select id from users where id = ? for update")) {
                    statement.setObject(1, userId(login));
                    try (var row = statement.executeQuery()) {
                        assertThat(row.next()).isTrue();
                    }
                }
                var callback = executor.submit(() -> mvc.perform(webhook(7, kakaoId, "KakaoAK mock-admin"))
                        .andExpect(status().isOk()).andReturn());
                callback.get(3, TimeUnit.SECONDS);
                assertThat(userCount(kakaoId)).isEqualTo(1);
            } finally {
                lock.rollback();
            }
        }
        mvc.perform(webhook(7, kakaoId, "KakaoAK mock-admin")).andExpect(status().isOk());
        assertThat(userCount(kakaoId)).isZero();
    }

    @Test
    void databaseErrorOmitsSensitiveFailingRowDetail() {
        String marker = "postgres-private-detail@example.test";
        assertThatThrownBy(() -> jdbc.sql("insert into users(id, kakao_id, email) values (null, 99517, :email)")
                .param("email", marker).update())
                .isInstanceOfSatisfying(DataAccessException.class, failure -> {
                    assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, cause -> {
                        assertThat(cause.getSQLState()).isEqualTo("23502");
                        assertThat(cause.getMessage()).doesNotContain(marker, "Failing row");
                    });
                });
    }

    @Test
    void locationLogsRemainAppendOnlyAndDetachWhenEventIsDeleted() throws Exception {
        var account = login(stubProfile());
        UUID userId = userId(account);
        UUID vehicle = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.sql("""
                insert into vehicles(id, user_id, name, bluetooth_address)
                values (:id, :user, 'test', 'AA:BB:CC:DD:EE:FF')
                """).param("id", vehicle).param("user", userId).update();
        jdbc.sql("""
                insert into parking_events(id, user_id, vehicle_id, client_event_id, request_hash, vehicle_name_snapshot,
                    detected_at, confirmed_at, confirmed_location, confirmation_method)
                values (:id, :user, :vehicle, gen_random_uuid(), 'test', 'test', now(), now(),
                    'SRID=4326;POINT(127 37)', 'CONFIRMED')
                """).param("id", event).param("user", userId).param("vehicle", vehicle).update();
        jdbc.sql("insert into location_access_logs(user_id, parking_event_id, action, purpose) values (:user, :event, 'USE', 'test')")
                .param("user", userId).param("event", event).update();
        assertThatThrownBy(() -> jdbc.sql("delete from location_access_logs where user_id = :id")
                .param("id", userId).update()).isInstanceOfSatisfying(DataAccessException.class,
                e -> assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo("42501")));
        jdbc.sql("delete from parking_events where id = :id").param("id", event).update();
        assertThat(jdbc.sql("select parking_event_id is null from location_access_logs where user_id = :id")
                .param("id", userId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void locationReaderUserLockConflictsWithWithdrawalUserLock() throws Exception {
        var account = login(stubProfile());
        try (var reader = dataSource.getConnection(); var writer = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            try (var share = reader.prepareStatement("select id from users where id = ? for share");
                    var exclusive = writer.prepareStatement("select id from users where id = ? for update");
                    var timeout = writer.createStatement()) {
                share.setObject(1, userId(account));
                exclusive.setObject(1, userId(account));
                timeout.execute("set local lock_timeout = '250ms'");
                try (var row = share.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
                assertThatThrownBy(exclusive::executeQuery).isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo("55P03"));
            } finally {
                writer.rollback();
                reader.rollback();
            }
        }
    }

    @Test
    void settingsAndAgreementReadsAreScopedToTheAuthenticatedUser() throws Exception {
        JsonNode first = login(stubProfile());
        JsonNode second = login(stubProfile());
        mvc.perform(settingsPatch(first, "{\"nightEnabled\":true}")).andExpect(status().isOk());
        mvc.perform(agreement(first, "TERMS", "terms-v1", "AGREE")).andExpect(status().isCreated());
        mvc.perform(get(BASE + "/notification-settings").header("Authorization", bearer(second)).param("userId", userId(first).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nightEnabled").value(false));
        mvc.perform(get(BASE + "/me").header("Authorization", bearer(second)).param("userId", userId(first).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(userId(second).toString()))
                .andExpect(jsonPath("$.data.agreements").isEmpty());
    }

    private long stubProfile() {
        long kakaoId = KAKAO_IDS.incrementAndGet();
        doReturn(new KakaoClient.Profile(kakaoId, "sample", null, "sample@example.test"))
                .when(kakao).verify(kakaoToken(kakaoId));
        return kakaoId;
    }

    private JsonNode login(long kakaoId) throws Exception {
        return data(mvc.perform(post(BASE + "/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(kakaoId))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString());
    }

    private static String loginJson(long kakaoId) {
        return "{\"kakaoAccessToken\":\"" + kakaoToken(kakaoId) + "\",\"deviceInfo\":\"test-device\"}";
    }

    private static String kakaoToken(long kakaoId) { return "mock-token-" + kakaoId; }
    private JsonNode data(String body) { return json.readTree(body).path("data"); }
    private static UUID userId(JsonNode login) { return UUID.fromString(login.at("/user/id").asString()); }
    private static String refresh(JsonNode token) { return token.path("refreshToken").asString(); }
    private static String bearer(JsonNode token) { return "Bearer " + token.path("accessToken").asString(); }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder renew(String token) {
        return post(BASE + "/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(tokenBody(token));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder logout(JsonNode account, String token) {
        return post(BASE + "/auth/logout").header("Authorization", bearer(account))
                .contentType(MediaType.APPLICATION_JSON).content(tokenBody(token));
    }

    private static String tokenBody(String token) { return "{\"refreshToken\":\"" + token + "\"}"; }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder agreement(
            JsonNode account, String type, String version, String action) {
        return post(BASE + "/me/agreements").header("Authorization", bearer(account))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\",\"version\":\"%s\",\"action\":\"%s\"}".formatted(type, version, action));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsPatch(JsonNode account, String body) {
        return patch(BASE + "/notification-settings").header("Authorization", bearer(account))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(long appId, long userId, String auth) {
        return post(BASE + "/webhooks/kakao/unlink").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("app_id=%d&user_id=%d&referrer_type=ACCOUNT_DELETE".formatted(appId, userId));
    }

    private int count(String table, UUID id) {
        return jdbc.sql("select count(*) from " + table + " where user_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private int userCount(long kakaoId) {
        return jdbc.sql("select count(*) from users where kakao_id = :id").param("id", kakaoId).query(Integer.class).single();
    }

    private int activeTokens(UUID id) {
        return jdbc.sql("select count(*) from refresh_tokens where user_id = :id and revoked_at is null")
                .param("id", id).query(Integer.class).single();
    }

    private static <T> java.util.List<T> concurrently(Callable<T> task) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> simultaneous = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test start timed out");
                return task.call();
            };
            var first = executor.submit(simultaneous);
            var second = executor.submit(simultaneous);
            start.countDown();
            return java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }
}
