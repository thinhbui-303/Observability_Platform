package com.thinhbui303.observability.ingestion.controller;

import com.thinhbui303.observability.ingestion.security.AuthenticatedServiceIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AccessLogInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute("startTime");
        long latency = (startTime != null) ? (System.currentTimeMillis() - startTime) : 0;
        int status = response.getStatus();

        AuthenticatedServiceIdentity identity = (AuthenticatedServiceIdentity) request.getAttribute("AuthenticatedServiceIdentity");
        String serviceId = (identity != null) ? identity.serviceId() : "unauthenticated";

        log.info("Access Log - path={}, method={}, serviceId={}, status={}, latency={}ms",
                request.getRequestURI(), request.getMethod(), serviceId, status, latency);
        // Note: Request body is strictly NOT logged to comply with immutable boundary rules.
    }
}
