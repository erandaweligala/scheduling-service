package com.axonect.aee.template.baseapp.domain.util;

import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.DBWriteRequest;
import com.axonect.aee.template.baseapp.domain.enums.EventType;

/**
 * Utility class for mapping domain objects to request payloads.
 */
public class MappingUtil {

    private MappingUtil() {
    }

    /**
     * Build a {@link DBWriteRequest} from a balance entry for a terminated session.
     *
     * @param balance        the balance to persist
     * @param bucketUsername the owner of the bucket
     * @param sessionId      the ID of the terminated session
     * @param eventType      the type of DB write event
     * @return populated DBWriteRequest
     */
    public static DBWriteRequest createDBWriteRequest(Balance balance,
                                                      String bucketUsername,
                                                      String sessionId,
                                                      EventType eventType) {
        return DBWriteRequest.builder()
                .sessionId(sessionId)
                .bucketId(balance.getBucketId())
                .bucketUsername(bucketUsername)
                .quota(balance.getQuota())
                .eventType(eventType)
                .build();
    }
}
