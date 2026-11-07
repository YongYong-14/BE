package io.github.yongyong14.parking_server.global.security;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ERD v5.1 uses hard deletion; no account entity ownership is introduced here. */
public final class JdbcUserExistence implements UserExistence {
    private final JdbcClient jdbc;
    public JdbcUserExistence(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public boolean exists(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM public.users WHERE id = :id)")
                .param("id", userId).query(Boolean.class).single();
    }
}
