package io.github.yongyong14.parking_server.global.security;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.jsonwebtoken.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

public final class AccessTokenService {
    private final TokenSettings settings;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;

    public AccessTokenService(TokenSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(settings.secretBase64()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("JWT signing key must be Base64 encoded"); }
        if (bytes.length < 32 || java.util.stream.IntStream.range(0, bytes.length)
                .map(i -> bytes[i] & 255).distinct().count() < 16) {
            throw new IllegalArgumentException("JWT signing key requires at least 32 random bytes");
        }
        key = new SecretKeySpec(bytes, "HmacSHA256");
        Arrays.fill(bytes, (byte) 0);
        parser = Jwts.parser().verifyWith(key).requireIssuer(settings.issuer()).require("token_type", "access")
                .clock(() -> Date.from(clock.instant())).sig().clear().add(Jwts.SIG.HS256).and().build();
    }

    public IssuedAccessToken issue(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(settings.accessTtl());
        String token = Jwts.builder().issuer(settings.issuer()).subject(userId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(expiresAt)).claim("token_type", "access")
                .signWith(key, Jwts.SIG.HS256).compact();
        return new IssuedAccessToken(token, settings.accessTtl().toSeconds(), expiresAt);
    }

    public UUID verify(String token) {
        try {
            Claims claims;
            boolean expired = false;
            try { claims = parser.parseSignedClaims(token).getPayload(); }
            catch (ExpiredJwtException e) { claims = e.getClaims(); expired = true; }
            // Validate the entire contract even when expiration was the parser's first failure.
            if (!settings.issuer().equals(claims.getIssuer()) || !"access".equals(claims.get("token_type", String.class))
                    || claims.getExpiration() == null || claims.getIssuedAt() == null
                    || !claims.getExpiration().after(claims.getIssuedAt())
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant())) throw new IllegalArgumentException();
            String subject = claims.getSubject();
            UUID id = UUID.fromString(subject);
            if (!id.toString().equals(subject)) throw new IllegalArgumentException();
            if (expired || !claims.getExpiration().toInstant().isAfter(clock.instant()))
                throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
            return id;
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public record IssuedAccessToken(String accessToken, long expiresIn, Instant expiresAt) {
        @Override public String toString() { return "IssuedAccessToken[redacted]"; }
    }
}
