package com.axonect.aee.template.baseapp.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduler for automatically cleaning up expired bucket instances from the database.
 * Runs periodically based on the configured cron expression.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("java:S6813")
public class BucketInstanceScheduler {

    private final DeleteBucketInstanceService deleteBucketInstanceService;
    private final ExpiryNotificationService expiryNotificationService;


    @Autowired
    @Lazy
    private  BucketInstanceScheduler self;

    @Scheduled(cron = "${delete-expired-buckets.schedule:0 0 2 * * ?}")
    public void scheduleDeleteExpiredBuckets() {
        log.info("Starting scheduled deletion of expired bucket instances");
        try {
            self.deleteExpiredBucketsTransactional();
            log.info("Successfully completed scheduled deletion of expired bucket instances");
        } catch (Exception e) {
            log.error("Error during scheduled deletion of expired bucket instances", e);
        }
    }

    /**
     * Transactional method to delete expired buckets.
     * Separated to avoid self-invocation issues with @Transactional.
     */
    @Transactional
    public void deleteExpiredBucketsTransactional() {
        deleteBucketInstanceService.deleteExpiredBucketInstance();
    }

    /**
     * Scheduled job to process and send bucket expiry notifications via Kafka.
     * Runs daily to check for buckets that will expire based on configured DAYS_TO_EXPIRE templates.
     * Example: If a template has DAYS_TO_EXPIRE = 2 and today is 2026-01-28,
     * this will send notifications for all buckets expiring on 2026-01-30.
     * Default schedule: 9:00 AM daily (configurable via application.yml)
     */
    @Scheduled(cron = "${expiry-notification.schedule:0 0 9 * * ?}")
    public void scheduleExpiryNotifications() {
        log.info("Starting scheduled expiry notification processing");
        try {
            int notificationsSent = expiryNotificationService.processExpiryNotifications();
            log.info("Successfully completed expiry notification processing. Total notifications sent: {}",
                    notificationsSent);
        } catch (Exception e) {
            log.error("Error during scheduled expiry notification processing", e);
        }
    }
}
