package io.github.yongyong14.parking_server.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT("INVALID_INPUT", HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    UNAUTHORIZED("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    KAKAO_TOKEN_INVALID("KAKAO_TOKEN_INVALID", HttpStatus.UNAUTHORIZED, "카카오 인증 토큰을 확인해 주세요."),
    TOKEN_REUSED("TOKEN_REUSED", HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    TOKEN_EXPIRED("TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    FORBIDDEN("FORBIDDEN", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND("NOT_FOUND", HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE("NOT_ACCEPTABLE", HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식입니다."),
    CONFLICT("CONFLICT", HttpStatus.CONFLICT, "요청이 현재 리소스 상태와 충돌합니다."),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE, "요청 크기가 허용 범위를 초과했습니다."),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    REQUEST_REJECTED("REQUEST_REJECTED", HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다."),
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;

    ErrorCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }

    /** Maps the response code/message only; callers must preserve the actual MVC status.
     * Fallback defaults (400/500) apply to BusinessException, not to MVC responses.
     */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_INPUT;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_SERVER_ERROR : REQUEST_REJECTED;
        };
    }
}
