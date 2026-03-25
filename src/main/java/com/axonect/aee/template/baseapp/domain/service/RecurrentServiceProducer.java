package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.transport.request.entities.DBWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Kafka producer for DB write events triggered during recurrent service reactivation.
 * Publishes service instance updates, bucket instance inserts/updates, and failure records
 * to the accounting management service via Kafka.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecurrentServiceProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${service-renewal.db-write-topic:recurrent-service-db-write-events}")
    private String dbWriteTopic;

    /**
     * Publishes a DB write event to Kafka.
     * Retries up to 3 additional times (4 attempts total) with exponential backoff
     * before falling back to {@link #fallbackPublishDBWriteEvent}.
     *
     * @param request the DB write request containing table, event type, and column values
     * @param key     the Kafka partition key (typically the service or entity ID)
     */
    @Retryable(
            maxAttempts = 4,
            backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 10000)
    )
    public void publishDBWriteEvent(DBWriteRequest request, String key) {
        log.info("Publishing DB write event - table: {}, eventType: {}, key: {}",
                request.getTableName(), request.getEventType(), key);

        kafkaTemplate.send(dbWriteTopic, key, request)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send DB write event - table: {}, key: {}",
                                request.getTableName(), key, ex);
                    } else {
                        log.info("Successfully sent DB write event - table: {}, key: {}",
                                request.getTableName(), key);
                    }
                });
    }

    /**
     * Fallback invoked after all retry attempts are exhausted.
     *
     * @param e       the last exception that caused the failure
     * @param request the original DB write request
     * @param key     the Kafka partition key
     */
    @Recover
    public void fallbackPublishDBWriteEvent(Exception e, DBWriteRequest request, String key) {
        log.error("All retry attempts exhausted for DB write event - table: {}, key: {}",
                request.getTableName(), key, e);
    }
}
