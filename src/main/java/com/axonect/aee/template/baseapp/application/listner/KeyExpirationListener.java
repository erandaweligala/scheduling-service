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
 * When a key of the form  service::{serviceId}  expires, this listener:
 *   1. Extracts the serviceId from the key name
 *   2. Triggers removal of all BucketInstance records for that serviceId
 *
 * Redis only delivers the KEY name on expiry — the value is already gone.
 * That is why the serviceId is encoded in the key, not the value.
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

        String serviceIdStr = expiredKey.substring(SERVICE_KEY_PREFIX.length());

        try {
            Long serviceId = Long.parseLong(serviceIdStr);
            log.info("Service TTL expired for serviceId: {}. Triggering BucketInstance cleanup.", serviceId);
            serviceExpirationHandler.handleServiceExpiration(serviceId);
        } catch (NumberFormatException e) {
            log.error("Could not parse serviceId from expired key '{}': {}", expiredKey, e.getMessage());
        }
    }
}