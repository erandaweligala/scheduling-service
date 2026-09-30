package com.axonect.aee.template.baseapp.domain.entities.dto;

/**
 * Final balance of a BUCKET_INSTANCE row, persisted when the session consuming it is terminated.
 *
 * @param bucketInstanceId BUCKET_INSTANCE.ID
 * @param serviceId        BUCKET_INSTANCE.SERVICE_ID
 * @param currentBalance   value for CURRENT_BALANCE
 * @param usage            value for USAGE
 */
public record BucketBalanceUpdate(long bucketInstanceId, long serviceId, long currentBalance, long usage) {
}
