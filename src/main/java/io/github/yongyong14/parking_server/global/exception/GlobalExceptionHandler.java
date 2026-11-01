package io.github.yongyong14.parking_server.global.exception;

import io.github.yongyong14.parking_server.global.response.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.getErrorCode();
        logFailure(exception, code.getStatus(), code);
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code.getCode(), exception.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode code = ErrorCode.forStatus(status.value());
        logFailure(exception, status, code);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception,
                ApiResponse.failure(code.getCode(), code.getMessage()), responseHeaders, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception,
            WebRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        // Only violations on the current controller's HTTP-bound parameters are client errors.
        // Service/entity validation, return values, mixed and unidentifiable violations stay 500.
        boolean inputError = handler instanceof HandlerMethod method
                && !exception.getConstraintViolations().isEmpty()
                && exception.getConstraintViolations().stream()
                    .allMatch(violation -> isRequestParameterViolation(violation, method));
        ErrorCode code = inputError ? ErrorCode.INVALID_INPUT : ErrorCode.INTERNAL_SERVER_ERROR;
        return handleExceptionInternal(exception, null, new HttpHeaders(), code.getStatus(), request);
    }

    private boolean isRequestParameterViolation(ConstraintViolation<?> violation, HandlerMethod handler) {
        if (!handler.getBeanType().isAssignableFrom(violation.getRootBeanClass())) {
            return false;
        }
        boolean matchingMethod = false;
        boolean requestParameter = false;
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.METHOD) {
                Path.MethodNode method = node.as(Path.MethodNode.class);
                matchingMethod = handler.getMethod().getName().equals(method.getName())
                        && Arrays.equals(handler.getMethod().getParameterTypes(),
                            method.getParameterTypes().toArray(Class<?>[]::new));
            } else if (node.getKind() == ElementKind.PARAMETER) {
                int index = node.as(Path.ParameterNode.class).getParameterIndex();
                if (index < 0 || index >= handler.getMethodParameters().length) {
                    return false;
                }
                var parameter = handler.getMethodParameters()[index];
                requestParameter = parameter.hasParameterAnnotation(RequestParam.class)
                        || parameter.hasParameterAnnotation(PathVariable.class)
                        || parameter.hasParameterAnnotation(RequestBody.class)
                        || parameter.hasParameterAnnotation(RequestHeader.class)
                        || parameter.hasParameterAnnotation(CookieValue.class)
                        || parameter.hasParameterAnnotation(ModelAttribute.class)
                        || parameter.hasParameterAnnotation(RequestPart.class)
                        || parameter.hasParameterAnnotation(MatrixVariable.class);
            } else if (node.getKind() == ElementKind.RETURN_VALUE
                    || node.getKind() == ElementKind.CROSS_PARAMETER) {
                return false;
            }
        }
        return matchingMethod && requestParameter;
    }

    private void logFailure(Exception exception, HttpStatusCode status, ErrorCode code) {
        if (status.is5xxServerError()) {
            log.error("Request failed: status={}, code={}", status.value(), code.getCode(), exception);
        } else {
            // Do not log messages, binding results, URIs or rejected values for client errors.
            log.debug("Request rejected: status={}, code={}, exception={}",
                    status.value(), code.getCode(), exception.getClass().getSimpleName());
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception) {
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        logFailure(exception, code.getStatus(), code);
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code.getCode(), code.getMessage()));
    }
}
