package com.thinhbui303.observability.analytics.topology;

import com.thinhbui303.observability.analytics.rule.AlertRuleCacheService;
import com.thinhbui303.observability.analytics.rule.AlertRuleConfig;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PatternMatchStreamTopology {

    private static final Logger log = LoggerFactory.getLogger(PatternMatchStreamTopology.class);

    @Autowired
    public void buildTopology(StreamsBuilder builder,
                              AlertRuleCacheService ruleCacheService,
                              Serde<CanonicalLogEvent> logEventSerde,
                              Serde<CanonicalAlertEvent> alertEventSerde) {

        KStream<String, CanonicalLogEvent> stream = builder.stream("raw-logs", Consumed.with(Serdes.String(), logEventSerde));

        KStream<String, CanonicalAlertEvent> alertStream = stream
                .filter((key, value) -> value != null && value.message() != null)
                .flatMap((key, value) -> {
                    String serviceId = value.serviceName() != null ? value.serviceName() : "unknown-service";
                    String environment = value.environment() != null ? value.environment() : "unknown-env";
                    String message = value.message();

                    List<AlertRuleConfig> rules = ruleCacheService.getRules(serviceId, environment);
                    List<KeyValue<String, CanonicalAlertEvent>> results = new ArrayList<>();

                    for (AlertRuleConfig rule : rules) {
                        if ("PATTERN_MATCH".equals(rule.conditionType()) && rule.conditionValue() != null) {
                            if (message.contains(rule.conditionValue())) {
                                CanonicalAlertEvent alert = new CanonicalAlertEvent(
                                        UUID.randomUUID().toString(),
                                        rule.ruleId(),
                                        serviceId,
                                        environment,
                                        rule.severity(),
                                        "TRIGGERED",
                                        "Matched pattern: " + rule.conditionValue(),
                                        0, // 0 for instant alerts
                                        value.timestamp(),
                                        1,
                                        Instant.now(),
                                        null,
                                        null,
                                        value.eventId(),
                                        rule.notificationChannels()
                                );
                                log.info("Triggered PATTERN_MATCH alert for rule {} (service={}, pattern={})", rule.ruleId(), serviceId, rule.conditionValue());
                                results.add(KeyValue.pair(serviceId, alert));
                            }
                        }
                    }
                    return results;
                });

        alertStream.to("system-alerts", Produced.with(Serdes.String(), alertEventSerde));
    }
}
