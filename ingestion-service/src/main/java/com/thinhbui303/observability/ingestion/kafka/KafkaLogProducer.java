package com.thinhbui303.observability.ingestion.kafka;

import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class KafkaLogProducer {

    private final KafkaTemplate<String, CanonicalLogEvent> kafkaTemplate;

    public KafkaLogProducer(KafkaTemplate<String, CanonicalLogEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(CanonicalLogEvent event) throws ExecutionException, InterruptedException, TimeoutException {
        // Synchronous send with 10 seconds timeout
        kafkaTemplate.send("raw-logs", event.serviceName(), event).get(10, TimeUnit.SECONDS);
    }
}
