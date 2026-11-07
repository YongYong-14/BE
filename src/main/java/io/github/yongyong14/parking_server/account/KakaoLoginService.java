package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class KakaoLoginService {
    private final UserRepository users;
    private final NotificationSettingsRepository notifications;
    private final RefreshTokenService tokens;
    private final AgreementService agreements;
    private final Clock clock;

    KakaoLoginService(UserRepository users, NotificationSettingsRepository notifications, RefreshTokenService tokens,
            AgreementService agreements, Clock clock) {
        this.users = users;
        this.notifications = notifications;
        this.tokens = tokens;
        this.agreements = agreements;
        this.clock = clock;
    }

    @Transactional
    public AccountApi.LoginResponse login(KakaoClient.Profile profile, String deviceInfo) {
        boolean isNew = users.insertIfAbsent(UUID.randomUUID(), profile.id()) == 1;
        User user = users.lockByKakaoId(profile.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        user.recordLogin(profile, clock.instant());
        UUID id = user.getId();
        if (isNew || !notifications.existsById(id)) notifications.save(new NotificationSettings(id));
        var token = tokens.start(id, deviceInfo);
        return new AccountApi.LoginResponse(token.accessToken(), token.refreshToken(), token.expiresIn(), isNew,
                new AccountApi.LoginUser(id, user.getNickname(), user.getProfileImageUrl()), agreements.missing(id));
    }
}
