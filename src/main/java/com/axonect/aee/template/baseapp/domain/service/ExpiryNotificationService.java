package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.ChildTemplateTableRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketExpiryNotification;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
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
import java.util.Objects;

/**
 * Service for processing and sending expiry notifications via Kafka
 *
 * Workflow:
 * 1. Fetch all EXPIRE type templates from CHILD_TEMPLATE_TABLE
 * 2. For each template with DAYS_TO_EXPIRE configuration:
 *    - Calculate the target expiry date (today + DAYS_TO_EXPIRE)
 *    - Find service instances with CYCLE_END_DATE on that date
 *    - Get user and plan information from ServiceInstance
 *    - Replace dynamic fields in message template
 *    - Send Kafka notification event
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExpiryNotificationService {

    private final ChildTemplateTableRepository childTemplateTableRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final UserCacheService userCacheService;

    @Value("${kafka.topic.bucket-expiry-notification:bucket-expiry-notifications}")
    private String bucketExpiryTopic;

    @Value("${expiry-notification.batch-size:100}")
    private Integer batchSize;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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
     * Process notifications for a specific template configuration.
     * Directly queries ServiceInstance by CYCLE_END_DATE instead of going through BucketInstance.
     *
     * @param template the child template with DAYS_TO_EXPIRE configuration
     * @return number of notifications sent for this template
     */
    private int processTemplateNotifications(ChildTemplateTable template) {
        int daysToExpire = template.getDaysToExpire();
        log.info("Processing template ID: {} with DAYS_TO_EXPIRE: {}", template.getId(), daysToExpire);

        // Calculate target expiry date
        // Example: If DAYS_TO_EXPIRE = 2 and today is 2026-01-28, target date is 2026-01-30
        // We want to notify about services with CYCLE_END_DATE on 2026-01-30
        LocalDate targetExpiryDate = LocalDate.now().plusDays(daysToExpire);
        LocalDateTime targetExpiryStart = targetExpiryDate.atStartOfDay();
        LocalDateTime targetExpiryEnd = targetExpiryDate.plusDays(1).atStartOfDay();

        log.info("Looking for service instances with CYCLE_END_DATE on: {}", targetExpiryDate);

        int notificationsSent = 0;
        int page = 0;
        boolean hasMore = true;

        // Process in batches to handle large datasets
        while (hasMore) {
            Pageable pageable = PageRequest.of(page, batchSize);
            Page<ServiceInstance> servicePage = serviceInstanceRepository
                    .findByServiceCycleEndDateBetween(targetExpiryStart, targetExpiryEnd, pageable);
            List<ServiceInstance> services = servicePage.getContent();

            if (services.isEmpty()) {
                hasMore = false;
                continue;
            }

            log.info("Processing page {} with {} service instances", page, services.size());

            for (ServiceInstance service : services) {
                try {
                    sendNotificationForService(service, template, daysToExpire, targetExpiryDate);
                    notificationsSent++;
                } catch (Exception e) {
                    log.error("Failed to send notification for service instance ID: {}", service.getId(), e);
                    // Continue processing other services even if one fails
                }
            }

            hasMore = servicePage.hasNext();
            page++;
        }

        log.info("Template ID {} processing completed. Sent {} notifications", template.getId(), notificationsSent);
        return notificationsSent;
    }

    /**
     * Send Kafka notification for a specific service instance.
     * Looks up the user's superTemplateId from Redis cache and only sends the notification
     * if the template's superTemplateId matches the user's superTemplateId.
     *
     * @param service the service instance
     * @param template the message template
     * @param daysToExpire days until expiry
     * @param expiryDate the expiry date
     */
    private void sendNotificationForService(
            ServiceInstance service,
            ChildTemplateTable template,
            int daysToExpire,
            LocalDate expiryDate) {

        String username = service.getUsername();
        String planName = service.getPlanName();

        // Get user's superTemplateId from cache to match with the correct child template
        UserSessionData userSessionData = userCacheService.getUserData(username);
        if (userSessionData == null) {
            log.warn("User session data not found in cache for username: {}. Skipping notification for service ID: {}",
                    username, service.getId());
            return;
        }

        long userSuperTemplateId = userSessionData.getSuperTemplateId();

        // Only send notification if the template's superTemplateId matches the user's superTemplateId
        if (!Objects.equals(template.getSuperTemplateId(), userSuperTemplateId)) {
            log.debug("Template ID {} superTemplateId ({}) does not match user {} superTemplateId ({}). Skipping.",
                    template.getId(), template.getSuperTemplateId(), username, userSuperTemplateId);
            return;
        }

        // Replace dynamic fields in message template
        String message = buildNotificationMessage(
                template.getMessageContent(),
                planName,
                service.getServiceCycleEndDate(),
                daysToExpire
        );

        // Build notification event
        BucketExpiryNotification notification = BucketExpiryNotification.builder()
                .username(username)
                .userId(null)
                .serviceId(service.getId())
                .planName(planName)
                .dateOfExpiry(service.getServiceCycleEndDate())
                .daysToExpire(daysToExpire)
                .message(message)
                .messageType(template.getMessageType())
                .templateId(template.getId())
                .notificationTime(LocalDateTime.now())
                .build();

        // Send to Kafka topic
        try {
            kafkaTemplate.send(bucketExpiryTopic, username, notification)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send Kafka notification for username: {}, service ID: {}",
                                    username, service.getId(), ex);
                        } else {
                            log.debug("Successfully sent notification to Kafka for username: {}, service ID: {}",
                                    username, service.getId());
                        }
                    });

            log.info("Notification sent for username: {}, plan: {}, template: {}, expires on: {}, days remaining: {}",
                    username, planName, template.getId(), expiryDate, daysToExpire);

        } catch (Exception e) {
            log.error("Error sending Kafka message for service ID: {}", service.getId(), e);
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
