package com.thinhbui303.observability.analytics.topology;

import com.thinhbui303.observability.analytics.rule.AlertRuleCacheService;
import com.thinhbui303.observability.analytics.rule.AlertRuleConfig;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ErrorWindowStreamTopology {

    private static final Logger log = LoggerFactory.getLogger(ErrorWindowStreamTopology.class);
    private static final int WINDOW_SECONDS = 60;
    private static final int GRACE_SECONDS = 10;

    @Autowired
    public void buildTopology(StreamsBuilder builder,
                              AlertRuleCacheService ruleCacheService,
                              Serde<CanonicalLogEvent> logEventSerde,
                              Serde<CanonicalAlertEvent> alertEventSerde) {

        KStream<String, CanonicalLogEvent> stream = builder.stream("raw-logs", Consumed.with(Serdes.String(), logEventSerde));

        KStream<String, CanonicalAlertEvent> alertStream = stream
            .filter((key, value) -> value != null && "ERROR".equalsIgnoreCase(value.level()))
            .selectKey((key, value) -> {
                String serviceName = value.serviceName() != null ? value.serviceName() : "unknown-service";
                String environment = value.environment() != null ? value.environment() : "unknown-env";
                return serviceName + "||" + environment;
            })
            .groupByKey(Grouped.with(Serdes.String(), logEventSerde))
            .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofSeconds(WINDOW_SECONDS), Duration.ofSeconds(GRACE_SECONDS)))
            .count()
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .flatMap((Windowed<String> windowedKey, Long count) -> {
                String compositeKey = windowedKey.key();
                String[] parts = compositeKey.split("\\|\\|");
                String serviceId = parts[0];
                String environment = parts.length > 1 ? parts[1] : "unknown-env";
                
                log.debug("Window closed for {} with count {}", compositeKey, count);

                List<AlertRuleConfig> rules = ruleCacheService.getRules(serviceId, environment);
                List<KeyValue<String, CanonicalAlertEvent>> results = new ArrayList<>();

                for (AlertRuleConfig rule : rules) {
                    if (count >= rule.thresholdValue()) {
                        CanonicalAlertEvent alert = new CanonicalAlertEvent(
                                UUID.randomUUID().toString(),
                                rule.ruleId(),
                                serviceId,
                                environment,
                                rule.severity(),
                                "TRIGGERED",
                                "ERROR_COUNT >= " + rule.thresholdValue(),
                                WINDOW_SECONDS,
                                Instant.ofEpochMilli(windowedKey.window().start()),
                                count.intValue(),
                                Instant.now(),
                                null,
                                null,
                                null,
                                rule.notificationChannels()
                        );
                        log.info("Triggered alert for rule {} (service={}, count={})", rule.ruleId(), serviceId, count);
                        results.add(KeyValue.pair(serviceId, alert));
                    }
                }
                return results;
            });

        alertStream.to("system-alerts", Produced.with(Serdes.String(), alertEventSerde));
    }
}
