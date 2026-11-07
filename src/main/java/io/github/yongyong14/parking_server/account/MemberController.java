package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import io.github.yongyong14.parking_server.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
class MemberController {
    private static final Logger log = LoggerFactory.getLogger(MemberController.class);
    private final AccountService accounts;
    private final AgreementService agreements;
    private final KakaoClient kakao;

    MemberController(AccountService accounts, AgreementService agreements, KakaoClient kakao) {
        this.accounts = accounts;
        this.agreements = agreements;
        this.kakao = kakao;
    }

    @Operation(summary = "내 회원 정보와 최신 약관 이력")
    @GetMapping("/me")
    ApiResponse<AccountApi.Me> me(@CurrentUser UUID userId) {
        return ApiResponse.success(accounts.me(userId));
    }

    @Operation(summary = "약관 동의 또는 철회 기록")
    @PostMapping("/me/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AccountApi.Agreement> agreement(@CurrentUser UUID userId,
            @Valid @RequestBody AccountApi.AgreementRequest request) {
        return ApiResponse.success(agreements.record(userId, request));
    }

    @Operation(summary = "카카오 연결 해제 및 회원 삭제")
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@CurrentUser UUID userId) {
        long kakaoId = accounts.kakaoId(userId);
        try {
            kakao.unlink(kakaoId);
        } catch (BusinessException e) {
            log.warn("Kakao unlink request failed during account deletion");
        }
        accounts.delete(userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 알림 설정 조회")
    @GetMapping("/notification-settings")
    ApiResponse<AccountApi.Settings> settings(@CurrentUser UUID userId) {
        return ApiResponse.success(accounts.settings(userId));
    }

    @Operation(summary = "내 주의·야간 알림 설정 부분 변경")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description =
            "cautionEnabled와 nightEnabled의 불리언 값만 전송할 수 있습니다. 생략한 필드는 유지합니다.")
    @PatchMapping("/notification-settings")
    ApiResponse<AccountApi.Settings> updateSettings(@CurrentUser UUID userId, @RequestBody JsonNode patch) {
        if (!patch.isObject()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        Boolean caution = null;
        Boolean night = null;
        for (var field : patch.properties()) {
            if (!field.getValue().isBoolean()) throw new BusinessException(ErrorCode.INVALID_INPUT);
            switch (field.getKey()) {
                case "cautionEnabled" -> caution = field.getValue().asBoolean();
                case "nightEnabled" -> night = field.getValue().asBoolean();
                default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
        return ApiResponse.success(accounts.updateSettings(userId, caution, night));
    }
}
