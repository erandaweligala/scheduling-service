package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.transport.request.entities.DBWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceExpirationHandler {

    /**
     * Table name in the Quarkus-managed DB that holds bucket instances.
     * Override via property:  db-write.bucket-instance-table=YOUR_TABLE
     */
    @Value("${db-write.bucket-instance-table:BUCKET_INSTANCE}")
    private String bucketInstanceTable;

    /**
     * Table name in the Quarkus-managed DB that holds service instances.
     * Override via property:  db-write.service-instance-table=YOUR_TABLE
     */
    @Value("${db-write.service-instance-table:SERVICE_INSTANCE}")
    private String serviceInstanceTable;

    /**
     * Publishes to the dc-provisioning topic, consumed by Quarkus consumeAndReply()
     * a reply, so no reply headers are needed here.
     */
    @Value("${kafka.topic.db-write-provisioning:dc-provisioning}")
    private String dbWriteProvisioningTopic;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Triggered when a Redis TTL key of the form  service::{serviceId}  expires.
     *
     * @param serviceId the PK of the expired ServiceInstance
     */
    public void handleServiceExpiration(Long serviceId) {
        log.info("TTL expired — publishing BucketInstance then ServiceInstance DELETE events for serviceId: {}", serviceId);

        publishDeleteEvent(bucketInstanceTable, "SERVICE_ID", serviceId, "BucketInstance")
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Must NOT rethrow — a crash here kills the Redis listener thread.
                        log.error("Failed to publish BUCKET_INSTANCE DELETE for serviceId: {} — " +
                                "manual cleanup of BUCKET_INSTANCE and SERVICE_INSTANCE may be required", serviceId, ex);
                    } else {
                        log.info("BUCKET_INSTANCE DELETE published for serviceId: {} → topic={}, partition={}, offset={}",
                                serviceId,
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());

                        // Only proceed to delete the ServiceInstance once BucketInstance delete is confirmed sent.
                        // "ID" is the PK column on SERVICE_INSTANCE (field: Long id, no @Column override).
                        publishDeleteEvent(serviceInstanceTable, "ID", serviceId, "ServiceInstance")
                                .whenComplete((res2, ex2) -> {
                                    if (ex2 != null) {
                                        log.error("Failed to publish SERVICE_INSTANCE DELETE for serviceId: {} — " +
                                                "manual cleanup of SERVICE_INSTANCE may be required", serviceId, ex2);
                                    } else {
                                        log.info("SERVICE_INSTANCE DELETE published for serviceId: {} → topic={}, partition={}, offset={}",
                                                serviceId,
                                                res2.getRecordMetadata().topic(),
                                                res2.getRecordMetadata().partition(),
                                                res2.getRecordMetadata().offset());
                                    }
                                });
                    }
                });
    }

    /**
     * Builds and sends a DELETE {@link DBWriteRequest} to {@code dbWriteProvisioningTopic}.
     *
     * @param tableName   target table
     * @param whereColumn column name for the WHERE condition
     * @param whereValue  value for the WHERE condition
     * @param label       human-readable label used in log messages
     * @return the CompletableFuture returned by {@link KafkaTemplate#send}
     */
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