package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.exception.CacheOperationException;
import com.axonect.aee.template.baseapp.domain.exception.CacheSerializationException;
import com.axonect.aee.template.baseapp.domain.exception.CacheTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final RedisTemplate<String, byte[]> redisTemplateBytes;
    private final SessionCacheCodec sessionCacheCodec;

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
            byte[] payload = fetchUserDataFromRedis(key);

            if (payload == null || payload.length == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("No user data found for userId: {}", userId);
                }
                return null;
            }

            UserSessionData userData = deserializeUserData(payload, userId);

            if (log.isDebugEnabled()) {
                log.debug("User data retrieved for userId: {} in {} ms",
                        userId, (System.currentTimeMillis() - startTime));
            }

            return userData;

        } catch (CacheSerializationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get user data for userId: {}", userId, e);
            throw new CacheOperationException("Failed to get user data for userId: " + userId, e);
        }
    }

    @SuppressWarnings("java:S112")
    private byte[] fetchUserDataFromRedis(String key) throws Exception {
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() ->
                redisTemplateBytes.opsForValue().get(key), executorService);
        return future.get(5, TimeUnit.SECONDS);
    }

    /**
     * Deserializes a raw Redis payload (CBOR, or legacy JSON) to a UserSessionData object.
     */
    private UserSessionData deserializeUserData(byte[] payload, String userId) {
        try {
            return sessionCacheCodec.decode(payload);
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
    @SneakyThrows
    public void updateUserAndRelatedCaches(String userId, UserSessionData userData, String userName) {
        if (log.isDebugEnabled()) {
            log.debug("Updating user data and related caches for userId: {}", userId);
        }

        String userKey = KEY_PREFIX + userId;

        try {
            removeExpiredBalanceElements(userData);
            byte[] encoded = sessionCacheCodec.encode(userData);

            if (shouldUpdateGroupCache(userData)) {
                updateUserAndGroupCache(userId, userName, userKey, encoded, userData);
            } else {
                updateUserCacheOnly(userId, userKey, encoded);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while updating cache for user: {}", userId, e);
            throw new CacheOperationException("Thread interrupted while updating cache for user: " + userId, e);
        }
    }

    private boolean shouldUpdateGroupCache(UserSessionData userData) {
        return userData != null
                && userData.getGroupId() != null
                && !userData.getGroupId().equalsIgnoreCase("1");
    }

    private void updateUserAndGroupCache(String userId, String userName, String userKey,
                                        byte[] encoded, UserSessionData userData) throws Exception {
        String groupKey = GROUP_KEY_PREFIX + userName;
        String groupValues = userData.getGroupId() + "," + userData.getConcurrency() + ","
                + userData.getUserStatus() + "," + userData.getSessionTimeOut();

        CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() ->
                redisTemplateBytes.opsForValue().set(userKey, encoded), executorService);

        CompletableFuture<Void> groupFuture = CompletableFuture.runAsync(() ->
                redisTemplateString.opsForValue().set(groupKey, groupValues), executorService);

        CompletableFuture.allOf(userFuture, groupFuture).get(8, TimeUnit.SECONDS);

        if (log.isDebugEnabled()) {
            log.debug("Updated user and group cache for userId: {}", userId);
        }
    }

    private void updateUserCacheOnly(String userId, String userKey, byte[] encoded) throws Exception {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                redisTemplateBytes.opsForValue().set(userKey, encoded), executorService);

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
     * Batch-retrieve user data for multiple user IDs in a single MGET round-trip.
     * Used by {@link IdleSessionTerminatorScheduler} to minimise Redis latency per batch.
     *
     * @param userIds list of user IDs to fetch
     * @return map of userId to UserSessionData (absent entries are omitted)
     */
    @Retryable(
            maxAttempts = 2,
            backoff = @Backoff(delay = 50, maxDelay = 3000)
    )
    public Map<String, UserSessionData> getUserDataBatchAsMap(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        log.debug("Batch-fetching user data for {} users via MGET", userIds.size());

        List<String> keys = new ArrayList<>(userIds.size());
        for (String id : userIds) {
            keys.add(KEY_PREFIX + id);
        }

        try {
            List<byte[]> values = redisTemplateBytes.opsForValue().multiGet(keys);
            Map<String, UserSessionData> result = new HashMap<>(userIds.size() * 2);

            if (values != null) {
                for (int i = 0; i < userIds.size(); i++) {
                    byte[] value = values.get(i);
                    if (value != null && value.length > 0) {
                        try {
                            UserSessionData userData = sessionCacheCodec.decode(value);
                            result.put(userIds.get(i), userData);
                        } catch (Exception e) {
                            log.error("Failed to deserialize user data for userId: {} - {}",
                                    userIds.get(i), e.getMessage());
                        }
                    }
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to batch-fetch user data", e);
            throw new CacheOperationException("Failed to batch-fetch user data", e);
        }
    }

    /**
     * Scan all {@code user:*} keys in Redis and return their deserialized session data.
     * <p>
     * This is a one-time O(N) backfill operation used by {@link com.axonect.aee.template.baseapp.domain.service.IdleSessionTerminatorScheduler}
     * when the session expiry sorted-set index is found empty. It uses Redis SCAN (non-blocking,
     * cursor-based) rather than KEYS to avoid blocking the Redis server.
     *
     * @return map of userId → UserSessionData for every user key currently in Redis
     */
    public Map<String, UserSessionData> scanAllUserData() {
        log.info("Scanning all user:* keys from Redis for expiry index backfill (one-time O(N) operation)");

        List<String> userKeys = new ArrayList<>();

        redisTemplateString.execute((RedisCallback<Object>) connection -> {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(KEY_PREFIX + "*")
                    .count(200)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    userKeys.add(new String(cursor.next()));
                }
            } catch (Exception e) {
                log.error("Error scanning Redis keys for backfill: {}", e.getMessage());
            }
            return null;
        });

        if (userKeys.isEmpty()) {
            log.info("Backfill scan found no user:* keys in Redis");
            return Map.of();
        }

        log.info("Backfill scan found {} user keys; fetching values via MGET", userKeys.size());
        List<byte[]> values = redisTemplateBytes.opsForValue().multiGet(userKeys);
        Map<String, UserSessionData> result = new HashMap<>(userKeys.size() * 2);

        if (values != null) {
            for (int i = 0; i < userKeys.size(); i++) {
                byte[] value = values.get(i);
                if (value == null || value.length == 0) continue;
                String userId = userKeys.get(i).substring(KEY_PREFIX.length());
                try {
                    result.put(userId, sessionCacheCodec.decode(value));
                } catch (Exception e) {
                    log.error("Failed to deserialize user data for key {}: {}", userKeys.get(i), e.getMessage());
                }
            }
        }

        log.info("Backfill scan complete: {} users with session data", result.size());
        return result;
    }

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
