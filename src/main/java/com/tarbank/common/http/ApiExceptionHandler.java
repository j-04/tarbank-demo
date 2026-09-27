package com.tarbank.common.http;

import com.tarbank.common.api.ApiErrorResponse;
import com.tarbank.common.api.ApiErrorResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LogManager.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiErrorResponse> handleValidation(Exception exception) {
        List<ApiErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        if (exception instanceof MethodArgumentNotValidException validationException) {
            for (FieldError fieldError : validationException.getBindingResult().getFieldErrors()) {
                fieldErrors.add(new ApiErrorResponse.FieldError(
                        fieldError.getField(), fieldError.getCode(), fieldError.getDefaultMessage()));
            }
        }
        return ResponseEntity.badRequest().body(ApiErrorResponses.validation(correlationId(), fieldErrors));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMethod() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiErrorResponses.validation(correlationId(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        LOGGER.error(
                "internalCode=TAR-INFRA-001 publicErrorCode=INTERNAL_ERROR httpStatus=500 exceptionType={}",
                exception.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponses.internalError(correlationId()));
    }

    private UUID correlationId() {
        return CorrelationIdContext.current();
    }
}