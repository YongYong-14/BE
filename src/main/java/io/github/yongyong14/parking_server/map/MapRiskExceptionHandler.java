package io.github.yongyong14.parking_server.map;

import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

/** Keep map inputs out of exception messages/stacks while preserving the common HTTP contract. */
@RestControllerAdvice(assignableTypes=MapRiskController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MapRiskExceptionHandler extends GlobalExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(MapRiskExceptionHandler.class);

    @Override
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
        return failure(exception,exception.getErrorCode().getStatus(),new HttpHeaders(),exception.getErrorCode());
    }

    @Override
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception) {
        return failure(exception,HttpStatus.INTERNAL_SERVER_ERROR,new HttpHeaders(),ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception,Object body,
            HttpHeaders headers,HttpStatusCode status,WebRequest request) {
        return failure(exception,status,headers,ErrorCode.forStatus(status.value()));
    }

    private ResponseEntity<Object> failure(Exception exception,HttpStatusCode status,HttpHeaders headers,ErrorCode code) {
        // Do not attach exception.toString(), message, cause or stack: DB errors can contain inputs.
        if(status.is5xxServerError()) log.error("Map request failed: status={}, code={}, exception={}",
                status.value(),code.getCode(),exception.getClass().getSimpleName());
        else log.debug("Map request rejected: status={}, code={}, exception={}",
                status.value(),code.getCode(),exception.getClass().getSimpleName());
        var responseHeaders=new HttpHeaders(); responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(status).headers(responseHeaders).body(ApiResponse.failure(code.getCode(),code.getMessage()));
    }
}
