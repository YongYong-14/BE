package io.github.yongyong14.parking_server.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public String getCode() { return name(); }
    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }

    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_INPUT;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_SERVER_ERROR : REQUEST_REJECTED;
        };
    }
}
