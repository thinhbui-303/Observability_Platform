package com.thinhbui303.observability.ingestion.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class ActuatorSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ActuatorSecurityFilter.class);
    private final List<IpSubnet> allowedSubnets = new ArrayList<>();

    public ActuatorSecurityFilter(@Value("${actuator.allowed-ip-ranges:127.0.0.1/32,172.16.0.0/12}") String allowedIpRanges) {
        if (allowedIpRanges != null && !allowedIpRanges.isBlank()) {
            String[] ranges = allowedIpRanges.split(",");
            for (String range : ranges) {
                try {
                    allowedSubnets.add(new IpSubnet(range.trim()));
                } catch (Exception e) {
                    log.error("Invalid IP range format in actuator.allowed-ip-ranges: {}", range, e);
                }
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String path = request.getRequestURI();
        if (path.startsWith("/actuator") && !path.equals("/actuator/health")) {
            String clientIp = getClientIp(request);
            if (!isAllowed(clientIp)) {
                log.warn("Blocked actuator access from IP: {}", clientIp);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access Denied");
                return;
            }
        }
        
        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String ipAddress) {
        if (allowedSubnets.isEmpty()) return false;
        
        for (IpSubnet subnet : allowedSubnets) {
            if (subnet.matches(ipAddress)) {
                return true;
            }
        }
        return false;
    }
    
    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
    
    // A simple IPv4 Subnet matcher
    private static class IpSubnet {
        private final int network;
        private final int mask;

        public IpSubnet(String cidr) {
            if (!cidr.contains("/")) {
                cidr = cidr + "/32";
            }
            String[] parts = cidr.split("/");
            this.network = ipToInt(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            this.mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
        }

        public boolean matches(String ip) {
            try {
                if (ip.equals("0:0:0:0:0:0:0:1")) {
                    ip = "127.0.0.1";
                }
                int ipInt = ipToInt(ip);
                return (ipInt & mask) == (network & mask);
            } catch (Exception e) {
                return false;
            }
        }

        private int ipToInt(String ipAddress) {
            String[] octets = ipAddress.split("\\.");
            if (octets.length != 4) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            int result = 0;
            for (String octet : octets) {
                result <<= 8;
                result |= Integer.parseInt(octet) & 0xFF;
            }
            return result;
        }
    }
}
