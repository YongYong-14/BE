package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import io.github.yongyong14.parking_server.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final KakaoClient kakao;
    private final KakaoLoginService login;
    private final RefreshTokenService tokens;

    AuthController(KakaoClient kakao, KakaoLoginService login, RefreshTokenService tokens) {
        this.kakao = kakao;
        this.login = login;
        this.tokens = tokens;
    }

    @Operation(summary = "카카오 로그인")
    @PostMapping("/kakao")
    ApiResponse<AccountApi.LoginResponse> kakao(@Valid @RequestBody AccountApi.KakaoLoginRequest request) {
        var profile = kakao.verify(request.kakaoAccessToken());
        return ApiResponse.success(login.login(profile, request.deviceInfo()));
    }

    @Operation(summary = "리프레시 토큰 회전 및 토큰 재발급")
    @PostMapping("/refresh")
    ApiResponse<AccountApi.TokenResponse> refresh(@Valid @RequestBody AccountApi.RefreshRequest request) {
        return ApiResponse.success(tokens.rotate(request.refreshToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_REUSED)));
    }

    @Operation(summary = "현재 로그인 계열 로그아웃")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> logout(@CurrentUser UUID userId, @Valid @RequestBody AccountApi.RefreshRequest request) {
        tokens.logout(userId, request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
