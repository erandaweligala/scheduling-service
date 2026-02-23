package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.BucketExpiryNotification;
import com.axonect.aee.template.baseapp.domain.entities.dto.FttxExpiryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Kafka consumer for FTTX expiry events from AAA/SingleView.
 *
 * Consumes events from the FTTX expiry topic, validates that the event is an
 * EXPIRY event for the FTTX product type, maps the payload fields to a
 * {@link BucketExpiryNotification}, and publishes it to the bucket expiry
 * notifications topic.
 *
 * Field mapping:
 * <pre>
 *   FttxExpiryEvent.data.serviceLineNumber  → BucketExpiryNotification.username
 *   FttxExpiryEvent.data.planDescription    → BucketExpiryNotification.planName
 *   FttxExpiryEvent.data.expiryDate         → BucketExpiryNotification.dateOfExpiry
 *   FttxExpiryEvent.data.message            → BucketExpiryNotification.message
 *   FttxExpiryEvent.meta.eventType          → BucketExpiryNotification.messageType
 *   FttxExpiryEvent.meta.messageTimeStamp   → BucketExpiryNotification.notificationTime
 *   calculated from expiryDate - notificationTime → BucketExpiryNotification.daysToExpire
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FttxExpiryConsumerService {

    private static final String EVENT_TYPE_EXPIRY = "EXPIRY";
    private static final String PRODUCT_TYPE_FTTX = "FTTX";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topic.bucket-expiry-notification:bucket-expiry-notifications}")
    private String bucketExpiryTopic;

    /**
     * Consumes an FTTX expiry event from the configured Kafka topic.
     * Only processes events where:
     * - {@code meta.eventType} is {@code "EXPIRY"}
     * - {@code meta.productType} is {@code "FTTX"}
     *
     * @param event the incoming FTTX expiry event
     */
    @KafkaListener(
            topics = "${kafka.topic.fttx-expiry-events:fttx-expiry-events}",
            groupId = "${spring.kafka.consumer.group-id:scheduling-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleFttxExpiryEvent(FttxExpiryEvent event) {
        if (event == null || event.getMeta() == null || event.getData() == null) {
            log.warn("Received null or incomplete FTTX expiry event. Skipping.");
            return;
        }

        String eventType = event.getMeta().getEventType();
        String productType = event.getMeta().getProductType();

        if (!EVENT_TYPE_EXPIRY.equalsIgnoreCase(eventType)) {
            log.debug("Skipping event with eventType={} (expected {})", eventType, EVENT_TYPE_EXPIRY);
            return;
        }

        if (!PRODUCT_TYPE_FTTX.equalsIgnoreCase(productType)) {
            log.debug("Skipping event with productType={} (expected {})", productType, PRODUCT_TYPE_FTTX);
            return;
        }

        log.info("Processing FTTX EXPIRY event: eventId={}, serviceLineNumber={}",
                event.getMeta().getEventId(), event.getData().getServiceLineNumber());

        try {
            BucketExpiryNotification notification = mapToNotification(event);
            sendNotification(notification);
        } catch (Exception e) {
            log.error("Failed to process FTTX expiry event: eventId={}", event.getMeta().getEventId(), e);
        }
    }

    /**
     * Maps the FTTX expiry event fields to a {@link BucketExpiryNotification}.
     *
     * @param event the source FTTX expiry event
     * @return a populated {@link BucketExpiryNotification}
     */
    BucketExpiryNotification mapToNotification(FttxExpiryEvent event) {
        FttxExpiryEvent.EventMeta meta = event.getMeta();
        FttxExpiryEvent.EventData data = event.getData();

        // username ← data.serviceLineNumber
        String username = data.getServiceLineNumber();

        // planName ← data.planDescription
        String planName = data.getPlanDescription();

        // dateOfExpiry ← data.expiryDate (ISO-8601 without offset, e.g. "2026-02-20T23:59:59")
        LocalDateTime dateOfExpiry = parseLocalDateTime(data.getExpiryDate(), "data.expiryDate");

        // notificationTime ← meta.messageTimeStamp (ISO-8601 with offset, e.g. "2026-02-19T13:35:03.999Z")
        LocalDateTime notificationTime = parseOffsetToLocalDateTime(meta.getMessageTimeStamp(), "meta.messageTimeStamp");

        // daysToExpire ← days between notificationTime date and dateOfExpiry date
        int daysToExpire = calculateDaysToExpire(notificationTime, dateOfExpiry);

        // messageType ← meta.eventType (e.g. "EXPIRY")
        String messageType = meta.getEventType();

        // message ← data.message
        String message = data.getMessage();

        BucketExpiryNotification notification = BucketExpiryNotification.builder()
                .username(username)
                .userId(null)
                .serviceId(null)
                .planName(planName)
                .dateOfExpiry(dateOfExpiry)
                .daysToExpire(daysToExpire)
                .message(message)
                .messageType(messageType)
                .templateId(null)
                .notificationTime(notificationTime)
                .build();

        log.debug("Mapped FTTX expiry event to notification: username={}, planName={}, dateOfExpiry={}, daysToExpire={}",
                username, planName, dateOfExpiry, daysToExpire);

        return notification;
    }

    /**
     * Sends the notification to the bucket expiry Kafka topic.
     * The username is used as the Kafka message key for partition routing.
     *
     * @param notification the notification to send
     */
    private void sendNotification(BucketExpiryNotification notification) {
        String key = notification.getUsername();
        kafkaTemplate.send(bucketExpiryTopic, key, notification)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish BucketExpiryNotification to topic={} for username={}",
                                bucketExpiryTopic, key, ex);
                    } else {
                        log.info("Published BucketExpiryNotification to topic={} for username={}, planName={}, dateOfExpiry={}",
                                bucketExpiryTopic, key, notification.getPlanName(), notification.getDateOfExpiry());
                    }
                });
    }

    /**
     * Parses an ISO-8601 local date-time string (without timezone offset).
     * Returns {@link LocalDateTime#now()} if the value is null or unparseable.
     */
    private LocalDateTime parseLocalDateTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            log.warn("Field {} is null or blank; using current time as fallback", fieldName);
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse {} value '{}' as LocalDateTime; using current time as fallback", fieldName, value);
            return LocalDateTime.now();
        }
    }

    /**
     * Parses an ISO-8601 offset date-time string (e.g. "2026-02-19T13:35:03.999Z") to a
     * {@link LocalDateTime} by stripping the offset.
     * Returns {@link LocalDateTime#now()} if the value is null or unparseable.
     */
    private LocalDateTime parseOffsetToLocalDateTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            log.warn("Field {} is null or blank; using current time as fallback", fieldName);
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException e) {
            // Fallback: try plain LocalDateTime (no offset)
            return parseLocalDateTime(value, fieldName);
        }
    }

    /**
     * Calculates the number of whole days between the notification time and the expiry date-time.
     * Returns 0 if either parameter is null or the expiry is before the notification time.
     */
    private int calculateDaysToExpire(LocalDateTime notificationTime, LocalDateTime expiryDateTime) {
        if (notificationTime == null || expiryDateTime == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(notificationTime.toLocalDate(), expiryDateTime.toLocalDate());
        return (int) Math.max(0, days);
    }
}
