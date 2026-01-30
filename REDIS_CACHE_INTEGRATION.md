# Redis Cache Integration Guide

## Overview

This document describes the Redis cache integration implemented in the scheduling service application. Redis caching has been added to improve performance by reducing database queries for frequently accessed data.

## Prerequisites

- Redis server installed and running
- Default Redis configuration: `localhost:6379`
- No authentication required by default (can be configured)

### Installing Redis

**On Linux/Mac:**
```bash
# Using Docker (recommended)
docker run -d --name redis -p 6379:6379 redis:latest

# Or install directly
# Ubuntu/Debian
sudo apt-get install redis-server

# Mac
brew install redis
```

**On Windows:**
```bash
# Using Docker
docker run -d --name redis -p 6379:6379 redis:latest
```

## Configuration

### Redis Connection Settings

The Redis connection is configured in `src/main/resources/application.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost        # Redis server host
      port: 6379            # Redis server port
      password:             # Set if authentication is required
      timeout: 5000ms       # Connection timeout
      jedis:
        pool:
          max-active: 50    # Maximum active connections
          max-idle: 20      # Maximum idle connections
          min-idle: 10      # Minimum idle connections
          max-wait: 2000ms  # Max wait time for connection
  cache:
    type: redis
    redis:
      time-to-live: 3600000  # Default TTL: 1 hour
      cache-null-values: false
      use-key-prefix: true
      key-prefix: "scheduling-service:"
```

### Cache Regions and TTL

The following cache regions are configured with different TTL values:

| Cache Region | TTL | Description |
|-------------|-----|-------------|
| `plans` | 6 hours | Plan definitions (rarely change) |
| `buckets` | 6 hours | Bucket definitions (relatively static) |
| `qosProfiles` | 12 hours | QOS profiles (rarely change) |
| `users` | 30 minutes | User data (may change frequently) |
| `planToBuckets` | 6 hours | Plan to bucket mappings |
| `serviceInstances` | 15 minutes | Service instances (dynamic) |
| `bucketInstances` | 5 minutes | Bucket instances (very dynamic) |

## Implementation Details

### 1. Dependencies

Added to `pom.xml`:
```xml
<!-- Redis Cache Support -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
</dependency>
```

### 2. Configuration Classes

#### RedisConfig.java
- Configures `CacheManager` with custom TTL settings
- Sets up `RedisTemplate` for manual cache operations
- Configures JSON serialization with Java 8 time support

#### CustomCacheKeyGenerator.java
- Generates consistent cache keys based on class, method, and parameters
- Format: `ClassName::methodName::param1,param2`

### 3. Cached Repositories

The following repository methods are cached:

#### PlanRepository
- `findByPlanId(String planId)` - Cache: `plans`
- `findByPlanIdIn(Set<String> planIds)` - Cache: `plans`

#### BucketRepository
- `findByBucketId(String bucketId)` - Cache: `buckets`
- `findByBucketIdIn(Set<String> bucketIds)` - Cache: `buckets`

#### QOSProfileRepository
- `findById(Long qosId)` - Cache: `qosProfiles`
- `findByIdIn(Set<Long> qosIds)` - Cache: `qosProfiles`

#### UserRepository
- `findAllByUserName(String userName)` - Cache: `users`
- `findByUserNameIn(Set<String> userNames)` - Cache: `users`

#### PlanToBucketRepository
- `findByPlanId(String planId)` - Cache: `planToBuckets`
- `findByPlanIdIn(Set<String> planIds)` - Cache: `planToBuckets`

### 4. RedisCacheService

A service class providing manual cache operations:

- `evictCache(cacheName, key)` - Evict specific key
- `evictAllCache(cacheName)` - Clear entire cache
- `evictAllCaches()` - Clear all caches
- `getCacheValue(cacheName, key)` - Get cached value
- `putCacheValue(cacheName, key, value)` - Put value in cache
- `getCacheSize(cacheName)` - Get cache statistics
- `containsKey(cacheName, key)` - Check if key exists
- `evictPlanCaches(planId)` - Evict plan-related caches
- `evictUserCache(userName)` - Evict user cache
- `evictBucketCache(bucketId)` - Evict bucket cache
- `evictQosProfileCache(qosId)` - Evict QOS profile cache

### 5. Cache Management REST API

The `CacheManagementController` provides REST endpoints for cache management:

#### GET Endpoints

- `GET /cache/names` - Get all cache names
- `GET /cache/statistics` - Get cache statistics
- `GET /cache/{cacheName}/size` - Get size of specific cache
- `GET /cache/{cacheName}/keys` - Get all keys from cache
- `GET /cache/{cacheName}/contains/{key}` - Check if key exists
- `GET /cache/health` - Redis health check

#### DELETE Endpoints

- `DELETE /cache/{cacheName}/key/{key}` - Evict specific key
- `DELETE /cache/{cacheName}` - Clear entire cache
- `DELETE /cache/all` - Clear all caches
- `DELETE /cache/plan/{planId}` - Evict plan-related caches
- `DELETE /cache/user/{userName}` - Evict user cache
- `DELETE /cache/bucket/{bucketId}` - Evict bucket cache
- `DELETE /cache/qos/{qosId}` - Evict QOS profile cache

#### POST Endpoints

- `POST /cache/statistics/log` - Log cache statistics to console

## Usage Examples

### 1. Using Cached Repositories

Cached repositories work transparently:

```java
@Service
public class MyService {
    @Autowired
    private PlanRepository planRepository;

    public Plan getPlan(String planId) {
        // First call: queries database and caches result
        // Subsequent calls: returns from cache (within TTL)
        return planRepository.findByPlanId(planId).orElse(null);
    }
}
```

### 2. Manual Cache Operations

```java
@Service
public class MyService {
    @Autowired
    private RedisCacheService cacheService;

    public void updatePlan(Plan plan) {
        // Update plan in database
        planRepository.save(plan);

        // Evict cached plan to ensure fresh data on next read
        cacheService.evictPlanCaches(plan.getPlanId());
    }
}
```

### 3. Cache Management via REST API

```bash
# Get cache statistics
curl -X GET http://localhost:8086/cache/statistics

# Check cache health
curl -X GET http://localhost:8086/cache/health

# Evict specific plan cache
curl -X DELETE http://localhost:8086/cache/plan/PLAN123

# Clear all caches
curl -X DELETE http://localhost:8086/cache/all

# Get all keys from plans cache
curl -X GET http://localhost:8086/cache/plans/keys
```

## Monitoring and Debugging

### 1. Enable Debug Logging

Add to `application.yml`:

```yaml
logging:
  level:
    com.axonect.aee.template.baseapp.application.config: DEBUG
    com.axonect.aee.template.baseapp.domain.service.RedisCacheService: DEBUG
    org.springframework.cache: DEBUG
```

### 2. View Cache Statistics

```bash
# Via REST API
curl -X GET http://localhost:8086/cache/statistics

# Log to console
curl -X POST http://localhost:8086/cache/statistics/log
```

### 3. Monitor Redis Directly

```bash
# Connect to Redis CLI
redis-cli

# View all keys
KEYS *

# View keys for specific cache
KEYS scheduling-service:plans::*

# Get cache value
GET "scheduling-service:plans::PLAN123"

# Monitor all commands
MONITOR
```

## Performance Considerations

### Benefits

1. **Reduced Database Load**: Frequently accessed data is served from cache
2. **Improved Response Time**: Cache hits are significantly faster than database queries
3. **Scalability**: Redis can handle millions of operations per second
4. **Batch Operations**: Optimized for the existing batch processing (5M+ records)

### Best Practices

1. **Cache Invalidation**: Always evict cache when data is modified
2. **TTL Configuration**: Adjust TTL based on data volatility
3. **Memory Management**: Monitor Redis memory usage
4. **Connection Pooling**: Configured with appropriate pool sizes
5. **Error Handling**: Cache failures should not break application flow

### Memory Estimation

Approximate memory usage per cached item:

- Plan: ~2 KB
- Bucket: ~1 KB
- QOSProfile: ~500 bytes
- User: ~1 KB
- PlanToBucket: ~500 bytes

For 10,000 cached items of each type: ~50 MB

## Troubleshooting

### Redis Connection Errors

```
Error: Could not connect to Redis at localhost:6379
```

**Solution:**
1. Ensure Redis is running: `redis-cli ping` (should return PONG)
2. Check Redis port: `netstat -an | grep 6379`
3. Verify configuration in `application.yml`

### Cache Not Working

1. Check Redis connection: `GET /cache/health`
2. Enable debug logging
3. Verify cache annotations are present
4. Check if data meets caching conditions (e.g., not null)

### High Memory Usage

1. Check cache sizes: `GET /cache/statistics`
2. Reduce TTL for large caches
3. Clear unnecessary caches: `DELETE /cache/all`
4. Configure Redis maxmemory policy

## Production Deployment

### Redis Configuration for Production

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 5000ms
      ssl: true  # Enable for production
```

### High Availability Setup

Consider using:
- Redis Sentinel for automatic failover
- Redis Cluster for horizontal scaling
- AWS ElastiCache or Azure Cache for Redis

### Monitoring

Implement monitoring for:
- Cache hit/miss ratio
- Memory usage
- Connection pool utilization
- Cache eviction rate

## Additional Resources

- [Spring Cache Documentation](https://docs.spring.io/spring-framework/docs/current/reference/html/integration.html#cache)
- [Spring Data Redis](https://spring.io/projects/spring-data-redis)
- [Redis Documentation](https://redis.io/documentation)
- [Redis Best Practices](https://redis.io/topics/best-practices)

## Support

For issues or questions:
1. Check application logs
2. Review Redis logs
3. Use cache management endpoints for diagnostics
4. Contact development team
