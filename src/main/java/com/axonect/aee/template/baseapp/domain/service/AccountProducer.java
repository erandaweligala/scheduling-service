package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.DBWriteRequest;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.AccountingCdrEvent;
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

    @Value("${idle-session.cdr-topic:accounting-cdr-events}")
    private String cdrTopic;

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
        log.error("All retry attempts exhausted for DB write event. Session: {}",
                request.getSessionId(), e);
    }

    /**
     * Produce an accounting CDR event to Kafka. Generated when a session is
     * terminated (e.g. on idle timeout) so downstream systems receive a Call
     * Detail Record for the closed session.
     *
     * <p>Retries up to 3 additional times (4 attempts total) with exponential
     * backoff before falling back to {@link #fallbackProduceCdrEvent}.
     *
     * @param event the accounting CDR event to publish
     */
    @Retryable(
            maxAttempts = 4,
            backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 10000)
    )
    public void produceCdrEvent(AccountingCdrEvent event) {
        long startTime = System.currentTimeMillis();
        String key = event.getPartitionKey();
        log.info("Producing CDR event [{}] for session: {}", event.getEventType(), key);

        kafkaTemplate.send(cdrTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send CDR event for session: {}", key, ex);
                    } else {
                        log.info("Successfully sent CDR event for session: {} in {} ms",
                                key, System.currentTimeMillis() - startTime);
                    }
                });
    }

    /**
     * Fallback invoked after all retry attempts for a CDR event are exhausted.
     *
     * @param e     the last exception that caused the failure
     * @param event the original CDR event
     */
    @Recover
    public void fallbackProduceCdrEvent(Exception e, AccountingCdrEvent event) {
        log.error("All retry attempts exhausted for CDR event. Session: {}",
                event.getPartitionKey(), e);
    }
}
