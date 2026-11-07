package io.github.yongyong14.parking_server.security;

import io.github.yongyong14.parking_server.global.security.JdbcUserExistence;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class UserExistencePostgresTest {
    @Test void realSchemaLookupRejectsHardDeletedUsersAndPropagatesDatabaseFailures() throws Exception {
        String url = required("AUTH_TEST_JDBC_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/auth_verify"))
            throw new IllegalStateException("Expected isolated authentication database");
        var source = new DriverManagerDataSource(url, "auth_tester", required("AUTH_TEST_PASSWORD"));
        var jdbc = JdbcClient.create(source);
        var identity = jdbc.sql("SELECT current_database() || ':' || current_user || ':' || current_setting('verification.identity', true)")
                .query(String.class).single();
        assertThat(identity).isEqualTo("auth_verify:auth_tester:" + required("AUTH_TEST_IDENTITY"));
        // Execute only the actual users DDL; unrelated migrations and DB suites are unnecessary.
        String migration = new ClassPathResource("db/migration/V2__erd_v5_schema.sql").getContentAsString(StandardCharsets.UTF_8);
        int start = migration.indexOf("CREATE TABLE \"users\"");
        if (start < 0) throw new IllegalStateException("Actual users migration was not found");
        jdbc.sql(migration.substring(start, migration.indexOf(';', start) + 1)).update();
        var users = new JdbcUserExistence(jdbc);
        UUID id = UUID.randomUUID();
        assertThat(users.exists(id)).isFalse();
        jdbc.sql("INSERT INTO public.users(id, kakao_id) VALUES (:id, 1)").param("id", id).update();
        assertThat(users.exists(id)).isTrue();
        jdbc.sql("DELETE FROM public.users WHERE id = :id").param("id", id).update();
        assertThat(users.exists(id)).isFalse();
        jdbc.sql("DROP TABLE public.users").update();
        assertThatThrownBy(() -> users.exists(id)).isInstanceOf(DataAccessException.class);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Run scripts/db/Verify-Authentication.ps1; missing " + name);
        return value;
    }
}
