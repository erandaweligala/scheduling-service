package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.transport.request.entities.DBWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    public void handleServiceExpiration(Long serviceId, Long bucketInstanceId) {
        log.info("TTL expired — serviceId: {}, bucketInstanceId: {}", serviceId, bucketInstanceId);

        DBWriteRequest serviceInstanceDelete = DBWriteRequest.builder()
                .eventType("DELETE")
                .tableName(serviceInstanceTable)
                .whereConditions(Map.of("ID", serviceId))
                .timestamp(Instant.now().toString())
                .userName("system")
                .build();

        DBWriteRequest bucketInstanceDelete = DBWriteRequest.builder()
                .eventType("DELETE")
                .tableName(bucketInstanceTable)
                .whereConditions(Map.of("ID", bucketInstanceId))
                .timestamp(Instant.now().toString())
                .userName("system")
                .relatedWrites(List.of(serviceInstanceDelete))
                .build();

        log.debug("Publishing combined DELETE event for bucketInstanceId={}, serviceId={}", bucketInstanceId, serviceId);
        kafkaTemplate.send(dbWriteProvisioningTopic, String.valueOf(bucketInstanceId), bucketInstanceDelete)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish DELETE event for bucketInstanceId: {} (serviceId: {})",
                                bucketInstanceId, serviceId, ex);
                    } else {
                        log.info("Combined DELETE event published for bucketInstanceId: {}, serviceId: {} " +
                                        "→ topic={}, partition={}, offset={}",
                                bucketInstanceId, serviceId,
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
