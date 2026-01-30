package com.axonect.aee.template.baseapp.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing Redis cache operations.
 * Provides methods for cache eviction, retrieval, and management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCacheService {

    private final CacheManager cacheManager;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Evict a specific key from a cache
     */
    public void evictCache(String cacheName, String key) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(key);
                log.info("Evicted cache key: {} from cache: {}", key, cacheName);
            } else {
                log.warn("Cache not found: {}", cacheName);
            }
        } catch (Exception e) {
            log.error("Error evicting cache key: {} from cache: {}", key, cacheName, e);
        }
    }

    /**
     * Evict all entries from a specific cache
     */
    public void evictAllCache(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                log.info("Cleared all entries from cache: {}", cacheName);
            } else {
                log.warn("Cache not found: {}", cacheName);
            }
        } catch (Exception e) {
            log.error("Error clearing cache: {}", cacheName, e);
        }
    }

    /**
     * Evict all entries from all caches
     */
    public void evictAllCaches() {
        try {
            Collection<String> cacheNames = cacheManager.getCacheNames();
            cacheNames.forEach(cacheName -> {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                    log.info("Cleared cache: {}", cacheName);
                }
            });
            log.info("Cleared all {} caches", cacheNames.size());
        } catch (Exception e) {
            log.error("Error clearing all caches", e);
        }
    }

    /**
     * Get value from cache
     */
    public Object getCacheValue(String cacheName, String key) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    log.debug("Cache hit for key: {} in cache: {}", key, cacheName);
                    return wrapper.get();
                }
                log.debug("Cache miss for key: {} in cache: {}", key, cacheName);
            }
        } catch (Exception e) {
            log.error("Error getting cache value for key: {} from cache: {}", key, cacheName, e);
        }
        return null;
    }

    /**
     * Put value into cache manually
     */
    public void putCacheValue(String cacheName, String key, Object value) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.put(key, value);
                log.debug("Put value into cache: {} with key: {}", cacheName, key);
            } else {
                log.warn("Cache not found: {}", cacheName);
            }
        } catch (Exception e) {
            log.error("Error putting value into cache: {} with key: {}", cacheName, key, e);
        }
    }

    /**
     * Get all cache names
     */
    public Collection<String> getAllCacheNames() {
        return cacheManager.getCacheNames();
    }

    /**
     * Get cache statistics (keys count) for a specific cache
     */
    public long getCacheSize(String cacheName) {
        try {
            Set<String> keys = redisTemplate.keys("scheduling-service:" + cacheName + "::*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.error("Error getting cache size for: {}", cacheName, e);
            return -1;
        }
    }

    /**
     * Check if cache contains a specific key
     */
    public boolean containsKey(String cacheName, String key) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                return cache.get(key) != null;
            }
        } catch (Exception e) {
            log.error("Error checking if cache contains key: {} in cache: {}", key, cacheName, e);
        }
        return false;
    }

    /**
     * Set expiration time for a specific key
     */
    public void setExpiration(String key, long timeout, TimeUnit timeUnit) {
        try {
            Boolean result = redisTemplate.expire(key, timeout, timeUnit);
            if (Boolean.TRUE.equals(result)) {
                log.debug("Set expiration for key: {} to {} {}", key, timeout, timeUnit);
            }
        } catch (Exception e) {
            log.error("Error setting expiration for key: {}", key, e);
        }
    }

    /**
     * Get remaining TTL for a key
     */
    public Long getTTL(String key, TimeUnit timeUnit) {
        try {
            return redisTemplate.getExpire(key, timeUnit);
        } catch (Exception e) {
            log.error("Error getting TTL for key: {}", key, e);
            return null;
        }
    }

    /**
     * Evict plan-related caches when plan is updated
     */
    public void evictPlanCaches(String planId) {
        evictCache("plans", planId);
        evictCache("planToBuckets", planId);
        log.info("Evicted plan-related caches for planId: {}", planId);
    }

    /**
     * Evict user-related caches when user is updated
     */
    public void evictUserCache(String userName) {
        evictCache("users", userName);
        log.info("Evicted user cache for userName: {}", userName);
    }

    /**
     * Evict bucket-related caches when bucket is updated
     */
    public void evictBucketCache(String bucketId) {
        evictCache("buckets", bucketId);
        log.info("Evicted bucket cache for bucketId: {}", bucketId);
    }

    /**
     * Evict QOS profile cache when profile is updated
     */
    public void evictQosProfileCache(Long qosId) {
        evictCache("qosProfiles", qosId.toString());
        log.info("Evicted QOS profile cache for qosId: {}", qosId);
    }

    /**
     * Get all keys from a specific cache
     */
    public Set<String> getAllKeysFromCache(String cacheName) {
        try {
            return redisTemplate.keys("scheduling-service:" + cacheName + "::*");
        } catch (Exception e) {
            log.error("Error getting all keys from cache: {}", cacheName, e);
            return Set.of();
        }
    }

    /**
     * Warm up cache by pre-loading frequently accessed data
     */
    public void warmUpCache() {
        log.info("Cache warm-up initiated - implement specific warm-up logic as needed");
        // This method can be called on application startup to pre-load frequently accessed data
        // Implementation depends on business requirements
    }

    /**
     * Get cache statistics for all caches
     */
    public void logCacheStatistics() {
        log.info("=== Redis Cache Statistics ===");
        getAllCacheNames().forEach(cacheName -> {
            long size = getCacheSize(cacheName);
            log.info("Cache: {} | Size: {} keys", cacheName, size);
        });
        log.info("==============================");
    }
}
