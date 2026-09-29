package com.tarbank.common.http;

import com.tarbank.common.api.ApiErrorResponse;
import com.tarbank.common.api.ApiErrorResponses;
import com.tarbank.common.resilience.SimulatedFailureException;
import com.tarbank.security.application.AuthService.InvalidCredentialsException;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LogManager.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiErrorResponse> validation(Exception exception) {
        List<ApiErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        if (exception instanceof MethodArgumentNotValidException validationException) {
            for (FieldError fieldError : validationException.getBindingResult()
                                                            .getFieldErrors()) {
                fieldErrors.add(new ApiErrorResponse.FieldError(
                        fieldError.getField(), fieldError.getCode(), fieldError.getDefaultMessage()));
            }
        }
        return ResponseEntity.badRequest()
                             .body(ApiErrorResponses.validation(correlationId(), fieldErrors));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> known(ApiException exception) {
        log(Level.WARN, exception.getInternalCode(), exception.getCode(),
            exception.getStatus()
                     .value(), "rejected", "Known API request rejected");
        return ResponseEntity.status(exception.getStatus())
                             .body(ApiErrorResponses.error(correlationId(), exception.getCode(),
                                                           exception.getSafeMessage(),
                                                           exception.getFieldErrors()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> invalidCredentials() {
        log(Level.WARN, "TAR-AUTH-001", "INVALID_CREDENTIALS", 401,
            "rejected", "Authentication request rejected");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                             .body(ApiErrorResponses.error(correlationId(), "INVALID_CREDENTIALS",
                                                           "Invalid credentials."));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> rateLimited(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                             .header(HttpHeaders.RETRY_AFTER,
                                     Long.toString(exception.getRetryAfterSeconds()))
                             .body(ApiErrorResponses.error(
                                     correlationId(), "RATE_LIMIT_EXCEEDED",
                                     "The request rate limit has been exceeded."));
    }

    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            JDBCConnectionException.class
    })
    public ResponseEntity<ApiErrorResponse> dependencyUnavailable(Exception exception) {
        log(Level.ERROR, "TAR-INFRA-002", "DEPENDENCY_UNAVAILABLE", 503,
            "dependency_unavailable", "Required dependency unavailable exceptionType={}",
            exception.getClass()
                     .getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                             .body(ApiErrorResponses.error(
                                     correlationId(), "DEPENDENCY_UNAVAILABLE",
                                     "A required service is temporarily unavailable."));
    }

    @ExceptionHandler(SimulatedFailureException.class)
    public ResponseEntity<ApiErrorResponse> simulatedFailure(SimulatedFailureException exception) {
        log(Level.ERROR, "TAR-DEMO-001", "INTERNAL_ERROR", 500,
            "simulated_failure", "Configured demo failure point={}", exception.getPoint());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                             .body(ApiErrorResponses.internalError(correlationId()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> method() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                             .body(ApiErrorResponses.validation(correlationId(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
        log(Level.ERROR, "TAR-INFRA-001", "INTERNAL_ERROR", 500,
            "technical_failure", "Unexpected request failure exceptionType={}",
            exception.getClass()
                     .getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                             .body(ApiErrorResponses.internalError(correlationId()));
    }

    private UUID correlationId() {
        return CorrelationIdContext.current();
    }

    private void log(Level level,
                     String internalCode,
                     String publicErrorCode,
                     int httpStatus,
                     String outcome,
                     String message,
                     Object... arguments) {
        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.putAll(Map.of(
                "internalCode", internalCode,
                "publicErrorCode", publicErrorCode,
                "httpStatus", Integer.toString(httpStatus),
                "outcome", outcome))) {
            LOGGER.log(level, message, arguments);
        }
    }
}
