package io.github.yongyong14.parking_server.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AccountApi {
    private AccountApi() {}

    record KakaoLoginRequest(
            @NotBlank @Size(max = 4096) @Pattern(regexp = "[!-~]+") String kakaoAccessToken,
            @Size(max = 200) String deviceInfo) {
        @Override public String toString() { return "KakaoLoginRequest[redacted]"; }
    }

    record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
        @Override public String toString() { return "RefreshRequest[redacted]"; }
    }

    record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }

    record LoginResponse(String accessToken, String refreshToken, long expiresIn, boolean isNewUser,
            LoginUser user, MissingAgreements agreements) {
        @Override public String toString() { return "LoginResponse[redacted]"; }
    }

    record LoginUser(UUID id, String nickname, String profileImageUrl) {}
    record MissingAgreements(List<UserAgreement.Type> missing) {}
    record Me(UUID id, String nickname, String email, boolean hasVehicle, List<Agreement> agreements) {}
    record Agreement(UserAgreement.Type type, String version, UserAgreement.Action action, Instant occurredAt) {
        static Agreement from(UserAgreement row) {
            return new Agreement(row.getAgreementType(), row.getVersion(), row.getAction(), row.getOccurredAt());
        }
    }
    record AgreementRequest(@NotNull UserAgreement.Type type, @NotBlank @Size(max = 20) String version,
            @NotNull UserAgreement.Action action) {}
    record Settings(boolean dangerEnabled, boolean cautionEnabled, boolean nightEnabled) {
        static Settings from(NotificationSettings row) {
            return new Settings(true, row.isCautionEnabled(), row.isNightEnabled());
        }
    }
}
