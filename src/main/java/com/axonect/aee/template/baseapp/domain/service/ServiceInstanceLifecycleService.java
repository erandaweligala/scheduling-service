package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Handles service instance lifecycle transitions performed by the scheduler:
 *  - Bulk activation of PENDING service instances.
 *  - Archive-and-delete of service instances (and their bucket instances) whose
 *    CYCLE_END_DATE or EXPIRY_DATE landed on yesterday's date.
 *
 * All DB writes are executed directly as set-based statements, one short transaction per chunk,
 * so no row data is shipped to the application and row locks are held only briefly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("java:S6813")
public class ServiceInstanceLifecycleService {

    private final ServiceInstanceRepository serviceInstanceRepository;
    private final BucketInstanceRepository bucketInstanceRepository;
    private final ServiceInstanceHistoryRepository serviceInstanceHistoryRepository;
    private final BucketInstanceHistoryRepository bucketInstanceHistoryRepository;

    @Autowired
    @Lazy
    private ServiceInstanceLifecycleService self;

    @Value("${service-instance-activation.chunk-size:500}")
    private int activationChunkSize;

    @Value("${service-instance-cleanup.chunk-size:200}")
    private int cleanupChunkSize;

    /**
     * Activate every SERVICE_INSTANCE row whose status is PENDING.
     * Processed in chunks to bound transaction size and lock duration.
     *
     * @return total rows updated
     */
    public int activatePendingServiceInstances(String batchId) {
        log.info("Activating PENDING service instances. batchId={}", batchId);

        int totalUpdated = 0;
        int chunk = 0;

        while (true) {
            int updated;
            try {
                updated = self.activateChunkInTransaction(activationChunkSize);
            } catch (Exception ex) {
                log.error("Activation chunk {} failed. batchId={}. Aborting further chunks.", chunk, batchId, ex);
                break;
            }
            totalUpdated += updated;
            log.info("Activated {} service instances in chunk {}. batchId={}", updated, chunk, batchId);

            // A chunk smaller than the limit means no PENDING rows were left.
            if (updated < activationChunkSize) {
                break;
            }
            chunk++;
        }

        log.info("Finished activating PENDING service instances. totalUpdated={}, batchId={}",
                totalUpdated, batchId);
        return totalUpdated;
    }

    /**
     * Flip up to {@code limit} PENDING rows to ACTIVE with a single UPDATE.
     *
     * @return rows updated
     */
    @Retryable(retryFor = TransientDataAccessException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2))
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int activateChunkInTransaction(int limit) {
        return serviceInstanceRepository.updateStatusLimited(
                Constants.PENDING, Constants.ACTIVE, LocalDateTime.now(), limit);
    }

    /**
     * Find service instances whose CYCLE_END_DATE or EXPIRY_DATE was yesterday,
     * archive them and their bucket instances to history tables, and delete the originals.
     */
    public CleanupSummary cleanupExpiredServiceInstances(String batchId) {
        LocalDate yesterday = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).minusDays(1);
        LocalDateTime dayStart = yesterday.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        log.info("Cleaning up service instances with CYCLE_END_DATE or EXPIRY_DATE on {}. batchId={}",
                yesterday, batchId);

        CleanupSummary summary = new CleanupSummary();
        int chunk = 0;

        while (true) {
            // Deleted rows drop out of the result, so the first page always holds the next chunk.
            List<Long> serviceIds = serviceInstanceRepository.findIdsByCycleEndOrExpiryWithinDay(
                    dayStart, dayEnd, PageRequest.of(0, cleanupChunkSize));
            if (serviceIds.isEmpty()) {
                break;
            }

            try {
                ChunkResult result = self.archiveAndDeleteChunkInTransaction(serviceIds, dayStart, dayEnd, batchId);
                summary.add(result);
                log.info("Cleanup chunk {}: services={}, buckets={}, batchId={}",
                        chunk, result.servicesDeleted, result.bucketsDeleted, batchId);
                if (result.servicesDeleted == 0) {
                    log.warn("Cleanup chunk deleted no services; stopping to avoid loop. batchId={}", batchId);
                    break;
                }
            } catch (Exception ex) {
                log.error("Cleanup chunk {} failed. batchId={}. Aborting further chunks.", chunk, batchId, ex);
                summary.failedChunks++;
                break;
            }

            // A chunk smaller than the limit means no candidates were left.
            if (serviceIds.size() < cleanupChunkSize) {
                break;
            }
            chunk++;
        }

        log.info("Finished cleanup. servicesDeleted={}, bucketsDeleted={}, failedChunks={}, batchId={}",
                summary.servicesDeleted, summary.bucketsDeleted, summary.failedChunks, batchId);
        return summary;
    }

    /**
     * Archive the given services and all of their bucket instances with INSERT ... SELECT, then
     * delete the bucket instances before their parent services. Everything commits or rolls
     * back together.
     */
    @Retryable(retryFor = TransientDataAccessException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2))
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChunkResult archiveAndDeleteChunkInTransaction(List<Long> serviceIds,
                                                          LocalDateTime dayStart,
                                                          LocalDateTime dayEnd,
                                                          String batchId) {
        LocalDateTime archivedAt = LocalDateTime.now();
        int servicesArchived = serviceInstanceHistoryRepository.archiveServiceInstances(
                serviceIds, dayStart, dayEnd, batchId, archivedAt);
        int bucketsArchived = bucketInstanceHistoryRepository.archiveBucketInstancesOfServices(
                serviceIds, dayStart, dayEnd, batchId, archivedAt);

        int bucketsDeleted = bucketInstanceRepository.deleteAllByServiceIdIn(serviceIds);
        int servicesDeleted = serviceInstanceRepository.deleteAllByIdIn(serviceIds);

        log.debug("Archived {} services and {} buckets; deleted {} services and {} buckets. batchId={}",
                servicesArchived, bucketsArchived, servicesDeleted, bucketsDeleted, batchId);
        return new ChunkResult(servicesDeleted, bucketsDeleted);
    }

    public static class ChunkResult {
        final int servicesDeleted;
        final int bucketsDeleted;

        public ChunkResult(int servicesDeleted, int bucketsDeleted) {
            this.servicesDeleted = servicesDeleted;
            this.bucketsDeleted = bucketsDeleted;
        }

        public int getServicesDeleted() {
            return servicesDeleted;
        }

        public int getBucketsDeleted() {
            return bucketsDeleted;
        }
    }

    public static class CleanupSummary {
        int servicesDeleted = 0;
        int bucketsDeleted = 0;
        int failedChunks = 0;

        void add(ChunkResult r) {
            servicesDeleted += r.servicesDeleted;
            bucketsDeleted += r.bucketsDeleted;
        }

        public int getServicesDeleted() {
            return servicesDeleted;
        }

        public int getBucketsDeleted() {
            return bucketsDeleted;
        }

        public int getFailedChunks() {
            return failedChunks;
        }
    }
}
