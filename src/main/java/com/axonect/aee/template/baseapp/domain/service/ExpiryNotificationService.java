package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ChildTemplateTableRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketExpiryNotification;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.ChildTemplateTable;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.exception.NotificationProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Service for processing and sending bucket expiry notifications via Kafka
 *
 * Workflow:
 * 1. Fetch all EXPIRE type templates from CHILD_TEMPLATE_TABLE
 * 2. For each template with DAYS_TO_EXPIRE configuration:
 *    - Calculate the target expiry date (today + DAYS_TO_EXPIRE)
 *    - Find bucket instances expiring on that date
 *    - Get user and plan information from ServiceInstance
 *    - Replace dynamic fields in message template
 *    - Send Kafka notification event
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExpiryNotificationService {

    private final ChildTemplateTableRepository childTemplateTableRepository;
    private final BucketInstanceRepository bucketInstanceRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final UserCacheService userCacheService;

    @Value("${kafka.topic.bucket-expiry-notification:bucket-expiry-notifications}")
    private String bucketExpiryTopic;

    @Value("${expiry-notification.batch-size:100}")
    private Integer batchSize;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Process and send expiry notifications for all configured EXPIRE templates
     * Called by scheduled job to check daily
     *
     * @return total number of notifications sent
     */
    @Transactional(readOnly = true)
    public int processExpiryNotifications() {
        log.info("Starting expiry notification processing...");

        int totalNotificationsSent = 0;

        try {
            // Fetch all EXPIRE type templates from database
            List<ChildTemplateTable> expireTemplates = childTemplateTableRepository.findAllExpireTemplates();

            if (expireTemplates.isEmpty()) {
                log.warn("No EXPIRE templates found in CHILD_TEMPLATE_TABLE. Skipping notification processing.");
                return 0;
            }

            log.info("Found {} EXPIRE templates to process", expireTemplates.size());

            // Process each template configuration
            for (ChildTemplateTable template : expireTemplates) {
                if (template.getDaysToExpire() == null) {
                    log.warn("Template {} has null DAYS_TO_EXPIRE, skipping", template.getId());
                    continue;
                }

                int sentCount = processTemplateNotifications(template);
                totalNotificationsSent += sentCount;
            }

            log.info("Expiry notification processing completed. Total notifications sent: {}", totalNotificationsSent);

        } catch (Exception e) {
            log.error("Error processing expiry notifications", e);
            throw new NotificationProcessingException("Failed to process expiry notifications", e);
        }

        return totalNotificationsSent;
    }

    /**
     * Process notifications for a specific template configuration
     *
     * @param template the child template with DAYS_TO_EXPIRE configuration
     * @return number of notifications sent for this template
     */
    private int processTemplateNotifications(ChildTemplateTable template) {
        int daysToExpire = template.getDaysToExpire();
        log.info("Processing template ID: {} with DAYS_TO_EXPIRE: {}", template.getId(), daysToExpire);

        // Calculate target expiry date
        // Example: If DAYS_TO_EXPIRE = 2 and today is 2026-01-28, target date is 2026-01-30
        // We want to notify about buckets expiring on 2026-01-30
        LocalDate targetExpiryDate = LocalDate.now().plusDays(daysToExpire);
        LocalDateTime targetExpiryStart = targetExpiryDate.atStartOfDay();
        LocalDateTime targetExpiryEnd = targetExpiryDate.plusDays(1).atStartOfDay();

        log.info("Looking for bucket instances expiring on: {}", targetExpiryDate);

        int notificationsSent = 0;
        int page = 0;
        boolean hasMore = true;

        // Process in batches to handle large datasets
        while (hasMore) {
            Pageable pageable = PageRequest.of(page, batchSize);
            Page<BucketInstance> bucketPage = findBucketsExpiringBetween(targetExpiryStart, targetExpiryEnd, pageable);

            List<BucketInstance> buckets = bucketPage.getContent();

            if (buckets.isEmpty()) {
                hasMore = false;
                continue;
            }

            log.info("Processing page {} with {} bucket instances", page, buckets.size());

            for (BucketInstance bucket : buckets) {
                try {
                    sendNotificationForBucket(bucket, template, daysToExpire, targetExpiryDate);
                    notificationsSent++;
                } catch (Exception e) {
                    log.error("Failed to send notification for bucket instance ID: {}", bucket.getId(), e);
                    // Continue processing other buckets even if one fails
                }
            }

            hasMore = bucketPage.hasNext();
            page++;
        }

        log.info("Template ID {} processing completed. Sent {} notifications", template.getId(), notificationsSent);
        return notificationsSent;
    }

    /**
     * Find bucket instances that expire within a specific date range
     *
     * @param startDateTime start of expiry date range
     * @param endDateTime end of expiry date range
     * @param pageable pagination information
     * @return page of bucket instances
     */
    private Page<BucketInstance> findBucketsExpiringBetween(
            LocalDateTime startDateTime,
            LocalDateTime endDateTime,
            Pageable pageable) {

        return bucketInstanceRepository.findBucketsExpiringBetween(startDateTime, endDateTime, pageable);
    }

    /**
     * Send Kafka notification for a specific bucket instance
     *
     * @param bucket the bucket instance
     * @param template the message template
     * @param daysToExpire days until expiry
     * @param expiryDate the expiry date
     */
    private void sendNotificationForBucket(
            BucketInstance bucket,
            ChildTemplateTable template,
            int daysToExpire,
            LocalDate expiryDate) {

        // Get service instance to retrieve username and plan name
        Optional<ServiceInstance> serviceOpt = serviceInstanceRepository.findById(bucket.getServiceId());

        if (serviceOpt.isEmpty()) {
            log.warn("Service instance not found for bucket ID: {}. Service ID: {}",
                    bucket.getId(), bucket.getServiceId());
            return;
        }

        ServiceInstance service = serviceOpt.get();
        String username = service.getUsername();
        String planName = service.getPlanName();

        // Replace dynamic fields in message template
        String message = buildNotificationMessage(
                template.getMessageContent(),
                planName,
                bucket.getExpiration(),
                daysToExpire
        );

        // Build notification event
        BucketExpiryNotification notification = BucketExpiryNotification.builder()
                .username(username)
                .userId(null)  // Can be populated if needed from UserEntity
                .serviceId(service.getId())
                .bucketInstanceId(bucket.getId())
                .bucketId(bucket.getBucketId())
                .planName(planName)
                .dateOfExpiry(bucket.getExpiration())
                .daysToExpire(daysToExpire)
                .message(message)
                .messageType(template.getMessageType())
                .templateId(template.getId())
                .notificationTime(LocalDateTime.now())
                .currentBalance(bucket.getCurrentBalance())
                .initialBalance(bucket.getInitialBalance())
                .build();

        // Send to Kafka topic
        try {
            kafkaTemplate.send(bucketExpiryTopic, username, notification)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send Kafka notification for username: {}, bucket ID: {}",
                                    username, bucket.getId(), ex);
                        } else {
                            log.debug("Successfully sent notification to Kafka for username: {}, bucket ID: {}",
                                    username, bucket.getId());
                        }
                    });

            log.info("Notification sent for username: {}, plan: {}, expires on: {}, days remaining: {}",
                    username, planName, expiryDate, daysToExpire);

        } catch (Exception e) {
            log.error("Error sending Kafka message for bucket ID: {}", bucket.getId(), e);
            throw e;
        }
    }

    /**
     * Replace dynamic fields in message template
     *
     * Dynamic fields supported:
     * - {PLAN_NAME}: Name of the plan
     * - {DATE_OF_EXPIRY}: Expiry date formatted as yyyy-MM-dd
     * - {DAYS_TO_EXPIRE}: Number of days until expiry
     *
     * @param messageTemplate the template message with placeholders
     * @param planName the plan name
     * @param expiryDateTime the expiry date time
     * @param daysToExpire days until expiry
     * @return message with placeholders replaced
     */
    private String buildNotificationMessage(
            String messageTemplate,
            String planName,
            LocalDateTime expiryDateTime,
            int daysToExpire) {

        if (messageTemplate == null) {
            return "Your plan will expire soon. Please renew to continue services.";
        }

        String expiryDateStr = expiryDateTime.format(DATE_FORMATTER);

        return messageTemplate
                .replace("{PLAN_NAME}", planName != null ? planName : "Unknown Plan")
                .replace("{DATE_OF_EXPIRY}", expiryDateStr)
                .replace("{DAYS_TO_EXPIRE}", String.valueOf(daysToExpire));
    }
}
