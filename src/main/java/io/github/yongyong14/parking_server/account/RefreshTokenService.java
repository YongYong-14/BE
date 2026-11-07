package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.github.yongyong14.parking_server.global.security.AccessTokenService;
import io.github.yongyong14.parking_server.global.security.TokenSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RefreshTokenService {
    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final AccessTokenService accessTokens;
    private final TokenSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    RefreshTokenService(RefreshTokenRepository tokens, UserRepository users, AccessTokenService accessTokens,
            TokenSettings settings, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.accessTokens = accessTokens;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public AccountApi.TokenResponse start(UUID userId, String deviceInfo) {
        return issue(userId, UUID.randomUUID(), deviceInfo, clock.instant()).response();
    }

    @Transactional
    public Optional<AccountApi.TokenResponse> rotate(String raw) {
        String hash = hash(raw);
        UUID userId = tokens.findOwner(hash).orElseThrow(RefreshTokenService::unauthorized);

        // Lock the user first in both rotation and logout, including requests for different tokens
        // in the same family. Then read the latest token state under its row lock.
        users.lockById(userId).orElseThrow(RefreshTokenService::unauthorized);
        RefreshToken previous = tokens.lockByHash(hash, userId).orElseThrow(RefreshTokenService::unauthorized);
        Instant now = clock.instant();
        if (previous.getRevokedAt() != null) {
            tokens.revokeFamily(userId, previous.getFamilyId(), now);
            // Return normally so the transaction commits before the controller responds with 401.
            return Optional.empty();
        }
        if (!previous.getExpiresAt().isAfter(now)) throw unauthorized();

        Issued issued = issue(userId, previous.getFamilyId(), previous.getDeviceInfo(), now);
        previous.replaceWith(issued.id(), now);
        return Optional.of(issued.response());
    }

    @Transactional
    public void logout(UUID userId, String raw) {
        String hash = hash(raw);
        UUID owner = tokens.findOwner(hash).orElseThrow(RefreshTokenService::unauthorized);
        if (!owner.equals(userId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        users.lockById(userId).orElseThrow(RefreshTokenService::unauthorized);
        RefreshToken token = tokens.lockByHash(hash, userId).orElseThrow(RefreshTokenService::unauthorized);
        tokens.revokeFamily(userId, token.getFamilyId(), clock.instant());
    }

    private Issued issue(UUID userId, UUID familyId, String deviceInfo, Instant now) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        var row = tokens.saveAndFlush(new RefreshToken(userId, familyId, hash(raw), deviceInfo,
                now.plus(settings.refreshTtl())));
        var access = accessTokens.issue(userId);
        return new Issued(row.getId(), new AccountApi.TokenResponse(access.accessToken(), raw, access.expiresIn()));
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    private record Issued(UUID id, AccountApi.TokenResponse response) {}
}
