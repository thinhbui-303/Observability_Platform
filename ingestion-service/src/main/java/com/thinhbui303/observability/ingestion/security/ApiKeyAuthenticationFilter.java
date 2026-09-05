package com.thinhbui303.observability.ingestion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyValidator apiKeyValidator;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyValidator apiKeyValidator, ObjectMapper objectMapper) {
        this.apiKeyValidator = apiKeyValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String apiKey = request.getHeader("X-API-Key");

        if (apiKey == null || apiKey.isBlank()) {
            sendUnauthorizedError(request, response, "Missing X-API-Key header");
            return;
        }

        try {
            AuthenticatedServiceIdentity identity = apiKeyValidator.validate(apiKey);
            request.setAttribute("AuthenticatedServiceIdentity", identity);
            filterChain.doFilter(request, response);
        } catch (ApiKeyInvalidException e) {
            sendUnauthorizedError(request, response, "Invalid or revoked API Key");
        }
    }

    private void sendUnauthorizedError(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse errorResponse = new ErrorResponse(
                "UNAUTHORIZED",
                message,
                Instant.now(),
                request.getRequestURI(),
                null
        );
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
