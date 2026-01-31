package com.axonect.aee.template.baseapp.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class BucketInstanceScheduler {

    private final DeleteBucketInstanceService deleteBucketInstanceService;

    @Autowired
    private BucketInstanceScheduler self;

    /**
     * Scheduled task to delete expired bucket instances.
     * Runs daily at 2:00 AM to clean up expired buckets.
     * Schedule can be configured via: delete-expired-buckets.schedule property
     */
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
}
