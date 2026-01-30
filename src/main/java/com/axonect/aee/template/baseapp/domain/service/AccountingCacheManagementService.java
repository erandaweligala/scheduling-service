package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.CacheBucketRequest;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.exception.CacheBucketException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountingCacheManagementService {

    private final WebClient cacheApiWebClient;

    @Value("${cache.api.base-url}")
    private String cacheApiUrl;

    @Value("${cache.api.timeout.request:10}")
    private int requestTimeoutSeconds;

    @Value("${cache.api.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${cache.api.retry.initial-backoff:2}")
    private int initialBackoffSeconds;

    @Value("${cache.api.retry.max-backoff:10}")
    private int maxBackoffSeconds;

    // Sync buckets to cache - processes buckets sequentially
    public void syncBuckets(String bucketUsername,String serviceStatus, List<BucketInstance> bucketInstances) {

        if (!StringUtils.hasText(bucketUsername)) {
            throw new IllegalArgumentException("Bucket username cannot be null or empty");
        }

        if (bucketInstances == null || bucketInstances.isEmpty()) {
            log.warn("No bucket instances provided for user: {}", bucketUsername);
            return;
        }

        log.info("Starting sync for user: {}, bucket count: {}", bucketUsername, bucketInstances.size());

        long startTime = System.currentTimeMillis();
        List<String> failedBucketIds = new ArrayList<>();
        int successCount = 0;

        // Process buckets one by one
        for (int i = 0; i < bucketInstances.size(); i++) {
            BucketInstance bucket = bucketInstances.get(i);

            log.info("Processing bucket {}/{} - bucketId: {} for user: {}",
                    i + 1, bucketInstances.size(), bucket.getBucketId(), bucketUsername);

            try {
                processBucket(bucketUsername,serviceStatus, bucket);
                successCount++;
                log.info("Successfully synced bucket {}/{} - bucketId: {}",
                        i + 1, bucketInstances.size(), bucket.getBucketId());
            } catch (Exception e) {
                log.error("Failed to sync bucket {}/{} - bucketId: {}",
                        i + 1, bucketInstances.size(), bucket.getBucketId(), e);
                failedBucketIds.add(bucket.getBucketId());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        int failedCount = failedBucketIds.size();

        log.info("Batch sync completed for user: {}. Success: {}, Failed: {}, Total: {}, Duration: {}ms",
                bucketUsername, successCount, failedCount, bucketInstances.size(), duration);

        // Throw exception if any bucket failed
        if (!failedBucketIds.isEmpty()) {
            String failedBuckets = String.join(", ", failedBucketIds);
            String errorMessage = String.format(
                    "Batch sync completed with %d failures out of %d buckets. Failed buckets: [%s]",
                    failedCount, bucketInstances.size(), failedBuckets);

            log.error("Batch sync failed for user: {}. {}", bucketUsername, errorMessage);
            throw new CacheBucketException(errorMessage, bucketUsername, null);
        }

        log.info("All buckets synced successfully for user: {} in {}ms", bucketUsername, duration);
    }

    // Process single bucket
    private void processBucket(String bucketUsername, String serviceStatus, BucketInstance bucket) {

        // Validate bucket
        if (bucket == null) {
            throw new IllegalArgumentException("Bucket instance is null");
        }

        if (!StringUtils.hasText(bucket.getBucketId())) {
            throw new IllegalArgumentException("Bucket ID is null or empty");
        }

        // Build request
        CacheBucketRequest request = buildRequest(bucket, bucketUsername,serviceStatus);

        // Build URL
        String fullUrl = cacheApiUrl.replace("{bucketUsername}", bucketUsername);

        log.debug("Making PATCH request to: {}", fullUrl);

        // Make API call and wait for response
        cacheApiWebClient
                .patch()
                .uri(fullUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .retryWhen(Retry.backoff(maxRetryAttempts, Duration.ofSeconds(initialBackoffSeconds))
                        .maxBackoff(Duration.ofSeconds(maxBackoffSeconds))
                        .filter(this::shouldRetry)
                        .doBeforeRetry(signal ->
                                log.warn("Retrying bucketId: {} for user: {}, attempt: {}/{}",
                                        bucket.getBucketId(), bucketUsername,
                                        signal.totalRetries() + 1, maxRetryAttempts)))
                .doOnSuccess(response ->
                        log.debug("Successfully synced bucketId: {} with status: {}",
                                bucket.getBucketId(), response.getStatusCode()))
                .onErrorMap(throwable -> {
                    String errorMsg = String.format("Failed to sync bucket %s for user %s: %s",
                            bucket.getBucketId(), bucketUsername, throwable.getMessage());
                    log.error(errorMsg, throwable);
                    return new RuntimeException(errorMsg, throwable);
                })
                .block(); // Block and wait for completion
    }

    private CacheBucketRequest buildRequest(BucketInstance instance, String bucketUsername, String serviceStatus) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        return CacheBucketRequest.builder()
                .initialBalance(instance.getInitialBalance() != null ? instance.getInitialBalance() : 0L)
                .quota(instance.getInitialBalance() != null ? instance.getInitialBalance() : 0L)
                .serviceExpiry(instance.getExpiration() != null ?
                        instance.getExpiration().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ".951" :
                        java.time.LocalDateTime.now().plusYears(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ".951")
                .bucketId(instance.getBucketId())
                .serviceId(instance.getServiceId())
                .priority(instance.getPriority() != null ? instance.getPriority().intValue() : 4)
                .serviceStartDate(instance.getExpiration() != null ?
                        instance.getExpiration().minusMonths(2).format(formatter) :
                        java.time.LocalDateTime.now().format(formatter))
                .serviceStatus(serviceStatus)
                .timeWindow(StringUtils.hasText(instance.getTimeWindow()) ? instance.getTimeWindow() : "6AM-03AM")
                .consumptionLimitWindow(parseConsumptionWindow(instance.getConsumptionLimitWindow()))
                .consumptionLimit(instance.getConsumptionLimit() != null ? instance.getConsumptionLimit() : 0L)
                .bucketUsername(bucketUsername)
                .group(false)
                .build();
    }

    //Parse consumption window
    private Integer parseConsumptionWindow(String window) {
        if (!StringUtils.hasText(window)) {
            return 24;
        }
        try {
            int parsed = Integer.parseInt(window.trim());
            return parsed > 0 ? parsed : 24;
        } catch (NumberFormatException e) {
            log.warn("Invalid consumption window: {}, using default 24", window);
            return 24;
        }
    }

    // Determine if should retry
    private boolean shouldRetry(Throwable throwable) {
        if (throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            return status >= 500 || status == 429 || status == 408;
        }
        return throwable instanceof java.util.concurrent.TimeoutException
                || throwable instanceof java.io.IOException
                || throwable instanceof java.net.ConnectException;
    }


}
