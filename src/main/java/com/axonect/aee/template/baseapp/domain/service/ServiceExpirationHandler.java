package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.DBWriteRequest;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ServiceExpirationHandler {

    @Value("${db-write.bucket-instance-table:BUCKET_INSTANCE}")
    private String bucketInstanceTable;

    @Value("${db-write.service-instance-table:SERVICE_INSTANCE}")
    private String serviceInstanceTable;

    @Value("${kafka.topic.db-write-provisioning:dc-provisioning}")
    private String dbWriteProvisioningTopic;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ServiceInstanceRepository serviceInstanceRepository;

    public ServiceExpirationHandler(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Qualifier("redisTemplateString") RedisTemplate<String, String> redisTemplate,
            ServiceInstanceRepository serviceInstanceRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.serviceInstanceRepository = serviceInstanceRepository;
    }

    /**
     * Triggered when a Redis TTL key of the form service::{serviceId}::{bucketInstanceId} expires.
     *
     * Recurring service:
     *   → DELETE FROM BUCKET_INSTANCE WHERE ID = {bucketInstanceId} only
     *   → ServiceInstance is preserved (will be renewed/updated separately)
     *
     * Non-recurring service:
     *   → DELETE FROM BUCKET_INSTANCE WHERE ID = {bucketInstanceId}
     *   → If no more service::{serviceId}::* keys remain → DELETE FROM SERVICE_INSTANCE WHERE ID = {serviceId}
     *
     * @param serviceId        the PK of the ServiceInstance
     * @param bucketInstanceId the PK of the specific expired BucketInstance
     */
    public void handleServiceExpiration(Long serviceId, Long bucketInstanceId) {
        log.info("TTL expired — serviceId: {}, bucketInstanceId: {}", serviceId, bucketInstanceId);

        Optional<ServiceInstance> serviceOpt = serviceInstanceRepository.findById(serviceId);

        if (serviceOpt.isEmpty()) {
            log.warn("ServiceInstance not found for serviceId: {} — skipping expiration handling.", serviceId);
            return;
        }

        ServiceInstance service = serviceOpt.get();
        boolean isRecurring = Boolean.TRUE.equals(service.getRecurringFlag());

        log.info("ServiceInstance ID: {} recurringFlag: {}", serviceId, isRecurring);

        if (isRecurring) {
            handleRecurringExpiration(serviceId, bucketInstanceId);
        } else {
            handleNonRecurringExpiration(serviceId, bucketInstanceId);
        }
    }

    // ─── Recurring ───────────────────────────────────────────────────────────────

    /**
     * Recurring: delete only the specific BucketInstance.
     * The ServiceInstance itself is preserved and will be renewed separately.
     */
    private void handleRecurringExpiration(Long serviceId, Long bucketInstanceId) {
        log.info("[RECURRING] Deleting only BucketInstance ID: {} for serviceId: {}", bucketInstanceId, serviceId);

        publishDeleteEvent(bucketInstanceTable, "ID", bucketInstanceId, "BucketInstance")
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[RECURRING] Failed to publish BUCKET_INSTANCE DELETE for " +
                                "bucketInstanceId: {} (serviceId: {})", bucketInstanceId, serviceId, ex);
                    } else {
                        log.info("[RECURRING] BUCKET_INSTANCE DELETE published for bucketInstanceId: {} " +
                                        "→ topic={}, partition={}, offset={}",
                                bucketInstanceId,
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    // ─── Non-Recurring ───────────────────────────────────────────────────────────

    /**
     * Non-recurring: delete the specific BucketInstance, then if it was
     * the last one, also delete the ServiceInstance.
     */
    private void handleNonRecurringExpiration(Long serviceId, Long bucketInstanceId) {
        log.info("[NON-RECURRING] Deleting BucketInstance ID: {} for serviceId: {}", bucketInstanceId, serviceId);

        publishDeleteEvent(bucketInstanceTable, "ID", bucketInstanceId, "BucketInstance")
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[NON-RECURRING] Failed to publish BUCKET_INSTANCE DELETE for " +
                                "bucketInstanceId: {} (serviceId: {})", bucketInstanceId, serviceId, ex);
                    } else {
                        log.info("[NON-RECURRING] BUCKET_INSTANCE DELETE published for bucketInstanceId: {} " +
                                        "→ topic={}, partition={}, offset={}",
                                bucketInstanceId,
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());

                        deleteServiceIfLastBucket(serviceId);
                    }
                });
    }

    /**
     * Checks remaining service::{serviceId}::* keys in Redis.
     * If none remain, the last bucket just expired → delete the ServiceInstance too.
     */
    private void deleteServiceIfLastBucket(Long serviceId) {
        try {
            String pattern = "service::" + serviceId + "::*";
            Set<String> remainingKeys = redisTemplate.keys(pattern);

            if (remainingKeys != null && !remainingKeys.isEmpty()) {
                log.info("[NON-RECURRING] Service ID: {} still has {} active bucket TTL key(s) " +
                        "— skipping ServiceInstance DELETE.", serviceId, remainingKeys.size());
                return;
            }

            log.info("[NON-RECURRING] No remaining bucket TTL keys for Service ID: {} " +
                    "— publishing ServiceInstance DELETE.", serviceId);

            publishDeleteEvent(serviceInstanceTable, "ID", serviceId, "ServiceInstance")
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            log.error("[NON-RECURRING] Failed to publish SERVICE_INSTANCE DELETE " +
                                    "for serviceId: {}", serviceId, ex);
                        } else {
                            log.info("[NON-RECURRING] SERVICE_INSTANCE DELETE published for serviceId: {} " +
                                            "→ topic={}, partition={}, offset={}",
                                    serviceId,
                                    res.getRecordMetadata().topic(),
                                    res.getRecordMetadata().partition(),
                                    res.getRecordMetadata().offset());
                        }
                    });

        } catch (Exception e) {
            log.error("[NON-RECURRING] Failed to check remaining TTL keys for Service ID: {}", serviceId, e);
        }
    }

    // ─── Shared ──────────────────────────────────────────────────────────────────

    private CompletableFuture<SendResult<String, Object>> publishDeleteEvent(
            String tableName, String whereColumn, Long whereValue, String label) {

        DBWriteRequest deleteRequest = DBWriteRequest.builder()
                .eventType("DELETE")
                .tableName(tableName)
                .whereConditions(Map.of(whereColumn, whereValue))
                .timestamp(Instant.now().toString())
                .userName("system")
                .build();

        log.debug("Publishing {} DELETE event for {}={}", label, whereColumn, whereValue);
        return kafkaTemplate.send(dbWriteProvisioningTopic, String.valueOf(whereValue), deleteRequest);
    }
}