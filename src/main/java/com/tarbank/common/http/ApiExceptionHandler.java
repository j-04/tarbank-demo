package com.tarbank.common.http;

import com.tarbank.common.api.ApiErrorResponse;
import com.tarbank.common.api.ApiErrorResponses;
import com.tarbank.security.application.AuthService.InvalidCredentialsException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
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
            for (FieldError fieldError : validationException.getBindingResult().getFieldErrors()) {
                fieldErrors.add(new ApiErrorResponse.FieldError(
                        fieldError.getField(), fieldError.getCode(), fieldError.getDefaultMessage()));
            }
        }
        return ResponseEntity.badRequest().body(ApiErrorResponses.validation(correlationId(), fieldErrors));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> known(ApiException exception) {
        LOGGER.warn("internalCode={} publicErrorCode={} httpStatus={} correlationId={}",
                    exception.getInternalCode(), exception.getCode(), exception.getStatus().value(), correlationId());
        return ResponseEntity.status(exception.getStatus())
                .body(ApiErrorResponses.error(correlationId(), exception.getCode(), exception.getSafeMessage(),
                                              exception.getFieldErrors()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> invalidCredentials() {
        LOGGER.warn("internalCode=TAR-AUTH-001 publicErrorCode=INVALID_CREDENTIALS httpStatus=401 correlationId={}", correlationId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponses.error(correlationId(), "INVALID_CREDENTIALS", "Invalid credentials."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> method() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiErrorResponses.validation(correlationId(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
        LOGGER.error("internalCode=TAR-INFRA-001 publicErrorCode=INTERNAL_ERROR httpStatus=500 exceptionType={} correlationId={}",
                     exception.getClass().getName(), correlationId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponses.internalError(correlationId()));
    }

    private UUID correlationId() {
        return CorrelationIdContext.current();
    }
}