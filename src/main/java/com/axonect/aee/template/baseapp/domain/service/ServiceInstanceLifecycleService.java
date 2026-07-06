package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.DBWriteRequest;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final String SERVICE_INSTANCE_TABLE = "SERVICE_INSTANCE";
    private static final String BUCKET_INSTANCE_TABLE = "BUCKET_INSTANCE";
    private static final String SERVICE_INSTANCE_HISTORY_TABLE = "SERVICE_INSTANCE_HISTORY";
    private static final String BUCKET_INSTANCE_HISTORY_TABLE = "BUCKET_INSTANCE_HISTORY";
    private static final String EVENT_INSERT = "INSERT";
    private static final String EVENT_UPDATE = "UPDATE";
    private static final String EVENT_DELETE = "DELETE";

    private final ServiceInstanceRepository serviceInstanceRepository;
    private final BucketInstanceRepository bucketInstanceRepository;
    private final ServiceInstanceHistoryRepository serviceInstanceHistoryRepository;
    private final BucketInstanceHistoryRepository bucketInstanceHistoryRepository;
    private final RecurrentServiceProducer recurrentServiceProducer;

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
        LocalDateTime updatedAt = LocalDateTime.now();
        int updated = serviceInstanceRepository.bulkUpdateStatus(
                ids, Constants.PENDING, Constants.ACTIVE, updatedAt);

        if (updated > 0) {
            publishActivationEvents(ids, updatedAt);
        }
        return updated;
    }

    private void publishActivationEvents(List<Long> ids, LocalDateTime updatedAt) {
        for (Long id : ids) {
            try {
                recurrentServiceProducer.publishDBWriteEvent(
                        buildServiceInstanceActivationUpdateRequest(id, updatedAt),
                        String.valueOf(id));
            } catch (Exception ex) {
                log.error("Failed to publish activation DB write event for service ID: {}", id, ex);
            }
        }
    }

    private DBWriteRequest buildServiceInstanceActivationUpdateRequest(Long serviceId, LocalDateTime updatedAt) {
        Map<String, Object> columnValues = new HashMap<>();
        columnValues.put("STATUS", Constants.ACTIVE);
        columnValues.put("UPDATED_AT", updatedAt.format(FORMATTER));

        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", serviceId);

        return DBWriteRequest.builder()
                .eventType(EVENT_UPDATE)
                .timestamp(Instant.now().toString())
                .userName("system")
                .tableName(SERVICE_INSTANCE_TABLE)
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .build();
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
                : buckets.size();
        int servicesDeleted = serviceIds.size();

        publishCleanupEvents(services, serviceHistory, buckets, batchId);

        return new ChunkResult(servicesDeleted, bucketsDeleted);
    }

    private void publishCleanupEvents(List<ServiceInstance> services,
                                      List<ServiceInstanceHistory> serviceHistory,
                                      List<BucketInstance> buckets,
                                      String batchId) {
        Map<Long, ServiceInstanceHistory> historyByOriginalId = serviceHistory.stream()
                .collect(Collectors.toMap(ServiceInstanceHistory::getOriginalId, h -> h));
        Map<Long, List<BucketInstance>> bucketsByServiceId = buckets.stream()
                .collect(Collectors.groupingBy(BucketInstance::getServiceId));

        for (ServiceInstance svc : services) {
            ServiceInstanceHistory history = historyByOriginalId.get(svc.getId());
            if (history == null) {
                continue;
            }
            List<BucketInstance> svcBuckets = bucketsByServiceId.getOrDefault(svc.getId(), List.of());

            try {
                recurrentServiceProducer.publishDBWriteEvent(
                        buildServiceCleanupRequest(svc, history, svcBuckets, batchId),
                        String.valueOf(svc.getId()));
            } catch (Exception ex) {
                log.error("Failed to publish cleanup DB write event for service ID: {}, batchId={}",
                        svc.getId(), batchId, ex);
            }
        }
    }

    private DBWriteRequest buildServiceCleanupRequest(ServiceInstance svc,
                                                      ServiceInstanceHistory history,
                                                      List<BucketInstance> svcBuckets,
                                                      String batchId) {
        List<DBWriteRequest> relatedWrites = new ArrayList<>();
        relatedWrites.add(buildServiceInstanceHistoryInsertRequest(history, batchId));

        String bucketReason = history.getDeletionReason();
        for (BucketInstance bucket : svcBuckets) {
            relatedWrites.add(buildBucketInstanceHistoryInsertRequest(bucket, bucketReason, batchId));
            relatedWrites.add(buildBucketInstanceDeleteRequest(bucket));
        }

        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", svc.getId());

        return DBWriteRequest.builder()
                .eventType(EVENT_DELETE)
                .timestamp(Instant.now().toString())
                .userName(svc.getUsername())
                .tableName(SERVICE_INSTANCE_TABLE)
                .whereConditions(whereConditions)
                .relatedWrites(relatedWrites)
                .build();
    }

    private DBWriteRequest buildServiceInstanceHistoryInsertRequest(ServiceInstanceHistory history, String batchId) {
        Map<String, Object> columnValues = new HashMap<>();
        columnValues.put("ORIGINAL_ID", history.getOriginalId());
        columnValues.put("PLAN_ID", history.getPlanId());
        columnValues.put("PLAN_NAME", history.getPlanName());
        columnValues.put("PLAN_TYPE", history.getPlanType());
        columnValues.put("RECURRING_FLAG", history.getRecurringFlag());
        columnValues.put("USERNAME", history.getUsername());
        columnValues.put("CYCLE_START_DATE", formatOrNull(history.getServiceCycleStartDate()));
        columnValues.put("CYCLE_END_DATE", formatOrNull(history.getServiceCycleEndDate()));
        columnValues.put("NEXT_CYCLE_START_DATE", formatOrNull(history.getNextCycleStartDate()));
        columnValues.put("SERVICE_START_DATE", formatOrNull(history.getServiceStartDate()));
        columnValues.put("EXPIRY_DATE", formatOrNull(history.getExpiryDate()));
        columnValues.put("STATUS", history.getStatus());
        columnValues.put("ORIGINAL_CREATED_AT", formatOrNull(history.getOriginalCreatedAt()));
        columnValues.put("ORIGINAL_UPDATED_AT", formatOrNull(history.getOriginalUpdatedAt()));
        columnValues.put("REQUEST_ID", history.getRequestId());
        columnValues.put("IS_GROUP", history.getIsGroup());
        columnValues.put("DELETION_REASON", history.getDeletionReason());
        columnValues.put("BATCH_ID", batchId);

        return DBWriteRequest.builder()
                .eventType(EVENT_INSERT)
                .timestamp(Instant.now().toString())
                .userName(history.getUsername())
                .tableName(SERVICE_INSTANCE_HISTORY_TABLE)
                .columnValues(columnValues)
                .build();
    }

    private DBWriteRequest buildBucketInstanceHistoryInsertRequest(BucketInstance bucket, String reason, String batchId) {
        Map<String, Object> columnValues = new HashMap<>();
        columnValues.put("ORIGINAL_ID", bucket.getId());
        columnValues.put("BUCKET_ID", bucket.getBucketId());
        columnValues.put("SERVICE_ID", bucket.getServiceId());
        columnValues.put("BUCKET_TYPE", bucket.getBucketType());
        columnValues.put("RULE", bucket.getRule());
        columnValues.put("PRIORITY", bucket.getPriority());
        columnValues.put("INITIAL_BALANCE", bucket.getInitialBalance());
        columnValues.put("CURRENT_BALANCE", bucket.getCurrentBalance());
        columnValues.put("USAGE", bucket.getUsage());
        columnValues.put("CARRY_FORWARD", bucket.getCarryForward());
        columnValues.put("MAX_CARRY_FORWARD", bucket.getMaxCarryForward());
        columnValues.put("TOTAL_CARRY_FORWARD", bucket.getTotalCarryForward());
        columnValues.put("CARRY_FORWARD_VALIDITY", bucket.getCarryForwardValidity());
        columnValues.put("TIME_WINDOW", bucket.getTimeWindow());
        columnValues.put("CONSUMPTION_LIMIT", bucket.getConsumptionLimit());
        columnValues.put("CONSUMPTION_LIMIT_WINDOW", bucket.getConsumptionLimitWindow());
        columnValues.put("EXPIRATION", formatOrNull(bucket.getExpiration()));
        columnValues.put("ORIGINAL_UPDATED_AT", formatOrNull(bucket.getUpdatedAt()));
        columnValues.put("IS_UNLIMITED", bucket.getIsUnlimited());
        columnValues.put("DELETION_REASON", reason);
        columnValues.put("BATCH_ID", batchId);

        return DBWriteRequest.builder()
                .eventType(EVENT_INSERT)
                .timestamp(Instant.now().toString())
                .userName("system")
                .tableName(BUCKET_INSTANCE_HISTORY_TABLE)
                .columnValues(columnValues)
                .build();
    }

    private DBWriteRequest buildBucketInstanceDeleteRequest(BucketInstance bucket) {
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", bucket.getId());

        return DBWriteRequest.builder()
                .eventType(EVENT_DELETE)
                .timestamp(Instant.now().toString())
                .userName("system")
                .tableName(BUCKET_INSTANCE_TABLE)
                .whereConditions(whereConditions)
                .build();
    }

    private String formatOrNull(LocalDateTime value) {
        return value == null ? null : value.format(FORMATTER);
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
