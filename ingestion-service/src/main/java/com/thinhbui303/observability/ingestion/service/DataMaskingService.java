package com.thinhbui303.observability.ingestion.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DataMaskingService {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("(?i)(password|token)=\\S+");

    public String maskMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        String masked = CREDIT_CARD_PATTERN.matcher(message).replaceAll(REDACTED);

        Matcher matcher = KEY_VALUE_PATTERN.matcher(masked);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1) + "=" + REDACTED);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    public Map<String, Object> maskMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return metadata;
        }

        Map<String, Object> maskedMetadata = new HashMap<>(metadata);
        maskedMetadata.replaceAll((key, value) -> {
            String lowerKey = key.toLowerCase();
            if (lowerKey.equals("authorization") || lowerKey.equals("token")
                    || lowerKey.equals("password") || lowerKey.equals("apikey")) {
                return REDACTED;
            }
            return value;
        });

        return maskedMetadata;
    }
}
