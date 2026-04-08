package com.axonect.aee.template.baseapp.domain.util;

import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.DBWriteRequest;
import com.axonect.aee.template.baseapp.domain.enums.EventType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Utility class for mapping domain objects to request payloads.
 */
public class MappingUtil {

    private MappingUtil() {
    }
    public static final String CURRENT_BALANCE = "CURRENT_BALANCE";
    public static final String USAGE = "USAGE";
    public static final String UPDATED_AT = "UPDATED_AT";
    public static final String ID = "ID";
    public static final String SERVICE_ID = "SERVICE_ID";
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
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
        long usage = 0;
        long currentBalance;
        if(!balance.isUnlimited()) {
            usage = balance.getInitialBalance() - balance.getQuota();
            currentBalance = balance.getQuota();

        }else {
            usage =balance.getUsage();
            currentBalance = balance.getInitialBalance();
        }
        Map<String, Object> columnValues = Map.of(
                CURRENT_BALANCE, currentBalance,
                USAGE, usage,
                UPDATED_AT, LocalDateTime.now().format(FORMATTER)
        );

        Map<String, Object> whereConditions = Map.of(
                SERVICE_ID, balance.getServiceId(),
                ID, balance.getBucketId()
        );
        return DBWriteRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(bucketUsername)
                .sessionId(sessionId)
                .tableName("BUCKET_INSTANCE")
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .build();
    }
}
