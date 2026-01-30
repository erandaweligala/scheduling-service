package com.axonect.aee.template.baseapp.application.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * High-Performance Redis Configuration using Lettuce Client
 * Optimized for high-throughput blocking operations
 */
@Configuration
@EnableCaching
@Slf4j
public class RedisConfig {

    private final RedisProperties redisProperties;

    public RedisConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }

    /**
     * Configure Lettuce Client Resources for optimal performance
     * Manages I/O threads and computation threads
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources lettuceClientResources() {
        log.info("Configuring Lettuce ClientResources for high-performance Redis operations");
        return DefaultClientResources.builder()
                .ioThreadPoolSize(4)  // Number of I/O threads (typically CPU cores)
                .computationThreadPoolSize(4)  // Number of computation threads
                .build();
    }

    /**
     * Configure Lettuce Client Options with performance optimizations
     */
    @Bean
    public ClientOptions lettuceClientOptions() {
        log.info("Configuring Lettuce ClientOptions with performance optimizations");

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .keepAlive(true)
                .tcpNoDelay(true)  // Disable Nagle's algorithm for lower latency
                .build();

        TimeoutOptions timeoutOptions = TimeoutOptions.enabled(Duration.ofSeconds(10));

        return ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(timeoutOptions)
                .protocolVersion(ProtocolVersion.RESP3)  // Use RESP3 protocol for better performance
                .autoReconnect(true)
                .cancelCommandsOnReconnectFailure(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .publishOnScheduler(true)  // Use dedicated scheduler for pub/sub
                .build();
    }

    /**
     * Configure Connection Pool for high-performance operations
     */
    @Bean
    public GenericObjectPoolConfig<?> lettucePoolConfig() {
        log.info("Configuring Lettuce connection pool for high concurrency");

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        RedisProperties.Pool pool = redisProperties.getLettuce().getPool();

        poolConfig.setMaxTotal(pool.getMaxActive());
        poolConfig.setMaxIdle(pool.getMaxIdle());
        poolConfig.setMinIdle(pool.getMinIdle());
        poolConfig.setMaxWait(pool.getMaxWait());

        // Performance optimizations
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(false);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(60));
        poolConfig.setMinEvictableIdleTime(Duration.ofMinutes(5));
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setJmxEnabled(false);

        log.info("Pool config: maxTotal={}, maxIdle={}, minIdle={}",
                poolConfig.getMaxTotal(), poolConfig.getMaxIdle(), poolConfig.getMinIdle());

        return poolConfig;
    }

    /**
     * Configure High-Performance Lettuce Connection Factory
     * This replaces Jedis with Lettuce for better performance and pooling
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            ClientResources clientResources,
            ClientOptions clientOptions,
            GenericObjectPoolConfig<?> poolConfig) {

        log.info("Configuring high-performance LettuceConnectionFactory");
        log.info("Redis host: {}, port: {}", redisProperties.getHost(), redisProperties.getPort());

        // Redis standalone configuration
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisProperties.getHost());
        redisConfig.setPort(redisProperties.getPort());

        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            redisConfig.setPassword(redisProperties.getPassword());
        }

        // Lettuce client configuration with pooling
        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .clientOptions(clientOptions)
                .clientResources(clientResources)
                .poolConfig(poolConfig)
                .commandTimeout(redisProperties.getTimeout())
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig, clientConfig);
        factory.setShareNativeConnection(false);  // Don't share connections for better concurrency
        factory.setValidateConnection(true);

        log.info("LettuceConnectionFactory configured successfully");
        return factory;
    }

    /**
     * Configure Redis cache manager with custom TTL settings for different caches.
     * This allows different cache types to have different expiration times.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        log.info("Initializing Redis Cache Manager with custom configurations");

        // Create ObjectMapper for JSON serialization with Java 8 time support
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Enable polymorphic type handling for proper deserialization
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        // Default cache configuration (1 hour TTL)
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        // Custom cache configurations for different cache types
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Plans cache - 6 hours (plans don't change frequently)
        cacheConfigurations.put("plans",
                defaultConfig.entryTtl(Duration.ofHours(6)));

        // Buckets cache - 6 hours (bucket definitions are relatively static)
        cacheConfigurations.put("buckets",
                defaultConfig.entryTtl(Duration.ofHours(6)));

        // QOS Profiles cache - 12 hours (QOS profiles rarely change)
        cacheConfigurations.put("qosProfiles",
                defaultConfig.entryTtl(Duration.ofHours(12)));

        // Users cache - 30 minutes (user data may change more frequently)
        cacheConfigurations.put("users",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Plan to Bucket mapping cache - 6 hours
        cacheConfigurations.put("planToBuckets",
                defaultConfig.entryTtl(Duration.ofHours(6)));

        // Service instances cache - 15 minutes (more dynamic data)
        cacheConfigurations.put("serviceInstances",
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Bucket instances cache - 5 minutes (very dynamic data)
        cacheConfigurations.put("bucketInstances",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        log.info("Configured {} custom cache regions with varying TTLs", cacheConfigurations.size());

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    /**
     * Configure RedisTemplate for manual cache operations if needed.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        log.info("Initializing RedisTemplate for manual cache operations");

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Create ObjectMapper for JSON serialization
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for values
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}
