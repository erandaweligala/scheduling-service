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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Value("${cache.api.parallel.concurrency:10}")
    private int parallelConcurrency;

    // Sync buckets to cache - processes buckets in parallel for optimal performance
    public void syncBuckets(String bucketUsername, String serviceStatus, List<BucketInstance> bucketInstances) {

        if (!StringUtils.hasText(bucketUsername)) {
            throw new IllegalArgumentException("Bucket username cannot be null or empty");
        }

        if (bucketInstances == null || bucketInstances.isEmpty()) {
            log.warn("No bucket instances provided for user: {}", bucketUsername);
            return;
        }

        log.info("Starting parallel sync for user: {}, bucket count: {}, concurrency: {}",
                bucketUsername, bucketInstances.size(), parallelConcurrency);

        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failedBucketIds = new ArrayList<>();

        // Process buckets in parallel with concurrency control
        List<String> errors = Flux.fromIterable(bucketInstances)
                .flatMap(bucket -> processBucketAsync(bucketUsername, serviceStatus, bucket)
                        .doOnSuccess(v -> {
                            successCount.incrementAndGet();
                            log.debug("Successfully synced bucketId: {}", bucket.getBucketId());
                        })
                        .onErrorResume(e -> {
                            log.error("Failed to sync bucketId: {}", bucket.getBucketId(), e);
                            synchronized (failedBucketIds) {
                                failedBucketIds.add(bucket.getBucketId());
                            }
                            return Mono.just(bucket.getBucketId());
                        }),
                        parallelConcurrency)
                .collectList()
                .block();

        long duration = System.currentTimeMillis() - startTime;
        int failedCount = failedBucketIds.size();

        log.info("Parallel batch sync completed for user: {}. Success: {}, Failed: {}, Total: {}, Duration: {}ms",
                bucketUsername, successCount.get(), failedCount, bucketInstances.size(), duration);

        // Throw exception if any bucket failed
        if (!failedBucketIds.isEmpty()) {
            String failedBuckets = String.join(", ", failedBucketIds);
            String errorMessage = String.format(
                    "Batch sync completed with %d failures out of %d buckets. Failed buckets: [%s]",
                    failedCount, bucketInstances.size(), failedBuckets);

            log.error("Batch sync failed for user: {}. {}", bucketUsername, errorMessage);
            throw new CacheBucketException(errorMessage, bucketUsername, null);
        }

        log.info("All buckets synced successfully for user: {} in {}ms (avg: {}ms per bucket)",
                bucketUsername, duration, duration / bucketInstances.size());
    }

    // Process single bucket - async version for parallel processing
    private Mono<Void> processBucketAsync(String bucketUsername, String serviceStatus, BucketInstance bucket) {

        // Validate bucket
        if (bucket == null) {
            return Mono.error(new IllegalArgumentException("Bucket instance is null"));
        }

        if (!StringUtils.hasText(bucket.getBucketId())) {
            return Mono.error(new IllegalArgumentException("Bucket ID is null or empty"));
        }

        // Build request
        CacheBucketRequest request = buildRequest(bucket, bucketUsername, serviceStatus);

        // Build URL
        String fullUrl = cacheApiUrl.replace("{bucketUsername}", bucketUsername);

        log.debug("Making PATCH request to: {}", fullUrl);

        // Make async API call without blocking
        return cacheApiWebClient
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
                .flatMap(response -> {
                    if (response.getStatusCode().equals(HttpStatus.OK)) {
                        log.debug("Received HTTP 200 for bucketId: {}", bucket.getBucketId());
                        return Mono.empty();
                    } else {
                        log.error("Non-200 status for bucketId: {}. Status: {}",
                                bucket.getBucketId(), response.getStatusCode());
                        return Mono.error(
                                new RuntimeException("Expected HTTP 200 but got " + response.getStatusCode()));
                    }
                });
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
