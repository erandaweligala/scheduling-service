package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-bucket Redis TTL keys for service instances.
 *
 * <p>Key format:  service::{serviceId}:{bucketInstanceId}
 * <p>Value format: {username}:{planId}
 *
 * <p>The key encodes both serviceId and bucketInstanceId so that when the key
 * expires, {@link com.axonect.aee.template.baseapp.application.listner.KeyExpirationListener}
 * can extract both identifiers from the key name alone (Redis does not deliver
 * the value on expiry).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceCacheTTLService {

    static final String SERVICE_KEY_PREFIX = "service::";

    private final RedisTemplate<String, String> redisTemplateString;

    /**
     * Sets a Redis TTL key for a single bucket instance belonging to a service.
     *
     * <p>The TTL is calculated as the number of seconds between now and
     * {@code bucketInstance.getExpiration()}. If the expiration is null or
     * already in the past, the key is not written.
     *
     * @param service        the owning service instance
     * @param bucketInstance the bucket instance whose expiry drives the TTL
     */
    public void setServiceBucketCacheTTL(ServiceInstance service, BucketInstance bucketInstance) {
        String key   = SERVICE_KEY_PREFIX + service.getId() + ":" + bucketInstance.getId();
        String value = service.getUsername() + ":" + service.getPlanId();

        LocalDateTime expiration = bucketInstance.getExpiration();
        if (expiration == null) {
            log.warn("Bucket instance ID: {} has no expiration date — skipping TTL key set", bucketInstance.getId());
            return;
        }

        long ttlSeconds = ChronoUnit.SECONDS.between(
                LocalDateTime.now(ZoneId.of(Constants.SL_TIME_ZONE)), expiration);

        if (ttlSeconds <= 0) {
            log.warn("Bucket instance ID: {} is already expired (expiration: {}) — skipping TTL key set",
                    bucketInstance.getId(), expiration);
            return;
        }

        redisTemplateString.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        log.debug("Set service bucket TTL — key: {}, value: {}, TTL: {}s", key, value, ttlSeconds);
    }
}
