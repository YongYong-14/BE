package io.github.yongyong14.parking_server.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record TokenSettings(String issuer, String secretBase64, Duration accessTtl, Duration refreshTtl) {
    public TokenSettings {
        if (issuer == null || issuer.isBlank() || secretBase64 == null || secretBase64.isBlank()
                || accessTtl == null || accessTtl.isNegative() || accessTtl.isZero() || accessTtl.getNano() != 0
                || refreshTtl == null || refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalArgumentException("Required authentication configuration is missing or invalid");
        }
    }
    // Deliberately exclude signing material from diagnostics.
    @Override public String toString() { return "TokenSettings[redacted]"; }
}
