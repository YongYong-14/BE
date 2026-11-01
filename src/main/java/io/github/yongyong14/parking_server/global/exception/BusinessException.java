package io.github.yongyong14.parking_server.global.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    // The custom message must be safe to expose to clients.
    public BusinessException(ErrorCode errorCode, String message) {
        super(Objects.requireNonNull(message));
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
