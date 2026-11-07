package io.github.yongyong14.parking_server.global.security;

import java.util.UUID;

@FunctionalInterface
public interface UserExistence {
    boolean exists(UUID userId);
}
