package com.axonect.aee.template.baseapp.application.listner;

import com.axonect.aee.template.baseapp.domain.service.ServiceExpirationHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Listens to Redis key expiration events.
 *
 * Handles two key formats:
 * <ul>
 *   <li>{@code service::{serviceId}} — triggers full service + bucket cleanup</li>
 *   <li>{@code service::{serviceId}:{bucketInstanceId}} — triggers single-bucket cleanup</li>
 * </ul>
 *
 * Redis only delivers the KEY name on expiry — the value is already gone.
 * That is why the serviceId (and bucketInstanceId) are encoded in the key, not the value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyExpirationListener implements MessageListener {

    private static final String SERVICE_KEY_PREFIX = "service::";

    private final ServiceExpirationHandler serviceExpirationHandler;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        log.debug("Redis key expired: {}", expiredKey);

        if (!expiredKey.startsWith(SERVICE_KEY_PREFIX)) {
            // Not our key — ignore
            return;
        }

        String remaining = expiredKey.substring(SERVICE_KEY_PREFIX.length());
        int colonIndex = remaining.indexOf(':');

        try {
            if (colonIndex == -1) {
                // Format: service::{serviceId}
                Long serviceId = Long.parseLong(remaining);
                log.info("Service TTL expired for serviceId: {}. Triggering full service cleanup.", serviceId);
                serviceExpirationHandler.handleServiceExpiration(serviceId);
            } else {
                // Format: service::{serviceId}:{bucketInstanceId}
                Long serviceId        = Long.parseLong(remaining.substring(0, colonIndex));
                Long bucketInstanceId = Long.parseLong(remaining.substring(colonIndex + 1));
                log.info("Bucket TTL expired for serviceId: {}, bucketInstanceId: {}. Triggering bucket cleanup.",
                        serviceId, bucketInstanceId);
                serviceExpirationHandler.handleBucketExpiration(serviceId, bucketInstanceId);
            }
        } catch (NumberFormatException e) {
            log.error("Could not parse IDs from expired key '{}': {}", expiredKey, e.getMessage());
        }
    }
}