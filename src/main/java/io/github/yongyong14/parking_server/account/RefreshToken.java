package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.entity.BaseCreatedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class RefreshToken extends BaseCreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID userId;
    private String tokenHash;
    private String deviceInfo;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
    private UUID familyId;
    private UUID replacedById;

    RefreshToken(UUID userId, UUID familyId, String tokenHash, String deviceInfo, Instant expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.deviceInfo = deviceInfo;
        this.expiresAt = expiresAt;
    }

    void replaceWith(UUID successorId, Instant now) {
        lastUsedAt = now;
        revokedAt = now;
        replacedById = successorId;
    }
}
