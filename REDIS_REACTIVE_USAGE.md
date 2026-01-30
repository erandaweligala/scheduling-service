# High-Performance Reactive Redis Usage Guide

## Overview
This project now uses **Lettuce** (reactive Redis client) instead of Jedis for high-performance, non-blocking Redis operations, similar to Quarkus's `ReactiveRedisDataSource` and `ReactiveValueCommands`.

## Architecture

### Components:
1. **LettuceConnectionFactory** - High-performance connection factory with optimized pooling
2. **ReactiveRedisTemplate** - Spring Boot's reactive Redis operations template
3. **ReactiveRedisService** - Service layer providing Quarkus-like API

## Performance Optimizations

### Lettuce Configuration Features:
- **Reactive/Non-blocking I/O**: Uses Project Reactor for async operations
- **Connection Pooling**: 100 max active connections, 20 min idle connections
- **RESP3 Protocol**: Latest Redis protocol for better performance
- **TCP_NODELAY**: Disabled Nagle's algorithm for lower latency
- **Keep-Alive**: Persistent connections
- **Auto-Reconnect**: Automatic connection recovery

### Pool Configuration:
```yaml
max-active: 100     # Maximum connections
max-idle: 50        # Maximum idle connections
min-idle: 20        # Minimum idle connections (pre-warmed)
max-wait: 5000ms    # Connection wait timeout
```

## Usage Examples

### 1. Basic String Operations (Similar to Quarkus ReactiveValueCommands)

```java
@Service
@RequiredArgsConstructor
public class MyService {

    private final ReactiveRedisService reactiveRedisService;

    // SET operation
    public Mono<Boolean> saveUserSession(String userId, String sessionData) {
        return reactiveRedisService.set("session:" + userId, sessionData);
    }

    // SETEX operation (with expiration)
    public Mono<Boolean> saveTempData(String key, String data) {
        return reactiveRedisService.setex(
            "temp:" + key,
            data,
            Duration.ofMinutes(15)
        );
    }

    // GET operation
    public Mono<String> getUserSession(String userId) {
        return reactiveRedisService.get("session:" + userId);
    }

    // SETNX operation (set if not exists)
    public Mono<Boolean> acquireLock(String lockKey) {
        return reactiveRedisService.setnx("lock:" + lockKey, "locked");
    }
}
```

### 2. Counter Operations (INCR/DECR)

```java
// Increment counter
public Mono<Long> incrementPageViews(String pageId) {
    return reactiveRedisService.incr("pageviews:" + pageId);
}

// Increment by specific amount
public Mono<Long> addPoints(String userId, long points) {
    return reactiveRedisService.incrby("points:" + userId, points);
}

// Decrement counter
public Mono<Long> decrementStock(String productId) {
    return reactiveRedisService.decr("stock:" + productId);
}
```

### 3. Hash Operations (HSET/HGET/HGETALL)

```java
// Store user profile fields
public Mono<Boolean> updateUserField(String userId, String field, String value) {
    return reactiveRedisService.hset("user:" + userId, field, value);
}

// Get specific field
public Mono<String> getUserField(String userId, String field) {
    return reactiveRedisService.hget("user:" + userId, field);
}

// Get all user fields
public Mono<Map<Object, Object>> getUserProfile(String userId) {
    return reactiveRedisService.hgetall("user:" + userId);
}
```

### 4. List Operations (LPUSH/RPUSH/LRANGE)

```java
// Add to queue (right push)
public Mono<Long> addToQueue(String queueName, String item) {
    return reactiveRedisService.rpush("queue:" + queueName, item);
}

// Process from queue (left pop)
public Mono<String> processFromQueue(String queueName) {
    return reactiveRedisService.lpop("queue:" + queueName);
}

// Get queue items
public Flux<String> getQueueItems(String queueName) {
    return reactiveRedisService.lrange("queue:" + queueName, 0, -1);
}
```

### 5. Set Operations (SADD/SMEMBERS/SISMEMBER)

```java
// Add user to online users set
public Mono<Long> addOnlineUser(String userId) {
    return reactiveRedisService.sadd("online:users", userId);
}

// Check if user is online
public Mono<Boolean> isUserOnline(String userId) {
    return reactiveRedisService.sismember("online:users", userId);
}

// Get all online users
public Flux<String> getOnlineUsers() {
    return reactiveRedisService.smembers("online:users");
}
```

### 6. JSON Object Operations (Complex Domain Objects)

```java
// Store complex object
public Mono<Boolean> saveBucketConfig(BucketConfig config) {
    return reactiveRedisService.setJson(
        "config:bucket:" + config.getId(),
        config
    );
}

// Store with expiration
public Mono<Boolean> cacheApiResponse(String key, ApiResponse response) {
    return reactiveRedisService.setJsonWithExpiry(
        "cache:api:" + key,
        response,
        Duration.ofMinutes(5)
    );
}

// Retrieve object
public Mono<Object> getBucketConfig(String bucketId) {
    return reactiveRedisService.getJson("config:bucket:" + bucketId);
}
```

### 7. High-Performance Batch Operations

```java
// Batch get multiple keys
public Flux<String> getUserSessions(List<String> userIds) {
    List<String> keys = userIds.stream()
        .map(id -> "session:" + id)
        .collect(Collectors.toList());
    return reactiveRedisService.mget(keys);
}

// Pipeline multiple operations
public Flux<Boolean> batchSetCache(Map<String, String> data) {
    List<Mono<Boolean>> operations = data.entrySet().stream()
        .map(entry -> reactiveRedisService.set(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
    return reactiveRedisService.executePipelined(operations);
}
```

### 8. Reactive Chain Operations

```java
// Complex reactive flow
public Mono<String> processUserLogin(String userId) {
    return reactiveRedisService.get("user:lastLogin:" + userId)
        .flatMap(lastLogin -> {
            // Update last login
            return reactiveRedisService.set(
                "user:lastLogin:" + userId,
                Instant.now().toString()
            );
        })
        .then(reactiveRedisService.incr("user:loginCount:" + userId))
        .then(reactiveRedisService.sadd("users:active", userId))
        .thenReturn("Login processed successfully");
}
```

### 9. TTL and Expiration Management

```java
// Set expiration on existing key
public Mono<Boolean> setKeyExpiration(String key, Duration ttl) {
    return reactiveRedisService.expire(key, ttl);
}

// Check remaining TTL
public Mono<Duration> checkKeyTTL(String key) {
    return reactiveRedisService.ttl(key);
}

// Check if key exists
public Mono<Boolean> keyExists(String key) {
    return reactiveRedisService.exists(key);
}
```

### 10. Health Check and Monitoring

```java
// Ping Redis
public Mono<Boolean> checkRedisHealth() {
    return reactiveRedisService.ping();
}
```

## Comparison: Quarkus vs Spring Boot

### Quarkus ReactiveValueCommands:
```java
// Quarkus
@Inject
ReactiveRedisDataSource reactiveRedisDS;

public Uni<Void> example() {
    ReactiveValueCommands<String, String> commands = reactiveRedisDS.value(String.class);
    return commands.set("key", "value");
}
```

### Spring Boot ReactiveRedisService:
```java
// Spring Boot (This Implementation)
@Autowired
ReactiveRedisService reactiveRedisService;

public Mono<Boolean> example() {
    return reactiveRedisService.set("key", "value");
}
```

## Performance Benefits

### 1. **Non-blocking I/O**
- All operations return `Mono<T>` or `Flux<T>` for reactive composition
- No thread blocking on Redis operations
- Better throughput under high load

### 2. **Connection Pooling**
- Pre-warmed connections (min-idle: 20)
- High connection capacity (max-active: 100)
- Efficient connection reuse

### 3. **Protocol Optimization**
- RESP3 protocol support
- TCP_NODELAY for lower latency
- Connection keep-alive

### 4. **Batch Operations**
- Pipeline support for multiple operations
- Multi-get for batch retrieval
- Reduced network round-trips

## Migration from Blocking to Reactive

### Before (Blocking with RedisTemplate):
```java
String value = redisTemplate.opsForValue().get("key");
// Thread blocked waiting for Redis response
```

### After (Non-blocking with ReactiveRedisService):
```java
Mono<String> valueMono = reactiveRedisService.get("key");
// Returns immediately, operation executes asynchronously
valueMono.subscribe(value -> {
    // Process value when available
});
```

## Best Practices

1. **Use reactive chains**: Chain operations with `flatMap`, `map`, `then` instead of blocking
2. **Batch operations**: Use `mget` and pipelines for multiple keys
3. **Set appropriate TTLs**: Prevent memory bloat with expiration times
4. **Monitor connection pool**: Watch for pool exhaustion
5. **Handle errors**: Use `doOnError` for proper error handling
6. **Use JSON operations**: For complex domain objects instead of serialization

## Configuration Tuning

For even higher performance, adjust these settings in `application.yml`:

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 200  # For very high concurrency
          min-idle: 50     # More pre-warmed connections
          max-wait: 3000ms # Lower timeout for faster failures
```

## Conclusion

This implementation provides high-performance reactive Redis operations comparable to Quarkus, with the following advantages:

- ✅ Non-blocking reactive operations
- ✅ Optimized connection pooling
- ✅ RESP3 protocol support
- ✅ Low-latency TCP configuration
- ✅ Quarkus-like API familiarity
- ✅ Batch operation support
- ✅ JSON object serialization
- ✅ Comprehensive Redis command coverage
