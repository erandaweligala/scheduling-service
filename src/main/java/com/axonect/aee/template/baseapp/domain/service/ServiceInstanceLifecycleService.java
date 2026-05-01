package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstanceHistory;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstanceHistory;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles service instance lifecycle transitions performed by the scheduler:
 *  - Bulk activation of PENDING service instances.
 *  - Archive-and-delete of service instances (and their bucket instances) whose
 *    CYCLE_END_DATE or EXPIRY_DATE landed on yesterday's date.
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
     * Processed in chunks to bound transaction size and memory.
     *
     * @return total rows updated
     */
    public int activatePendingServiceInstances(String batchId) {
        log.info("Activating PENDING service instances. batchId={}", batchId);

        int totalUpdated = 0;
        int chunk = 0;

        while (true) {
            Pageable pageable = PageRequest.of(0, activationChunkSize);
            Page<ServiceInstance> page = serviceInstanceRepository.findByStatus(Constants.PENDING, pageable);
            List<ServiceInstance> services = page.getContent();
            if (services.isEmpty()) {
                break;
            }

            List<Long> ids = services.stream().map(ServiceInstance::getId).collect(Collectors.toList());
            int updated;
            try {
                updated = self.activateChunkInTransaction(ids);
            } catch (Exception ex) {
                log.error("Activation chunk failed. ids={}, batchId={}. Aborting further chunks.",
                        ids.size(), batchId, ex);
                break;
            }
            totalUpdated += updated;
            log.info("Activated {} service instances in chunk {}. batchId={}", updated, chunk, batchId);

            // Once rows flip to ACTIVE the next page-0 fetch returns the next slice. If the
            // update flipped nothing (e.g. rows changed under us) we stop to avoid an infinite loop.
            if (updated == 0) {
                log.warn("No rows updated although page had {} services; stopping to avoid loop. batchId={}",
                        services.size(), batchId);
                break;
            }
            chunk++;
        }

        log.info("Finished activating PENDING service instances. totalUpdated={}, batchId={}",
                totalUpdated, batchId);
        return totalUpdated;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int activateChunkInTransaction(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return serviceInstanceRepository.bulkUpdateStatus(
                ids, Constants.PENDING, Constants.ACTIVE, LocalDateTime.now());
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
            Pageable pageable = PageRequest.of(0, cleanupChunkSize);
            Page<ServiceInstance> page =
                    serviceInstanceRepository.findByCycleEndOrExpiryWithinDay(dayStart, dayEnd, pageable);
            List<ServiceInstance> services = page.getContent();
            if (services.isEmpty()) {
                break;
            }

            try {
                ChunkResult result = self.archiveAndDeleteChunkInTransaction(services, dayStart, batchId);
                summary.add(result);
                log.info("Cleanup chunk {}: services={}, buckets={}, batchId={}",
                        chunk, result.servicesDeleted, result.bucketsDeleted, batchId);
                if (result.servicesDeleted == 0) {
                    log.warn("Cleanup chunk deleted no services; stopping to avoid loop. batchId={}", batchId);
                    break;
                }
            } catch (Exception ex) {
                log.error("Cleanup chunk failed. batchId={}. Aborting further chunks.", batchId, ex);
                summary.failedChunks++;
                break;
            }
            chunk++;
        }

        log.info("Finished cleanup. servicesDeleted={}, bucketsDeleted={}, failedChunks={}, batchId={}",
                summary.servicesDeleted, summary.bucketsDeleted, summary.failedChunks, batchId);
        return summary;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChunkResult archiveAndDeleteChunkInTransaction(List<ServiceInstance> services,
                                                          LocalDateTime dayStart,
                                                          String batchId) {
        Set<Long> serviceIds = services.stream().map(ServiceInstance::getId).collect(Collectors.toSet());

        List<BucketInstance> buckets = serviceIds.isEmpty()
                ? List.of()
                : bucketInstanceRepository.findByServiceIdIn(serviceIds);

        // Archive service instances first so their reason reflects which date triggered cleanup.
        List<ServiceInstanceHistory> serviceHistory = new ArrayList<>(services.size());
        for (ServiceInstance svc : services) {
            serviceHistory.add(toServiceHistory(svc, deletionReasonFor(svc, dayStart), batchId));
        }
        serviceInstanceHistoryRepository.saveAll(serviceHistory);

        if (!buckets.isEmpty()) {
            String bucketReason = serviceIds.size() == 1
                    ? serviceHistory.get(0).getDeletionReason()
                    : "PARENT_SERVICE_DELETED";
            List<BucketInstanceHistory> bucketHistory = new ArrayList<>(buckets.size());
            for (BucketInstance bucket : buckets) {
                bucketHistory.add(toBucketHistory(bucket, bucketReason, batchId));
            }
            bucketInstanceHistoryRepository.saveAll(bucketHistory);
        }

        int bucketsDeleted = buckets.isEmpty()
                ? 0
                : bucketInstanceRepository.deleteAllByServiceIdIn(new HashSet<>(serviceIds));
        int servicesDeleted = serviceInstanceRepository.deleteAllByIdIn(serviceIds);

        return new ChunkResult(servicesDeleted, bucketsDeleted);
    }

    private String deletionReasonFor(ServiceInstance svc, LocalDateTime dayStart) {
        LocalDateTime dayEnd = dayStart.plusDays(1);
        boolean cycleEndYesterday = svc.getServiceCycleEndDate() != null
                && !svc.getServiceCycleEndDate().isBefore(dayStart)
                && svc.getServiceCycleEndDate().isBefore(dayEnd);
        return cycleEndYesterday
                ? Constants.DELETION_REASON_CYCLE_END
                : Constants.DELETION_REASON_EXPIRY;
    }

    private ServiceInstanceHistory toServiceHistory(ServiceInstance s, String reason, String batchId) {
        return ServiceInstanceHistory.builder()
                .originalId(s.getId())
                .planId(s.getPlanId())
                .planName(s.getPlanName())
                .planType(s.getPlanType())
                .recurringFlag(s.getRecurringFlag())
                .username(s.getUsername())
                .serviceCycleStartDate(s.getServiceCycleStartDate())
                .serviceCycleEndDate(s.getServiceCycleEndDate())
                .nextCycleStartDate(s.getNextCycleStartDate())
                .serviceStartDate(s.getServiceStartDate())
                .expiryDate(s.getExpiryDate())
                .status(s.getStatus())
                .originalCreatedAt(s.getCreatedAt())
                .originalUpdatedAt(s.getUpdatedAt())
                .requestId(s.getRequestId())
                .isGroup(s.getIsGroup())
                .deletionReason(reason)
                .batchId(batchId)
                .build();
    }

    private BucketInstanceHistory toBucketHistory(BucketInstance b, String reason, String batchId) {
        return BucketInstanceHistory.builder()
                .originalId(b.getId())
                .bucketId(b.getBucketId())
                .serviceId(b.getServiceId())
                .bucketType(b.getBucketType())
                .rule(b.getRule())
                .priority(b.getPriority())
                .initialBalance(b.getInitialBalance())
                .currentBalance(b.getCurrentBalance())
                .usage(b.getUsage())
                .carryForward(b.getCarryForward())
                .maxCarryForward(b.getMaxCarryForward())
                .totalCarryForward(b.getTotalCarryForward())
                .carryForwardValidity(b.getCarryForwardValidity())
                .timeWindow(b.getTimeWindow())
                .consumptionLimit(b.getConsumptionLimit())
                .consumptionLimitWindow(b.getConsumptionLimitWindow())
                .expiration(b.getExpiration())
                .originalUpdatedAt(b.getUpdatedAt())
                .isUnlimited(b.getIsUnlimited())
                .deletionReason(reason)
                .batchId(batchId)
                .build();
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
