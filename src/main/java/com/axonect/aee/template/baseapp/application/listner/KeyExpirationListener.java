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
 * When a key of the form  service::{serviceId}::{bucketInstanceId}  expires:
 *   1. Extracts both serviceId and bucketInstanceId from the key
 *   2. Delegates to ServiceExpirationHandler to handle deletion logic
 *
 * Redis only delivers the KEY name on expiry — the value is already gone.
 * That is why both IDs are encoded in the key itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyExpirationListener implements MessageListener {

    private static final String SERVICE_KEY_PREFIX = "service::";
    private static final int EXPECTED_KEY_PARTS = 3; // ["service", "{serviceId}", "{bucketInstanceId}"]

    private final ServiceExpirationHandler serviceExpirationHandler;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        log.debug("Redis key expired: {}", expiredKey);

        if (!expiredKey.startsWith(SERVICE_KEY_PREFIX)) {
            return;
        }

        // Split: "service::9054135111::9054521738" → ["service", "9054135111", "9054521738"]
        String[] parts = expiredKey.split("::");

        if (parts.length != EXPECTED_KEY_PARTS) {
            log.error("Unexpected key format '{}' — expected service::{{serviceId}}::{{bucketInstanceId}}. Skipping.",
                    expiredKey);
            return;
        }

        try {
            Long serviceId        = Long.parseLong(parts[1].trim());
            Long bucketInstanceId = Long.parseLong(parts[2].trim());

            log.info("TTL expired — serviceId: {}, bucketInstanceId: {}. Triggering expiration handler.",
                    serviceId, bucketInstanceId);

            serviceExpirationHandler.handleServiceExpiration(serviceId, bucketInstanceId);

        } catch (NumberFormatException e) {
            log.error("Could not parse IDs from expired key '{}': {}", expiredKey, e.getMessage());
        }
    }
}