package io.github.yongyong14.parking_server.global.exception;

import io.github.yongyong14.parking_server.global.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.getErrorCode();
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code.getCode(), exception.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Spring MVC request processing failed", exception);
        }
        ErrorCode code = ErrorCode.forStatus(status.value());
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception,
                ApiResponse.failure(code.getCode(), code.getMessage()), responseHeaders, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception) {
        log.error("Unexpected request processing failure", exception);
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code.getCode(), code.getMessage()));
    }
}
