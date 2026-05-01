package com.axonect.aee.template.baseapp.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Schedules service-instance lifecycle jobs:
 *  - Bulk activation of PENDING service instances.
 *  - Cleanup (archive + delete) of service instances whose CYCLE_END_DATE
 *    or EXPIRY_DATE was yesterday, along with their bucket instances.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("java:S6813")
public class ServiceInstanceScheduler {

    private final ServiceInstanceLifecycleService lifecycleService;

    @Scheduled(cron = "${service-instance-activation.schedule:0 0 1 * * ?}")
    public void scheduleActivatePendingServiceInstances() {
        String batchId = UUID.randomUUID().toString();
        log.info("Starting scheduled activation of PENDING service instances. batchId={}", batchId);
        try {
            int updated = lifecycleService.activatePendingServiceInstances(batchId);
            log.info("Completed activation of PENDING service instances. updated={}, batchId={}",
                    updated, batchId);
        } catch (Exception ex) {
            log.error("Error during scheduled activation of PENDING service instances. batchId={}",
                    batchId, ex);
        }
    }

    @Scheduled(cron = "${service-instance-cleanup.schedule:0 15 1 * * ?}")
    public void scheduleCleanupExpiredServiceInstances() {
        String batchId = UUID.randomUUID().toString();
        log.info("Starting scheduled cleanup of expired service instances. batchId={}", batchId);
        try {
            ServiceInstanceLifecycleService.CleanupSummary summary =
                    lifecycleService.cleanupExpiredServiceInstances(batchId);
            log.info("Completed cleanup of expired service instances. servicesDeleted={}, " +
                            "bucketsDeleted={}, failedChunks={}, batchId={}",
                    summary.getServicesDeleted(), summary.getBucketsDeleted(),
                    summary.getFailedChunks(), batchId);
        } catch (Exception ex) {
            log.error("Error during scheduled cleanup of expired service instances. batchId={}",
                    batchId, ex);
        }
    }
}
