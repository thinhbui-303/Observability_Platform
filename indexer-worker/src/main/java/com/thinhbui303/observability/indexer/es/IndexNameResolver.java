package com.thinhbui303.observability.indexer.es;

import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class IndexNameResolver {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);
    private static final String INDEX_PREFIX = "logs-";

    public String resolve(CanonicalLogEvent event) {
        return INDEX_PREFIX + FORMATTER.format(event.timestamp());
    }
}
