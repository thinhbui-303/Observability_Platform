package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<UnifiedResponse<Void>> handleAuthenticationException(Exception ex, HttpServletRequest request) {
        UnifiedResponse<Void> response = new UnifiedResponse<>(
                "AUTHENTICATION_FAILED",
                "Invalid username or password",
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<UnifiedResponse<Void>> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        java.util.List<Map<String, String>> fieldErrors = new java.util.ArrayList<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.add(Map.of("field", fieldName, "message", errorMessage));
        });

        UnifiedResponse<Void> response = new UnifiedResponse<>(
                "VALIDATION_ERROR",
                null
        );
        response.setFieldErrors(fieldErrors);
        response.setMessage("Invalid request parameters");
        response.setTimestamp(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.setPath(request.getRequestURI());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<UnifiedResponse<Void>> handleTypeMismatchExceptions(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        java.util.List<Map<String, String>> fieldErrors = new java.util.ArrayList<>();
        fieldErrors.add(Map.of("field", ex.getName(), "message", "Invalid format or type"));

        UnifiedResponse<Void> response = new UnifiedResponse<>(
                "VALIDATION_ERROR",
                null
        );
        response.setFieldErrors(fieldErrors);
        response.setMessage("Invalid request parameters");
        response.setTimestamp(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.setPath(request.getRequestURI());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<UnifiedResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        UnifiedResponse<Void> response = new UnifiedResponse<>(
                "VALIDATION_ERROR",
                ex.getMessage(),
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<UnifiedResponse<Void>> handleGlobalException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing request: {}", request.getRequestURI(), ex);
        UnifiedResponse<Void> response = new UnifiedResponse<>(
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred",
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
