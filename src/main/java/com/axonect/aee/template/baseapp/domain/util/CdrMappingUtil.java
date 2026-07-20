package com.axonect.aee.template.baseapp.domain.util;

import com.axonect.aee.template.baseapp.domain.entities.dto.Session;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.AccountingCdr;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.AccountingCdrEvent;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.CdrPayload;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.NetworkCdr;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.SessionCdr;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.UserCdr;
import com.axonect.aee.template.baseapp.domain.enums.CdrEventType;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Utility for mapping {@link Session} data to accounting CDR events.
 * Used when sessions are terminated by the idle-session scheduler.
 */
public final class CdrMappingUtil {

    private static final String EVENT_VERSION = "1.0";
    private static final String SOURCE = "AAA-Scheduler";
    private static final String ACCT_STATUS_STOP = "Stop";

    /** Termination cause recorded when a session is torn down for being idle. */
    public static final String CAUSE_IDLE_TIMEOUT = "Idle-Timeout";

    private CdrMappingUtil() {
        // Prevent instantiation
    }

    /**
     * Builds a {@code Stop} accounting CDR event for a session terminated on idle timeout.
     *
     * @param session the session being terminated
     * @return a fully populated {@link AccountingCdrEvent}
     */
    public static AccountingCdrEvent buildIdleTimeoutStopCdrEvent(Session session) {
        return buildStopCdrEvent(session, CAUSE_IDLE_TIMEOUT);
    }

    /**
     * Builds a {@code Stop} accounting CDR event for a terminated session.
     *
     * @param session        the session being terminated
     * @param terminateCause the reason the session was torn down (e.g. {@code Idle-Timeout})
     * @return a fully populated {@link AccountingCdrEvent}
     */
    public static AccountingCdrEvent buildStopCdrEvent(Session session, String terminateCause) {
        Instant now = Instant.now();

        SessionCdr sessionCdr = SessionCdr.builder()
                .sessionId(session.getSessionId())
                .sessionTime(String.valueOf(session.getSessionTime() != null ? session.getSessionTime() : 0))
                .startTime(toInstant(session.getSessionStartTime() != null
                        ? session.getSessionStartTime()
                        : session.getSessionInitiatedTime()))
                .updateTime(now)
                .sessionStopTime(now)
                .nasIpAddress(session.getNasIp())
                .nasPort(session.getNasPortId())
                .nasPortType(session.getNasPortId())
                .build();

        UserCdr userCdr = UserCdr.builder()
                .userName(session.getUserName())
                .groupId(session.getGroupId())
                .build();

        NetworkCdr networkCdr = NetworkCdr.builder()
                .framedIpAddress(session.getFramedId())
                .calledStationId(session.getNasIp())
                .build();

        AccountingCdr accountingCdr = AccountingCdr.builder()
                .acctStatusType(ACCT_STATUS_STOP)
                .acctSessionTime(session.getSessionTime() != null ? session.getSessionTime() : 0)
                .totalUsage(session.getPreviousTotalUsageQuotaValue() != null
                        ? session.getPreviousTotalUsageQuotaValue()
                        : 0L)
                .sessionUsage(session.getSessionUsage())
                .serviceId(session.getServiceId())
                .bucketId(session.getPreviousUsageBucketId())
                .terminateCause(terminateCause)
                .build();

        CdrPayload payload = CdrPayload.builder()
                .session(sessionCdr)
                .user(userCdr)
                .network(networkCdr)
                .accounting(accountingCdr)
                .build();

        return AccountingCdrEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(CdrEventType.ACCOUNTING_STOP.name())
                .eventVersion(EVENT_VERSION)
                .eventTimestamp(now)
                .source(SOURCE)
                .partitionKey(session.getSessionId())
                .payload(payload)
                .build();
    }

    private static Instant toInstant(java.time.LocalDateTime dateTime) {
        return dateTime != null
                ? dateTime.atZone(ZoneId.systemDefault()).toInstant()
                : Instant.now();
    }
}
