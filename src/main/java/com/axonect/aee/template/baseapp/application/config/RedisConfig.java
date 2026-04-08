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
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
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
@EnableRetry
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
                .ioThreadPoolSize(4)
                .computationThreadPoolSize(4)
                .build();
    }

    /**
     * Configure Lettuce Client Options with performance optimizations
     * Includes Sentinel-specific reconnection settings
     */
    @Bean
    public ClientOptions lettuceClientOptions() {
        log.info("Configuring Lettuce ClientOptions with performance optimizations for Sentinel");

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .keepAlive(true)
                .tcpNoDelay(true)
                .build();

        TimeoutOptions timeoutOptions = TimeoutOptions.enabled(Duration.ofSeconds(120));

        return ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(timeoutOptions)
                // RESP3 requires auth to happen before HELLO on Sentinel.
                // setSentinelPassword() below handles that; if you still face
                // handshake issues with older Sentinel versions, switch to RESP2.
                .protocolVersion(ProtocolVersion.RESP3)
                .autoReconnect(true)
                .suspendReconnectOnProtocolFailure(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .publishOnScheduler(true)
                .build();
    }

    /**
     * Configure Connection Pool for high-performance operations
     */
    @Bean
    public GenericObjectPoolConfig<Object> lettucePoolConfig() {
        log.info("Configuring Lettuce connection pool for high concurrency");

        GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
        RedisProperties.Pool pool = redisProperties.getLettuce().getPool();

        poolConfig.setMaxTotal(pool.getMaxActive());
        poolConfig.setMaxIdle(pool.getMaxIdle());
        poolConfig.setMinIdle(pool.getMinIdle());
        poolConfig.setMaxWait(pool.getMaxWait());

        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(false);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(60));
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setJmxEnabled(false);

        log.info("Pool config: maxTotal={}, maxIdle={}, minIdle={}",
                poolConfig.getMaxTotal(), poolConfig.getMaxIdle(), poolConfig.getMinIdle());

        return poolConfig;
    }

    /**
     * Configure High-Performance Lettuce Connection Factory with Sentinel Support
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            ClientResources clientResources,
            ClientOptions clientOptions,
            GenericObjectPoolConfig<Object> poolConfig) {

        log.info("Configuring high-performance LettuceConnectionFactory with Sentinel support");

        LettuceConnectionFactory factory;

        if (redisProperties.getSentinel() != null && redisProperties.getSentinel().getMaster() != null) {
            log.info("Configuring Redis Sentinel mode");
            log.info("Sentinel Master: {}", redisProperties.getSentinel().getMaster());
            log.info("Sentinel Nodes: {}", redisProperties.getSentinel().getNodes());

            RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
                    .master(redisProperties.getSentinel().getMaster());

            // Add sentinel nodes
            redisProperties.getSentinel().getNodes().forEach(node -> {
                String[] parts = node.split(":");
                if (parts.length == 2) {
                    sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
                    log.info("Added Sentinel node: {}:{}", parts[0], parts[1]);
                }
            });

            // ── FIX 1: Set the Redis DATA node password ──────────────────────────────
            if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
                sentinelConfig.setPassword(redisProperties.getPassword());
                log.info("Redis data-node password configured");
            }

            // ── FIX 2: Set the SENTINEL authentication password ──────────────────────
            // Without this, Lettuce connects to Sentinel unauthenticated and Sentinel
            // rejects the RESP3 HELLO handshake with:
            //   "NOAUTH HELLO must be called with the client already authenticated"
            RedisProperties.Sentinel sentinelProps = redisProperties.getSentinel();
            if (sentinelProps.getPassword() != null && !sentinelProps.getPassword().isEmpty()) {
                sentinelConfig.setSentinelPassword(sentinelProps.getPassword());
                log.info("Sentinel authentication password configured");
            }

            sentinelConfig.setDatabase(redisProperties.getDatabase());

            LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                    .clientOptions(clientOptions)
                    .clientResources(clientResources)
                    .poolConfig(poolConfig)
                    .commandTimeout(redisProperties.getTimeout())
                    .build();

            factory = new LettuceConnectionFactory(sentinelConfig, clientConfig);
            log.info("LettuceConnectionFactory configured for Sentinel mode");

        } else {
            log.info("Configuring Redis Standalone mode");
            log.info("Redis host: {}, port: {}", redisProperties.getHost(), redisProperties.getPort());

            RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
            redisConfig.setHostName(redisProperties.getHost());
            redisConfig.setPort(redisProperties.getPort());

            if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
                redisConfig.setPassword(redisProperties.getPassword());
            }

            LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                    .clientOptions(clientOptions)
                    .clientResources(clientResources)
                    .poolConfig(poolConfig)
                    .commandTimeout(redisProperties.getTimeout())
                    .build();

            factory = new LettuceConnectionFactory(redisConfig, clientConfig);
            log.info("LettuceConnectionFactory configured for Standalone mode");
        }

        factory.setShareNativeConnection(false);
        factory.setValidateConnection(true);

        log.info("LettuceConnectionFactory configured successfully");
        return factory;
    }

    /**
     * Configure RedisTemplate for manual cache operations if needed.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        log.info("Initializing RedisTemplate for manual cache operations");

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

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

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Configure RedisTemplate for String-to-String operations
     * Used by UserCacheService for high-performance user session caching
     */
    @Bean
    public RedisTemplate<String, String> redisTemplateString(RedisConnectionFactory connectionFactory) {
        log.info("Initializing RedisTemplate<String, String> for string operations");

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }
}