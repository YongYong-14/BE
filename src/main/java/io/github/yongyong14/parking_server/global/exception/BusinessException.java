package io.github.yongyong14.parking_server.global.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    // The custom message must be safe to expose to clients.
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, errorCode.getMessage(), cause);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(Objects.requireNonNull(message), cause);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
