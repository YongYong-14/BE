package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/kakao")
class KakaoWebhookController {
    private static final Logger log = LoggerFactory.getLogger(KakaoWebhookController.class);
    private final KakaoClient kakao;
    private final AccountService accounts;

    KakaoWebhookController(KakaoClient kakao, AccountService accounts) {
        this.kakao = kakao;
        this.accounts = accounts;
    }

    @Operation(summary = "카카오 연결 해제 웹훅", description = "KakaoAK Primary Admin Key 인증 및 폼 본문 필요")
    @PostMapping(path = "/unlink", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> unlink(HttpServletRequest request, @RequestBody MultiValueMap<String, String> form) {
        var headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (headers.size() != 1) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (!kakao.authenticateWebhook(headers.getFirst(), positiveId(field(form, "app_id")))) {
            return ResponseEntity.ok().build();
        }
        long userId = positiveId(field(form, "user_id"));
        field(form, "referrer_type");
        try {
            accounts.deleteByKakaoId(userId);
        } catch (DataAccessException | TransactionException e) {
            // Kakao requires 200 for authenticated callbacks even when internal processing fails.
            // A sanitized error is observable without logging the admin key, Kakao ID or SQL values.
            log.error("Authenticated Kakao unlink webhook could not delete the account");
        }
        return ResponseEntity.ok().build();
    }

    private static String field(MultiValueMap<String, String> form, String name) {
        var values = form.get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return values.getFirst();
    }

    private static long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // The supplied value is not returned in the response.
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
}
