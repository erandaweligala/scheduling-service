package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.domain.service.RedisCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for managing Redis cache operations.
 * Provides endpoints for cache eviction, monitoring, and statistics.
 */
@RestController
@RequestMapping("/cache")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Cache Management", description = "Redis cache management operations")
public class CacheManagementController {

    private final RedisCacheService redisCacheService;

    @Operation(summary = "Get all cache names")
    @GetMapping("/names")
    public ResponseEntity<Collection<String>> getAllCacheNames() {
        log.info("Request received to get all cache names");
        Collection<String> cacheNames = redisCacheService.getAllCacheNames();
        return ResponseEntity.ok(cacheNames);
    }

    @Operation(summary = "Get cache statistics")
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getCacheStatistics() {
        log.info("Request received to get cache statistics");
        Map<String, Object> statistics = new HashMap<>();

        Collection<String> cacheNames = redisCacheService.getAllCacheNames();
        Map<String, Long> cacheSizes = new HashMap<>();

        cacheNames.forEach(cacheName -> {
            long size = redisCacheService.getCacheSize(cacheName);
            cacheSizes.put(cacheName, size);
        });

        statistics.put("totalCaches", cacheNames.size());
        statistics.put("cacheNames", cacheNames);
        statistics.put("cacheSizes", cacheSizes);

        return ResponseEntity.ok(statistics);
    }

    @Operation(summary = "Get cache size for a specific cache")
    @GetMapping("/{cacheName}/size")
    public ResponseEntity<Map<String, Object>> getCacheSize(@PathVariable String cacheName) {
        log.info("Request received to get size for cache: {}", cacheName);
        long size = redisCacheService.getCacheSize(cacheName);

        Map<String, Object> response = new HashMap<>();
        response.put("cacheName", cacheName);
        response.put("size", size);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get all keys from a specific cache")
    @GetMapping("/{cacheName}/keys")
    public ResponseEntity<Set<String>> getCacheKeys(@PathVariable String cacheName) {
        log.info("Request received to get all keys from cache: {}", cacheName);
        Set<String> keys = redisCacheService.getAllKeysFromCache(cacheName);
        return ResponseEntity.ok(keys);
    }

    @Operation(summary = "Evict a specific key from cache")
    @DeleteMapping("/{cacheName}/key/{key}")
    public ResponseEntity<Map<String, String>> evictCacheKey(
            @PathVariable String cacheName,
            @PathVariable String key) {
        log.info("Request received to evict key: {} from cache: {}", key, cacheName);
        redisCacheService.evictCache(cacheName, key);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Cache key evicted successfully");
        response.put("cacheName", cacheName);
        response.put("key", key);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Evict all entries from a specific cache")
    @DeleteMapping("/{cacheName}")
    public ResponseEntity<Map<String, String>> evictCache(@PathVariable String cacheName) {
        log.info("Request received to evict all entries from cache: {}", cacheName);
        redisCacheService.evictAllCache(cacheName);

        Map<String, String> response = new HashMap<>();
        response.put("message", "All entries evicted from cache successfully");
        response.put("cacheName", cacheName);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Evict all entries from all caches")
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, String>> evictAllCaches() {
        log.info("Request received to evict all caches");
        redisCacheService.evictAllCaches();

        Map<String, String> response = new HashMap<>();
        response.put("message", "All caches evicted successfully");

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Check if cache contains a specific key")
    @GetMapping("/{cacheName}/contains/{key}")
    public ResponseEntity<Map<String, Object>> containsKey(
            @PathVariable String cacheName,
            @PathVariable String key) {
        log.info("Request received to check if cache: {} contains key: {}", cacheName, key);
        boolean contains = redisCacheService.containsKey(cacheName, key);

        Map<String, Object> response = new HashMap<>();
        response.put("cacheName", cacheName);
        response.put("key", key);
        response.put("contains", contains);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Evict plan-related caches")
    @DeleteMapping("/plan/{planId}")
    public ResponseEntity<Map<String, String>> evictPlanCaches(@PathVariable String planId) {
        log.info("Request received to evict plan-related caches for planId: {}", planId);
        redisCacheService.evictPlanCaches(planId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Plan-related caches evicted successfully");
        response.put("planId", planId);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Evict user cache")
    @DeleteMapping("/user/{userName}")
    public ResponseEntity<Map<String, String>> evictUserCache(@PathVariable String userName) {
        log.info("Request received to evict user cache for userName: {}", userName);
        redisCacheService.evictUserCache(userName);

        Map<String, String> response = new HashMap<>();
        response.put("message", "User cache evicted successfully");
        response.put("userName", userName);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Evict bucket cache")
    @DeleteMapping("/bucket/{bucketId}")
    public ResponseEntity<Map<String, String>> evictBucketCache(@PathVariable String bucketId) {
        log.info("Request received to evict bucket cache for bucketId: {}", bucketId);
        redisCacheService.evictBucketCache(bucketId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Bucket cache evicted successfully");
        response.put("bucketId", bucketId);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Evict QOS profile cache")
    @DeleteMapping("/qos/{qosId}")
    public ResponseEntity<Map<String, String>> evictQosProfileCache(@PathVariable Long qosId) {
        log.info("Request received to evict QOS profile cache for qosId: {}", qosId);
        redisCacheService.evictQosProfileCache(qosId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "QOS profile cache evicted successfully");
        response.put("qosId", qosId.toString());

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Log cache statistics")
    @PostMapping("/statistics/log")
    public ResponseEntity<Map<String, String>> logCacheStatistics() {
        log.info("Request received to log cache statistics");
        redisCacheService.logCacheStatistics();

        Map<String, String> response = new HashMap<>();
        response.put("message", "Cache statistics logged successfully. Check application logs.");

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Health check for Redis cache")
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        try {
            // Simple health check - try to get cache names
            redisCacheService.getAllCacheNames();

            Map<String, String> response = new HashMap<>();
            response.put("status", "UP");
            response.put("message", "Redis cache is healthy");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Redis health check failed", e);

            Map<String, String> response = new HashMap<>();
            response.put("status", "DOWN");
            response.put("message", "Redis cache is not available: " + e.getMessage());

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }
}
