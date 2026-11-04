package io.github.yongyong14.parking_server.global.security;

import io.github.yongyong14.parking_server.global.exception.*;
import io.jsonwebtoken.*;
import javax.crypto.SecretKey;
import java.time.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccessTokenServiceTests {
    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final UUID id = UUID.randomUUID();
    private TokenSettings settings(String secret) {
        return new TokenSettings("parking", secret, Duration.ofMinutes(30), Duration.ofDays(30));
    }
    private AccessTokenService service(Instant time) {
        return new AccessTokenService(settings(Base64.getEncoder().encodeToString(key.getEncoded())), Clock.fixed(time, ZoneOffset.UTC));
    }
    private JwtBuilder builder() {
        return Jwts.builder().issuer("parking").subject(id.toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(1800))).claim("token_type", "access");
    }
    private void unauthorized(String token) {
        assertThatThrownBy(() -> service(now).verify(token)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }
    @Test void roundTripAndExactExpiration() {
        var issued = service(now).issue(id);
        assertThat(issued.expiresIn()).isEqualTo(1800);
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(1800));
        assertThat(service(now).verify(issued.accessToken())).isEqualTo(id);
        assertThat(service(now.plusSeconds(1799)).verify(issued.accessToken())).isEqualTo(id);
        assertThatThrownBy(() -> service(now.plusSeconds(1800)).verify(issued.accessToken()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_EXPIRED));
        assertThat(issued.toString()).doesNotContain(issued.accessToken());
    }
    @Test void rejectsWrongClaimsMissingClaimsAndRefresh() {
        for (String claim : List.of("iss", "sub", "exp", "iat", "token_type")) {
            var b = builder(); b.claim(claim, null); unauthorized(b.signWith(key, Jwts.SIG.HS256).compact());
        }
        unauthorized(builder().issuer("other").signWith(key).compact());
        unauthorized(builder().subject("not-uuid").signWith(key).compact());
        unauthorized(builder().subject("1-1-1-1-1").signWith(key).compact());
        unauthorized(builder().claim("token_type", "refresh").signWith(key).compact());
        unauthorized(builder().issuedAt(Date.from(now.plusSeconds(1))).signWith(key).compact());
        unauthorized("opaque-refresh-token"); unauthorized(""); unauthorized(null);
    }
    @Test void rejectsForgeryUnsignedAndOtherAlgorithms() {
        unauthorized(builder().signWith(Jwts.SIG.HS256.key().build()).compact());
        unauthorized(builder().compact());
        var longer = Jwts.SIG.HS512.key().build();
        var verifier = new AccessTokenService(settings(Base64.getEncoder().encodeToString(longer.getEncoded())), Clock.fixed(now, ZoneOffset.UTC));
        var token = builder().signWith(longer, Jwts.SIG.HS512).compact();
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        unauthorized(builder().issuer("other").expiration(Date.from(now.minusSeconds(1))).signWith(key).compact());
    }
    @Test void rejectsMissingWeakOrMalformedConfiguration() {
        assertThatThrownBy(() -> settings(null)).isInstanceOf(IllegalArgumentException.class);
        for (String secret : List.of("not-base64!", Base64.getEncoder().encodeToString(new byte[32]), "Y2hhbmdlLW1l"))
            assertThatThrownBy(() -> new AccessTokenService(settings(secret), Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenSettings("", "abc", Duration.ZERO, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
