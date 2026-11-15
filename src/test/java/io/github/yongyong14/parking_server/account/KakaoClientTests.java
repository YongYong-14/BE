package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoClientTests {
    private static final String HOST = "https://kapi.kakao.com";
    private static final String TOKEN_INFO = HOST + "/v1/user/access_token_info";
    private final RestClient.Builder builder = RestClient.builder().baseUrl(HOST);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KakaoClient client = new KakaoClient(builder.build(), 7, "mock-admin");

    @Test
    void verifiesAppAndUserIdsAndOnlyCopiesAvailableProfileFields() {
        server.expect(requestTo(TOKEN_INFO)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer mock-token"))
                .andRespond(withSuccess("""
                        {"id":123,"app_id":7,"expires_in":400,"future_field":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOST + "/v2/user/me"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer mock-token"))
                .andRespond(withSuccess("""
                        {"id":123,"kakao_account":{"profile":{"nickname":"test","profile_image_url":"https://example.test/p"},
                        "profile_image_needs_agreement":true,"email_needs_agreement":true,"email":"masked@example.test"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.verify("mock-token")).isEqualTo(new KakaoClient.Profile(123, "test", null, null));
        server.verify();
    }

    @Test
    void rejectsOtherAppBeforeLookingUpProfile() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withSuccess("""
                {"id":123,"app_id":8,"expires_in":400}
                """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.verify("mock-token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KAKAO_TOKEN_INVALID));
        server.verify();
    }

    @Test
    void mapsInvalidKakaoTokenTo401() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.verify("mock-token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KAKAO_TOKEN_INVALID));
        server.verify();
    }

    @Test
    void mapsProviderFailureWithoutExposingProviderBody() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withServerError().body("provider detail"));
        assertThatThrownBy(() -> client.verify("mock-token")).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(e).hasMessageNotContaining("provider detail");
        });
        server.verify();
    }

    @Test
    void rejectsRedirectWithAnOtherwiseValidTokenBody() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withStatus(HttpStatus.FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("""
                        {"id":123,"app_id":7,"expires_in":400}
                        """));
        assertThatThrownBy(() -> client.verify("mock-token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        server.verify();
    }

    @Test
    void distinguishesMalformedTokenFromOtherKakao400Errors() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":-2,\"msg\":\"invalid token format\"}"));
        server.expect(requestTo(TOKEN_INFO)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":-1,\"msg\":\"temporary error\"}"));
        server.expect(requestTo(TOKEN_INFO)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("invalid JSON"));
        assertThatThrownBy(() -> client.verify("mock-token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KAKAO_TOKEN_INVALID));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> client.verify("mock-token")).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }
        server.verify();
    }

    @Test
    void rejectsUserInfoWhoseIdDoesNotMatchVerifiedToken() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withSuccess("""
                {"id":123,"app_id":7,"expires_in":400}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOST + "/v2/user/me"))
                .andRespond(withSuccess("{\"id\":124}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.verify("mock-token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KAKAO_TOKEN_INVALID));
        server.verify();
    }

    @Test
    void unlinksByKakaoIdWithAdminHeaderAndEncodedForm() {
        server.expect(requestTo(HOST + "/v1/user/unlink")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK mock-admin"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("target_id_type=user_id&target_id=123"))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));
        client.unlink(123);
        server.verify();
    }

    @Test
    void webhookRequiresPrimaryKeyAndMatchesApplication() {
        assertThatThrownBy(() -> client.authenticateWebhook("Bearer mock-admin", 7))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThat(client.authenticateWebhook("KakaoAK mock-admin", 8)).isFalse();
        assertThat(client.authenticateWebhook("KakaoAK mock-admin", 7)).isTrue();
    }
}
