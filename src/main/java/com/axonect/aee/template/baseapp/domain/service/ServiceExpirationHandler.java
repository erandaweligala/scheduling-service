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

    @Value("${db-write.bucket-instance-table:BUCKET_INSTANCE}")
    private String bucketInstanceTable;

    @Value("${db-write.service-instance-table:SERVICE_INSTANCE}")
    private String serviceInstanceTable;

    @Value("${kafka.topic.db-write-provisioning:dc-provisioning}")
    private String dbWriteProvisioningTopic;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Triggered when a Redis TTL key of the form service::{serviceId}::{bucketInstanceId} expires.
     *
     * Always deletes both the BucketInstance and the ServiceInstance together.
     *
     * @param serviceId        the PK of the ServiceInstance
     * @param bucketInstanceId the PK of the specific expired BucketInstance
     */
    //todo both are delete to same publish event
    public void handleServiceExpiration(Long serviceId, Long bucketInstanceId) {
        log.info("TTL expired — serviceId: {}, bucketInstanceId: {}", serviceId, bucketInstanceId);

        publishDeleteEvent(bucketInstanceTable, "ID", bucketInstanceId, "BucketInstance")
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish BUCKET_INSTANCE DELETE for " +
                                "bucketInstanceId: {} (serviceId: {})", bucketInstanceId, serviceId, ex);
                    } else {
                        log.info("BUCKET_INSTANCE DELETE published for bucketInstanceId: {} " +
                                        "→ topic={}, partition={}, offset={}",
                                bucketInstanceId,
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());

                        publishDeleteEvent(serviceInstanceTable, "ID", serviceId, "ServiceInstance")
                                .whenComplete((res, e) -> {
                                    if (e != null) {
                                        log.error("Failed to publish SERVICE_INSTANCE DELETE " +
                                                "for serviceId: {}", serviceId, e);
                                    } else {
                                        log.info("SERVICE_INSTANCE DELETE published for serviceId: {} " +
                                                        "→ topic={}, partition={}, offset={}",
                                                serviceId,
                                                res.getRecordMetadata().topic(),
                                                res.getRecordMetadata().partition(),
                                                res.getRecordMetadata().offset());
                                    }
                                });
                    }
                });
    }

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
