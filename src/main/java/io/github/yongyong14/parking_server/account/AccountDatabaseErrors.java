package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = AuthController.class)
class AccountDatabaseErrors {
    private static final Logger log = LoggerFactory.getLogger(AccountDatabaseErrors.class);

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiResponse<Void>> databaseFailure() {
        log.error("Account database operation failed");
        var code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.failure(code.getCode(), code.getMessage()));
    }
}
