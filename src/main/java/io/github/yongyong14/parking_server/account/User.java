package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class User extends BaseTimeEntity {
    @Id
    private UUID id;
    private long kakaoId;
    private String nickname;
    private String profileImageUrl;
    private String email;
    private Instant lastLoginAt;

    void recordLogin(KakaoClient.Profile profile, Instant now) {
        nickname = profile.nickname();
        profileImageUrl = profile.profileImageUrl();
        email = profile.email();
        lastLoginAt = now;
    }
}
