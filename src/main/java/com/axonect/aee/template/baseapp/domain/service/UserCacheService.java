package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.exception.CacheOperationException;
import com.axonect.aee.template.baseapp.domain.exception.CacheSerializationException;
import com.axonect.aee.template.baseapp.domain.exception.CacheTimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * High-Performance User Cache Service using Lettuce
 * Converted from Quarkus reactive to Spring Boot blocking operations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserCacheService {

    private static final String KEY_PREFIX = "user:";
    private static final String GROUP_KEY_PREFIX = "group:";

    private final RedisTemplate<String, String> redisTemplateString;
    private final ObjectMapper objectMapper;

    // Thread pool for parallel operations (optimized for high TPS)
    private final ExecutorService executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
    );

    /**
     * Get user data from Redis cache with retry and timeout
     * Equivalent to Quarkus @Retry(maxRetries=1, delay=100, jitter=50) and @Timeout(5 seconds)
     */
    @Retryable(
            maxAttempts = 2,  // maxRetries=1 means 1 retry + 1 original = 2 attempts
            backoff = @Backoff(delay = 100, maxDelay = 150, random = true)  // delay=100, jitter=50
    )
    public UserSessionData getUserData(String userId) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;

        if (log.isDebugEnabled()) {
            log.debug("Retrieving user data for cache userId: {}", userId);
        }

        String key = KEY_PREFIX + userId;

        try {
            String json = fetchUserDataFromRedis(key);

            if (json == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No user data found for userId: {}", userId);
                }
                return null;
            }

            UserSessionData userData = deserializeUserData(json, userId);

            if (log.isDebugEnabled()) {
                log.debug("User data retrieved for userId: {} in {} ms",
                        userId, (System.currentTimeMillis() - startTime));
            }

            return userData;

        } catch (CacheSerializationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while getting user data for userId: {}", userId, e);
            throw new CacheOperationException("Thread interrupted while getting user data for userId: " + userId, e);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Timeout getting user data for userId: {}", userId, e);
            throw new CacheTimeoutException("Timeout getting user data for userId: " + userId, e);
        } catch (Exception e) {
            log.error("Failed to get user data for userId: {}", userId, e);
            throw new CacheOperationException("Failed to get user data for userId: " + userId, e);
        }
    }

    private String fetchUserDataFromRedis(String key) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                redisTemplateString.opsForValue().get(key), executorService);
        return future.get(5, TimeUnit.SECONDS);
    }

    /**
     * Deserializes JSON string to UserSessionData object
     */
    private UserSessionData deserializeUserData(String json, String userId) {
        try {
            return objectMapper.readValue(json, UserSessionData.class);
        } catch (Exception e) {
            log.error("Failed to deserialize user data for userId: {} - {}", userId, e.getMessage());
            throw new CacheSerializationException("Failed to deserialize user data", e);
        }
    }

    /**
     * Update user data and related caches in Redis
     * Equivalent to Quarkus @Retry(maxRetries=1, delay=30, maxDuration=1500) and @Timeout(8 seconds)
     */
    @Retryable(
            maxAttempts = 2,  // maxRetries=1 means 1 retry + 1 original = 2 attempts
            backoff = @Backoff(delay = 30)
    )
    public void updateUserAndRelatedCaches(String userId, UserSessionData userData, String userName) {
        if (log.isDebugEnabled()) {
            log.debug("Updating user data and related caches for userId: {}", userId);
        }

        String userKey = KEY_PREFIX + userId;

        try {
            removeExpiredBalanceElements(userData);
            String jsonValue = objectMapper.writeValueAsString(userData);

            if (shouldUpdateGroupCache(userData)) {
                updateUserAndGroupCache(userId, userName, userKey, jsonValue, userData);
            } else {
                updateUserCacheOnly(userId, userKey, jsonValue);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while updating cache for user: {}", userId, e);
            throw new CacheOperationException("Thread interrupted while updating cache for user: " + userId, e);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Timeout updating cache for user: {}", userId, e);
            throw new CacheTimeoutException("Timeout updating cache for user: " + userId, e);
        } catch (Exception e) {
            log.error("Failed to update cache for user: {}", userId, e);
            throw new CacheOperationException("Failed to serialize or update user data for userId: " + userId, e);
        }
    }

    private boolean shouldUpdateGroupCache(UserSessionData userData) {
        return userData != null
                && userData.getGroupId() != null
                && !userData.getGroupId().equalsIgnoreCase("1");
    }

    private void updateUserAndGroupCache(String userId, String userName, String userKey,
                                        String jsonValue, UserSessionData userData) throws Exception {
        String groupKey = GROUP_KEY_PREFIX + userName;
        String groupValues = userData.getGroupId() + "," + userData.getConcurrency() + ","
                + userData.getUserStatus() + "," + userData.getSessionTimeOut();

        CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() ->
                redisTemplateString.opsForValue().set(userKey, jsonValue), executorService);

        CompletableFuture<Void> groupFuture = CompletableFuture.runAsync(() ->
                redisTemplateString.opsForValue().set(groupKey, groupValues), executorService);

        CompletableFuture.allOf(userFuture, groupFuture).get(8, TimeUnit.SECONDS);

        if (log.isDebugEnabled()) {
            log.debug("Updated user and group cache for userId: {}", userId);
        }
    }

    private void updateUserCacheOnly(String userId, String userKey, String jsonValue) throws Exception {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                redisTemplateString.opsForValue().set(userKey, jsonValue), executorService);

        future.get(8, TimeUnit.SECONDS);

        if (log.isDebugEnabled()) {
            log.debug("Updated user cache for userId: {}", userId);
        }
    }

    /**
     * Get group data from Redis
     */
    @Retryable(maxAttempts = 2, backoff = @Backoff(delay = 100))
    public String getGroupData(String userName) {
        String groupKey = GROUP_KEY_PREFIX + userName;
        try {
            return redisTemplateString.opsForValue().get(groupKey);
        } catch (Exception e) {
            log.error("Failed to get group data for userName: {}", userName, e);
            throw new CacheOperationException("Failed to get group data for userName: " + userName, e);
        }
    }

    /**
     * Delete user data from cache
     */
    @Retryable(maxAttempts = 2, backoff = @Backoff(delay = 50))
    public void deleteUserData(String userId) {
        String userKey = KEY_PREFIX + userId;
        try {
            redisTemplateString.delete(userKey);
            log.debug("Deleted user data for userId: {}", userId);
        } catch (Exception e) {
            log.error("Failed to delete user data for userId: {}", userId, e);
            throw new CacheOperationException("Failed to delete user data for userId: " + userId, e);
        }
    }

    /**
     * Delete group data from cache
     */
    @Retryable(maxAttempts = 2, backoff = @Backoff(delay = 50))
    public void deleteGroupData(String userName) {
        String groupKey = GROUP_KEY_PREFIX + userName;
        try {
            redisTemplateString.delete(groupKey);
            log.debug("Deleted group data for userName: {}", userName);
        } catch (Exception e) {
            log.error("Failed to delete group data for userName: {}", userName, e);
            throw new CacheOperationException("Failed to delete group data for userName: " + userName, e);
        }
    }

    /**
     * Check if user data exists in cache
     */
    public boolean userDataExists(String userId) {
        String userKey = KEY_PREFIX + userId;
        try {
            return redisTemplateString.hasKey(userKey);
        } catch (Exception e) {
            log.error("Failed to check user data existence for userId: {}", userId, e);
            return false;
        }
    }

    /**
     * Removes expired balance elements from user session data
     * Removes balance elements where:
     * - bucketExpiryDate is before yesterday (expired more than 1 day ago)
     * - quota is 0
     * - isUnlimited is false (not an unlimited bucket)
     *
     * @param userData User session data to clean up
     */

    private void removeExpiredBalanceElements(UserSessionData userData) {
        if (userData == null || userData.getBalance() == null || userData.getBalance().isEmpty()) {
            return;
        }

        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        int originalSize = userData.getBalance().size();

        // Filter out balance elements that meet the removal criteria
        var filteredBalances = userData.getBalance().stream()
                .filter(balance -> {

                    boolean shouldKeep = balance.getBucketExpiryDate().isAfter(oneDayAgo);

                    if (!shouldKeep && log.isDebugEnabled()) {
                        log.debug("Removing expired balance element - BucketId: {}, Quota: {}, ExpiryDate: {}, isUnlimited: {}",
                                balance.getBucketId(), balance.getQuota(),
                                balance.getBucketExpiryDate(), balance.isUnlimited());
                    }

                    return shouldKeep;
                })
                .toList();

        int removedCount = originalSize - filteredBalances.size();
        if (removedCount > 0) {
            userData.setBalance(filteredBalances);
            log.info("Removed {} expired balance elements from user session data. Remaining: {}",
                    removedCount, filteredBalances.size());
        }
    }

    /**
     * Clear all user cache data containing bucket expiry information.
     * This is typically called after scheduled deletion of expired buckets
     * to ensure cache consistency with the database.
     * Uses Redis SCAN to efficiently iterate through all user keys and delete them.
     */

    /**
     * Cleanup method to shutdown executor service gracefully
     */
    public void shutdown() {
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
