package com.axonect.aee.template.baseapp.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * High-Performance Reactive Redis Service
 * Spring Boot equivalent of Quarkus ReactiveRedisDataSource with ReactiveValueCommands
 *
 * This service provides reactive, non-blocking Redis operations using Project Reactor
 * for high-throughput and low-latency data access patterns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReactiveRedisService {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplateJson;

    // ==================== STRING OPERATIONS (Similar to ReactiveValueCommands) ====================

    /**
     * Set a key-value pair (Reactive equivalent of SET command)
     * Similar to Quarkus: valueCommands.set(key, value)
     */
    public Mono<Boolean> set(String key, String value) {
        return reactiveRedisTemplate.opsForValue()
                .set(key, value)
                .doOnSuccess(result -> log.debug("SET key: {}, result: {}", key, result))
                .doOnError(error -> log.error("Error setting key: {}", key, error));
    }

    /**
     * Set a key-value pair with expiration (Reactive equivalent of SETEX command)
     * Similar to Quarkus: valueCommands.setex(key, seconds, value)
     */
    public Mono<Boolean> setex(String key, String value, Duration ttl) {
        return reactiveRedisTemplate.opsForValue()
                .set(key, value, ttl)
                .doOnSuccess(result -> log.debug("SETEX key: {}, ttl: {}, result: {}", key, ttl, result))
                .doOnError(error -> log.error("Error setting key with expiration: {}", key, error));
    }

    /**
     * Set if not exists (Reactive equivalent of SETNX command)
     * Similar to Quarkus: valueCommands.setnx(key, value)
     */
    public Mono<Boolean> setnx(String key, String value) {
        return reactiveRedisTemplate.opsForValue()
                .setIfAbsent(key, value)
                .doOnSuccess(result -> log.debug("SETNX key: {}, result: {}", key, result))
                .doOnError(error -> log.error("Error setting key if absent: {}", key, error));
    }

    /**
     * Get value by key (Reactive equivalent of GET command)
     * Similar to Quarkus: valueCommands.get(key)
     */
    public Mono<String> get(String key) {
        return reactiveRedisTemplate.opsForValue()
                .get(key)
                .doOnSuccess(value -> log.debug("GET key: {}, found: {}", key, value != null))
                .doOnError(error -> log.error("Error getting key: {}", key, error));
    }

    /**
     * Get and set (Reactive equivalent of GETSET command)
     * Similar to Quarkus: valueCommands.getset(key, value)
     */
    public Mono<String> getset(String key, String newValue) {
        return reactiveRedisTemplate.opsForValue()
                .getAndSet(key, newValue)
                .doOnSuccess(oldValue -> log.debug("GETSET key: {}, old value: {}", key, oldValue))
                .doOnError(error -> log.error("Error in getset for key: {}", key, error));
    }

    /**
     * Delete a key (Reactive equivalent of DEL command)
     * Similar to Quarkus: redisDataSource.key().del(key)
     */
    public Mono<Boolean> delete(String key) {
        return reactiveRedisTemplate.delete(key)
                .map(count -> count > 0)
                .doOnSuccess(result -> log.debug("DEL key: {}, deleted: {}", key, result))
                .doOnError(error -> log.error("Error deleting key: {}", key, error));
    }

    /**
     * Delete multiple keys (Reactive equivalent of DEL command for multiple keys)
     */
    public Mono<Long> deleteKeys(List<String> keys) {
        return reactiveRedisTemplate.delete(keys.toArray(new String[0]))
                .doOnSuccess(count -> log.debug("DEL {} keys, deleted count: {}", keys.size(), count))
                .doOnError(error -> log.error("Error deleting keys", error));
    }

    /**
     * Check if key exists (Reactive equivalent of EXISTS command)
     * Similar to Quarkus: redisDataSource.key().exists(key)
     */
    public Mono<Boolean> exists(String key) {
        return reactiveRedisTemplate.hasKey(key)
                .doOnSuccess(exists -> log.debug("EXISTS key: {}, result: {}", key, exists))
                .doOnError(error -> log.error("Error checking existence of key: {}", key, error));
    }

    /**
     * Set expiration on a key (Reactive equivalent of EXPIRE command)
     * Similar to Quarkus: redisDataSource.key().expire(key, seconds)
     */
    public Mono<Boolean> expire(String key, Duration ttl) {
        return reactiveRedisTemplate.expire(key, ttl)
                .doOnSuccess(result -> log.debug("EXPIRE key: {}, ttl: {}, result: {}", key, ttl, result))
                .doOnError(error -> log.error("Error setting expiration for key: {}", key, error));
    }

    /**
     * Get time to live for a key (Reactive equivalent of TTL command)
     * Similar to Quarkus: redisDataSource.key().ttl(key)
     */
    public Mono<Duration> ttl(String key) {
        return reactiveRedisTemplate.getExpire(key)
                .doOnSuccess(ttl -> log.debug("TTL key: {}, ttl: {}", key, ttl))
                .doOnError(error -> log.error("Error getting TTL for key: {}", key, error));
    }

    /**
     * Increment a value (Reactive equivalent of INCR command)
     * Similar to Quarkus: valueCommands.incr(key)
     */
    public Mono<Long> incr(String key) {
        return reactiveRedisTemplate.opsForValue()
                .increment(key)
                .doOnSuccess(value -> log.debug("INCR key: {}, new value: {}", key, value))
                .doOnError(error -> log.error("Error incrementing key: {}", key, error));
    }

    /**
     * Increment by a specific value (Reactive equivalent of INCRBY command)
     * Similar to Quarkus: valueCommands.incrby(key, increment)
     */
    public Mono<Long> incrby(String key, long delta) {
        return reactiveRedisTemplate.opsForValue()
                .increment(key, delta)
                .doOnSuccess(value -> log.debug("INCRBY key: {}, delta: {}, new value: {}", key, delta, value))
                .doOnError(error -> log.error("Error incrementing key by delta: {}", key, error));
    }

    /**
     * Decrement a value (Reactive equivalent of DECR command)
     * Similar to Quarkus: valueCommands.decr(key)
     */
    public Mono<Long> decr(String key) {
        return reactiveRedisTemplate.opsForValue()
                .decrement(key)
                .doOnSuccess(value -> log.debug("DECR key: {}, new value: {}", key, value))
                .doOnError(error -> log.error("Error decrementing key: {}", key, error));
    }

    /**
     * Decrement by a specific value (Reactive equivalent of DECRBY command)
     * Similar to Quarkus: valueCommands.decrby(key, decrement)
     */
    public Mono<Long> decrby(String key, long delta) {
        return reactiveRedisTemplate.opsForValue()
                .decrement(key, delta)
                .doOnSuccess(value -> log.debug("DECRBY key: {}, delta: {}, new value: {}", key, delta, value))
                .doOnError(error -> log.error("Error decrementing key by delta: {}", key, error));
    }

    // ==================== HASH OPERATIONS ====================

    /**
     * Set a hash field value (Reactive equivalent of HSET command)
     */
    public Mono<Boolean> hset(String key, String field, String value) {
        return reactiveRedisTemplate.opsForHash()
                .put(key, field, value)
                .doOnSuccess(result -> log.debug("HSET key: {}, field: {}, result: {}", key, field, result))
                .doOnError(error -> log.error("Error setting hash field for key: {}", key, error));
    }

    /**
     * Get a hash field value (Reactive equivalent of HGET command)
     */
    public Mono<String> hget(String key, String field) {
        return reactiveRedisTemplate.<String, String>opsForHash()
                .get(key, field)
                .map(Object::toString)
                .doOnSuccess(value -> log.debug("HGET key: {}, field: {}, found: {}", key, field, value != null))
                .doOnError(error -> log.error("Error getting hash field for key: {}", key, error));
    }

    /**
     * Get all hash fields and values (Reactive equivalent of HGETALL command)
     */
    public Mono<Map<Object, Object>> hgetall(String key) {
        return reactiveRedisTemplate.opsForHash()
                .entries(key)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doOnSuccess(map -> log.debug("HGETALL key: {}, size: {}", key, map.size()))
                .doOnError(error -> log.error("Error getting all hash fields for key: {}", key, error));
    }

    /**
     * Delete a hash field (Reactive equivalent of HDEL command)
     */
    public Mono<Long> hdel(String key, String... fields) {
        return reactiveRedisTemplate.opsForHash()
                .remove(key, (Object[]) fields)
                .doOnSuccess(count -> log.debug("HDEL key: {}, deleted count: {}", key, count))
                .doOnError(error -> log.error("Error deleting hash fields for key: {}", key, error));
    }

    /**
     * Check if hash field exists (Reactive equivalent of HEXISTS command)
     */
    public Mono<Boolean> hexists(String key, String field) {
        return reactiveRedisTemplate.opsForHash()
                .hasKey(key, field)
                .doOnSuccess(exists -> log.debug("HEXISTS key: {}, field: {}, exists: {}", key, field, exists))
                .doOnError(error -> log.error("Error checking hash field existence for key: {}", key, error));
    }

    // ==================== LIST OPERATIONS ====================

    /**
     * Push value to the left of a list (Reactive equivalent of LPUSH command)
     */
    public Mono<Long> lpush(String key, String... values) {
        return reactiveRedisTemplate.opsForList()
                .leftPushAll(key, values)
                .doOnSuccess(size -> log.debug("LPUSH key: {}, new size: {}", key, size))
                .doOnError(error -> log.error("Error pushing to list: {}", key, error));
    }

    /**
     * Push value to the right of a list (Reactive equivalent of RPUSH command)
     */
    public Mono<Long> rpush(String key, String... values) {
        return reactiveRedisTemplate.opsForList()
                .rightPushAll(key, values)
                .doOnSuccess(size -> log.debug("RPUSH key: {}, new size: {}", key, size))
                .doOnError(error -> log.error("Error pushing to list: {}", key, error));
    }

    /**
     * Pop value from the left of a list (Reactive equivalent of LPOP command)
     */
    public Mono<String> lpop(String key) {
        return reactiveRedisTemplate.opsForList()
                .leftPop(key)
                .doOnSuccess(value -> log.debug("LPOP key: {}, value: {}", key, value))
                .doOnError(error -> log.error("Error popping from list: {}", key, error));
    }

    /**
     * Pop value from the right of a list (Reactive equivalent of RPOP command)
     */
    public Mono<String> rpop(String key) {
        return reactiveRedisTemplate.opsForList()
                .rightPop(key)
                .doOnSuccess(value -> log.debug("RPOP key: {}, value: {}", key, value))
                .doOnError(error -> log.error("Error popping from list: {}", key, error));
    }

    /**
     * Get list size (Reactive equivalent of LLEN command)
     */
    public Mono<Long> llen(String key) {
        return reactiveRedisTemplate.opsForList()
                .size(key)
                .doOnSuccess(size -> log.debug("LLEN key: {}, size: {}", key, size))
                .doOnError(error -> log.error("Error getting list size: {}", key, error));
    }

    /**
     * Get list range (Reactive equivalent of LRANGE command)
     */
    public Flux<String> lrange(String key, long start, long end) {
        return reactiveRedisTemplate.opsForList()
                .range(key, start, end)
                .doOnComplete(() -> log.debug("LRANGE key: {}, start: {}, end: {}", key, start, end))
                .doOnError(error -> log.error("Error getting list range for key: {}", key, error));
    }

    // ==================== SET OPERATIONS ====================

    /**
     * Add members to a set (Reactive equivalent of SADD command)
     */
    public Mono<Long> sadd(String key, String... values) {
        return reactiveRedisTemplate.opsForSet()
                .add(key, values)
                .doOnSuccess(count -> log.debug("SADD key: {}, added count: {}", key, count))
                .doOnError(error -> log.error("Error adding to set: {}", key, error));
    }

    /**
     * Remove members from a set (Reactive equivalent of SREM command)
     */
    public Mono<Long> srem(String key, String... values) {
        return reactiveRedisTemplate.opsForSet()
                .remove(key, (Object[]) values)
                .doOnSuccess(count -> log.debug("SREM key: {}, removed count: {}", key, count))
                .doOnError(error -> log.error("Error removing from set: {}", key, error));
    }

    /**
     * Get all members of a set (Reactive equivalent of SMEMBERS command)
     */
    public Flux<String> smembers(String key) {
        return reactiveRedisTemplate.opsForSet()
                .members(key)
                .doOnComplete(() -> log.debug("SMEMBERS key: {}", key))
                .doOnError(error -> log.error("Error getting set members for key: {}", key, error));
    }

    /**
     * Check if member exists in set (Reactive equivalent of SISMEMBER command)
     */
    public Mono<Boolean> sismember(String key, String value) {
        return reactiveRedisTemplate.opsForSet()
                .isMember(key, value)
                .doOnSuccess(exists -> log.debug("SISMEMBER key: {}, value: {}, exists: {}", key, value, exists))
                .doOnError(error -> log.error("Error checking set membership for key: {}", key, error));
    }

    /**
     * Get set size (Reactive equivalent of SCARD command)
     */
    public Mono<Long> scard(String key) {
        return reactiveRedisTemplate.opsForSet()
                .size(key)
                .doOnSuccess(size -> log.debug("SCARD key: {}, size: {}", key, size))
                .doOnError(error -> log.error("Error getting set size for key: {}", key, error));
    }

    // ==================== JSON OBJECT OPERATIONS ====================

    /**
     * Set a JSON object (useful for complex domain objects)
     */
    public Mono<Boolean> setJson(String key, Object value) {
        return reactiveRedisTemplateJson.opsForValue()
                .set(key, value)
                .doOnSuccess(result -> log.debug("SET JSON key: {}, result: {}", key, result))
                .doOnError(error -> log.error("Error setting JSON object for key: {}", key, error));
    }

    /**
     * Set a JSON object with expiration
     */
    public Mono<Boolean> setJsonWithExpiry(String key, Object value, Duration ttl) {
        return reactiveRedisTemplateJson.opsForValue()
                .set(key, value, ttl)
                .doOnSuccess(result -> log.debug("SET JSON with expiry key: {}, ttl: {}, result: {}", key, ttl, result))
                .doOnError(error -> log.error("Error setting JSON object with expiry for key: {}", key, error));
    }

    /**
     * Get a JSON object
     */
    public Mono<Object> getJson(String key) {
        return reactiveRedisTemplateJson.opsForValue()
                .get(key)
                .doOnSuccess(value -> log.debug("GET JSON key: {}, found: {}", key, value != null))
                .doOnError(error -> log.error("Error getting JSON object for key: {}", key, error));
    }

    // ==================== BATCH OPERATIONS (High-Performance Pattern) ====================

    /**
     * Execute multiple operations in a pipeline for better performance
     * This is a high-performance pattern for batch operations
     */
    public <T> Flux<T> executePipelined(List<Mono<T>> operations) {
        log.debug("Executing {} operations in pipeline", operations.size());
        return Flux.concat(operations)
                .doOnComplete(() -> log.debug("Pipeline execution completed"))
                .doOnError(error -> log.error("Error in pipeline execution", error));
    }

    /**
     * Get multiple keys at once (High-performance batch get)
     * Similar to pipeline operations in Quarkus
     */
    public Flux<String> mget(List<String> keys) {
        return reactiveRedisTemplate.opsForValue()
                .multiGet(keys)
                .flatMapMany(Flux::fromIterable)
                .doOnComplete(() -> log.debug("MGET completed for {} keys", keys.size()))
                .doOnError(error -> log.error("Error in MGET operation", error));
    }

    /**
     * Get Redis connection info (useful for monitoring)
     */
    public Mono<Boolean> ping() {
        return reactiveRedisTemplate.execute(connection -> connection.ping())
                .map(response -> "PONG".equals(response))
                .single()
                .doOnSuccess(result -> log.debug("PING result: {}", result))
                .doOnError(error -> log.error("Error pinging Redis", error));
    }
}
