package io.github.yongyong14.parking_server.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
class KakaoClient {
    private final RestClient http;
    private final long appId;
    private final String adminKey;

    @Autowired
    KakaoClient(@Value("${app.account.kakao-app-id:0}") long appId,
            @Value("${app.account.kakao-admin-key:}") String adminKey) {
        this(kakaoHttp(), appId, adminKey);
    }

    private static RestClient kakaoHttp() {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl("https://kapi.kakao.com")
                .requestFactory(requestFactory).build();
    }

    KakaoClient(RestClient http, long appId, String adminKey) {
        this.http = http;
        this.appId = appId;
        this.adminKey = adminKey;
    }

    Profile verify(String token) {
        if (appId <= 0) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        try {
            var info = retrieveOk(http.get().uri("/v1/user/access_token_info")
                    .headers(h -> h.setBearerAuth(token)), TokenInfo.class);
            if (info == null || info.appId() != appId || info.id() <= 0 || info.expiresIn() <= 0) {
                throw new BusinessException(ErrorCode.KAKAO_TOKEN_INVALID);
            }
            var user = retrieveOk(http.get().uri("/v2/user/me")
                    .headers(h -> h.setBearerAuth(token)), KakaoUser.class);
            if (user == null || user.id() != info.id()) throw new BusinessException(ErrorCode.KAKAO_TOKEN_INVALID);

            var account = user.account();
            var profile = account == null ? null : account.profile();
            return new Profile(user.id(),
                    profile == null || Boolean.TRUE.equals(account.nicknameNeedsAgreement()) ? null : profile.nickname(),
                    profile == null || Boolean.TRUE.equals(account.imageNeedsAgreement()) ? null : profile.imageUrl(),
                    account == null || Boolean.TRUE.equals(account.emailNeedsAgreement()) ? null : account.email());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || malformedToken(e)) {
                throw new BusinessException(ErrorCode.KAKAO_TOKEN_INVALID);
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    void unlink(long userId) {
        if (adminKey.isBlank()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("target_id_type", "user_id");
        form.add("target_id", Long.toString(userId));
        try {
            var result = retrieveOk(http.post().uri("/v1/user/unlink")
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form), UnlinkedUser.class);
            if (result == null || result.id() != userId) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static <T> T retrieveOk(RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        return request.retrieve()
                .onStatus(status -> status.value() != HttpStatus.OK.value() && !status.isError(),
                        (ignored, response) -> { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); })
                .body(type);
    }

    private static boolean malformedToken(RestClientResponseException failure) {
        if (failure.getStatusCode().value() != HttpStatus.BAD_REQUEST.value()) return false;
        try {
            var error = failure.getResponseBodyAs(KakaoError.class);
            return error != null && error.code() == -2;
        } catch (RestClientException | IllegalStateException ignored) {
            return false;
        }
    }

    boolean authenticateWebhook(String authorization, long callbackAppId) {
        if (appId <= 0 || adminKey.isBlank()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        byte[] expected = ("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return appId == callbackAppId;
    }

    record Profile(long id, String nickname, String profileImageUrl, String email) {
        @Override public String toString() { return "Profile[redacted]"; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenInfo(long id, @JsonProperty("app_id") long appId,
            @JsonProperty("expires_in") int expiresIn) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoUser(long id, @JsonProperty("kakao_account") KakaoAccount account) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoAccount(KakaoProfile profile, String email,
            @JsonProperty("profile_nickname_needs_agreement") Boolean nicknameNeedsAgreement,
            @JsonProperty("profile_image_needs_agreement") Boolean imageNeedsAgreement,
            @JsonProperty("email_needs_agreement") Boolean emailNeedsAgreement) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoProfile(String nickname, @JsonProperty("profile_image_url") String imageUrl) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UnlinkedUser(long id) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoError(int code) {}
}
