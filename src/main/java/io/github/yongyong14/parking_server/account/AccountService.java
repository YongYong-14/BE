package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountService {
    private final UserRepository users;
    private final NotificationSettingsRepository notifications;
    private final AgreementService agreements;

    AccountService(UserRepository users, NotificationSettingsRepository notifications, AgreementService agreements) {
        this.users = users;
        this.notifications = notifications;
        this.agreements = agreements;
    }

    @Transactional(readOnly = true)
    public AccountApi.Me me(UUID id) {
        User user = users.findById(id).orElseThrow(AccountService::unauthorized);
        return new AccountApi.Me(id, user.getNickname(), user.getEmail(), users.hasActiveVehicle(id), agreements.latest(id));
    }

    @Transactional(readOnly = true)
    public AccountApi.Settings settings(UUID id) {
        return AccountApi.Settings.from(findSettings(id));
    }

    @Transactional
    public AccountApi.Settings updateSettings(UUID id, Boolean caution, Boolean night) {
        users.lockById(id).orElseThrow(AccountService::unauthorized);
        var row = findSettings(id);
        row.update(caution, night);
        return AccountApi.Settings.from(row);
    }

    private NotificationSettings findSettings(UUID id) {
        return notifications.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    long kakaoId(UUID id) {
        return users.findKakaoId(id).orElseThrow(AccountService::unauthorized);
    }

    @Transactional
    public void delete(UUID id) {
        users.lockById(id).ifPresent(users::delete);
    }

    // Reserve part of Kakao's three-second deadline for HTTP handling.
    @Transactional(timeout = 2)
    public void deleteByKakaoId(long id) {
        users.lockByKakaoId(id).ifPresent(users::delete);
    }

    private static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED);
    }
}
