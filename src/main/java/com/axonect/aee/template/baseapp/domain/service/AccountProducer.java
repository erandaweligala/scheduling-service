package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.DBWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Kafka producer for DB write events triggered on idle session termination.
 * Sends balance persistence requests to the accounting management service via Kafka.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${idle-session.db-write-topic:db-write-events}")
    private String dbWriteTopic;

    /**
     * Produce a DB write event to Kafka so the accounting service persists the
     * final balance state for the terminated session.
     *
     * <p>Retries up to 3 additional times (4 attempts total) with exponential
     * backoff before falling back to {@link #fallbackProduceDBWriteEvent}.
     *
     * @param request the DB write request containing session and balance details
     */
    @Retryable(
            maxAttempts = 4,
            backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 10000)
    )
    public void produceDBWriteEvent(DBWriteRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Producing DB write event for session: {}", request.getSessionId());

        kafkaTemplate.send(dbWriteTopic, request.getSessionId(), request)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send DB write event for session: {}", request.getSessionId(), ex);
                    } else {
                        log.info("Successfully sent DB write event for session: {} in {} ms",
                                request.getSessionId(), System.currentTimeMillis() - startTime);
                    }
                });
    }

    /**
     * Fallback invoked after all retry attempts are exhausted.
     *
     * @param e       the last exception that caused the failure
     * @param request the original DB write request
     */
    @Recover
    public void fallbackProduceDBWriteEvent(Exception e, DBWriteRequest request) {
        log.error("All retry attempts exhausted for DB write event. Session: {}, bucketId: {}",
                request.getSessionId(), request.getBucketId(), e);
    }
}
