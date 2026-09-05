package com.thinhbui303.observability.ingestion.controller;

import com.thinhbui303.observability.common.ErrorResponse;
import com.thinhbui303.observability.common.ValidationErrorResponse;
import com.thinhbui303.observability.ingestion.ratelimit.RateLimitExceededException;
import com.thinhbui303.observability.ingestion.security.ApiKeyInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ValidationErrorResponse.FieldError> errors = ex.getBindingResult()
                .getAllErrors()
                .stream()
                .map(error -> new ValidationErrorResponse.FieldError(
                        ((FieldError) error).getField(),
                        error.getDefaultMessage()
                ))
                .collect(Collectors.toList());

        ValidationErrorResponse response = new ValidationErrorResponse(
                "VALIDATION_ERROR",
                "Invalid request payload",
                Instant.now(),
                request.getRequestURI(),
                null,
                errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ApiKeyInvalidException.class)
    public ResponseEntity<ErrorResponse> handleApiKeyInvalid(ApiKeyInvalidException ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                "UNAUTHORIZED",
                ex.getMessage(),
                Instant.now(),
                request.getRequestURI(),
                null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                "RATE_LIMIT_EXCEEDED",
                ex.getMessage(),
                Instant.now(),
                request.getRequestURI(),
                null
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    @ExceptionHandler({ExecutionException.class, TimeoutException.class})
    public ResponseEntity<ErrorResponse> handleKafkaExceptions(Exception ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                "KAFKA_UNAVAILABLE",
                "Failed to send event to message broker",
                Instant.now(),
                request.getRequestURI(),
                null
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}
