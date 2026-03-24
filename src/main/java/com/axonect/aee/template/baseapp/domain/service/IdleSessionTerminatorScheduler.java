package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.IdleSessionConfig;
import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.DBWriteRequest;
import com.axonect.aee.template.baseapp.domain.entities.dto.Session;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.enums.EventType;
import com.axonect.aee.template.baseapp.domain.util.MappingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Scheduler that terminates idle sessions based on a configurable timeout threshold.
 *
 * <p>Uses a Redis Sorted Set index (via {@link SessionExpiryIndex}) to find expired sessions
 * in O(log N + K) time, then batch-fetches user data with MGET, terminates the sessions,
 * triggers DB write events for balance persistence, and updates the cache.
 *
 * <p>Concurrent execution is skipped with an {@link AtomicBoolean} guard so that a slow run
 * does not overlap with the next scheduled tick.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IdleSessionTerminatorScheduler {

    private static final String LOG_TERMINATE = "terminateIdleSessions";
    private static final String LOG_PROCESS   = "processExpiredSessions";

    private final UserCacheService userCacheService;
    private final SessionExpiryIndex sessionExpiryIndex;
    private final IdleSessionConfig config;
    private final AccountProducer accountProducer;
    private final MonitoringService monitoringService;

    /** Guards against concurrent scheduler executions (equivalent to ConcurrentExecution.SKIP). */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // -------------------------------------------------------------------------
    // Scheduled entry point
    // -------------------------------------------------------------------------

    /**
     * Scheduled task that terminates idle sessions using the optimized index-based lookup.
     * Interval is controlled by {@code idle-session.scheduler-interval-ms} (default 5 minutes).
     */
    @Scheduled(fixedDelayString = "${idle-session.scheduler-interval-ms:300000}")
    public void terminateIdleSessions() {
        if (!config.isEnabled()) {
            log.debug("[{}] Idle session terminator is disabled, skipping", LOG_TERMINATE);
            return;
        }

        if (!isRunning.compareAndSet(false, true)) {
            log.debug("[{}] Previous run still in progress, skipping concurrent execution", LOG_TERMINATE);
            return;
        }

        long startTime = System.currentTimeMillis();
        int timeoutMinutes = config.getTimeoutMinutes();
        int batchSize = config.getBatchSize();

        long expiryThresholdMillis = Instant.now()
                .minus(Duration.ofMinutes(timeoutMinutes))
                .toEpochMilli();

        log.info("[{}] Starting idle session termination - timeout: {} min, threshold: {}",
                LOG_TERMINATE, timeoutMinutes, expiryThresholdMillis);

        logIndexStats(expiryThresholdMillis);

        AtomicInteger totalTerminated    = new AtomicInteger(0);
        AtomicInteger totalUsersProcessed = new AtomicInteger(0);
        AtomicInteger totalBatches       = new AtomicInteger(0);

        try {
            processExpiredSessionsBatched(expiryThresholdMillis, batchSize,
                    totalTerminated, totalUsersProcessed, totalBatches);

            int terminated = totalTerminated.get();
            monitoringService.recordIdleSessionsTerminated(terminated);

            log.info("[{}] Completed - batches: {}, users: {}, sessions terminated: {}, duration: {} ms",
                    LOG_TERMINATE,
                    totalBatches.get(),
                    totalUsersProcessed.get(),
                    terminated,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[{}] Error during idle session termination", LOG_TERMINATE, e);
        } finally {
            isRunning.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Batch processing
    // -------------------------------------------------------------------------

    /**
     * Repeatedly processes batches of expired sessions until no full batch is returned,
     * which signals there are no more expired sessions.
     */
    private void processExpiredSessionsBatched(long expiryThresholdMillis, int batchSize,
                                               AtomicInteger totalTerminated,
                                               AtomicInteger totalUsersProcessed,
                                               AtomicInteger totalBatches) {
        int processedCount;
        do {
            processedCount = processOneBatch(expiryThresholdMillis, batchSize,
                    totalTerminated, totalUsersProcessed, totalBatches);
            if (processedCount > 0 && processedCount >= batchSize) {
                log.debug("[{}] Batch complete with {} sessions, checking for more", LOG_PROCESS, processedCount);
            }
        } while (processedCount >= batchSize);
    }

    /**
     * Process a single batch of expired sessions.
     *
     * @return count of entries processed in this batch (0 means no more work)
     */
    private int processOneBatch(long expiryThresholdMillis, int batchSize,
                                AtomicInteger totalTerminated,
                                AtomicInteger totalUsersProcessed,
                                AtomicInteger totalBatches) {

        List<SessionExpiryIndex.SessionExpiryEntry> expiredEntries =
                sessionExpiryIndex.getExpiredSessions(expiryThresholdMillis, batchSize);

        if (expiredEntries.isEmpty()) {
            log.debug("[{}] No expired sessions found in this batch", LOG_PROCESS);
            return 0;
        }

        totalBatches.incrementAndGet();
        log.info("[{}] Processing batch of {} expired sessions", LOG_PROCESS, expiredEntries.size());

        // Group by userId for efficient batch processing
        Map<String, List<SessionExpiryIndex.SessionExpiryEntry>> sessionsByUser = expiredEntries.stream()
                .collect(Collectors.groupingBy(SessionExpiryIndex.SessionExpiryEntry::userId));

        List<String> userIds = new ArrayList<>(sessionsByUser.keySet());
        totalUsersProcessed.addAndGet(userIds.size());

        // Single MGET round-trip for all user data
        Map<String, UserSessionData> userDataMap = userCacheService.getUserDataBatchAsMap(userIds);

        processUsersAndCleanupIndex(userDataMap, sessionsByUser, totalTerminated);

        return expiredEntries.size();
    }

    // -------------------------------------------------------------------------
    // Per-batch user processing & index cleanup
    // -------------------------------------------------------------------------

    /**
     * Process expired sessions for each user in the batch, then remove the
     * consumed entries from the Redis sorted set.
     */
    private void processUsersAndCleanupIndex(Map<String, UserSessionData> userDataMap,
                                             Map<String, List<SessionExpiryIndex.SessionExpiryEntry>> sessionsByUser,
                                             AtomicInteger totalTerminated) {

        List<String> membersToRemove = new ArrayList<>();

        for (Map.Entry<String, List<SessionExpiryIndex.SessionExpiryEntry>> entry : sessionsByUser.entrySet()) {
            String userId = entry.getKey();
            List<SessionExpiryIndex.SessionExpiryEntry> expiredSessions = entry.getValue();

            // Collect raw members for index cleanup regardless of user data availability
            for (SessionExpiryIndex.SessionExpiryEntry sessionEntry : expiredSessions) {
                membersToRemove.add(sessionEntry.rawMember());
            }

            UserSessionData userData = userDataMap.get(userId);
            if (userData == null) {
                log.debug("[{}] User data not found for userId: {}, cleaning up index only", LOG_PROCESS, userId);
                continue;
            }

            try {
                processUserExpiredSessions(userData, expiredSessions, totalTerminated);
            } catch (Exception e) {
                log.error("[{}] Error processing sessions for userId: {}", LOG_PROCESS, userId, e);
            }
        }

        // Remove all processed index entries in a single ZREM call
        long removed = sessionExpiryIndex.removeSessions(membersToRemove);
        log.debug("[{}] Removed {} entries from session expiry index", LOG_PROCESS, removed);
    }

    // -------------------------------------------------------------------------
    // Per-user session termination
    // -------------------------------------------------------------------------

    /**
     * Terminate idle (and absolutely timed-out) sessions for one user,
     * produce DB write events for balance persistence, and refresh the cache.
     */
    private void processUserExpiredSessions(UserSessionData userData,
                                            List<SessionExpiryIndex.SessionExpiryEntry> expiredSessionEntries,
                                            AtomicInteger totalTerminated) {

        if (userData.getSessions() == null || userData.getSessions().isEmpty()) {
            return;
        }

        String userName = userData.getUserName();
        List<Session> sessions = userData.getSessions();

        // Build O(1)-lookup set of expired session IDs from the index
        Set<String> expiredSessionIds = new HashSet<>(expiredSessionEntries.size() * 2);
        for (SessionExpiryIndex.SessionExpiryEntry entry : expiredSessionEntries) {
            expiredSessionIds.add(entry.sessionId());
        }

        // Collect sessions that should be terminated (idle timeout OR absolute timeout)
        List<Session> sessionsToTerminate = new ArrayList<>();
        for (Session session : sessions) {
            if (expiredSessionIds.contains(session.getSessionId())
                    || isSessionAbsoluteTimeoutExceeded(session)) {
                sessionsToTerminate.add(session);
            }
        }

        if (sessionsToTerminate.isEmpty()) {
            log.debug("[{}] No matching sessions for user {}, may have been terminated already",
                    LOG_PROCESS, userName);
            return;
        }

        log.info("[{}] Terminating {} idle session(s) for user: {}",
                LOG_PROCESS, sessionsToTerminate.size(), userName);

        // Remove terminated sessions and update user data
        List<Session> activeSessions = new ArrayList<>(sessions);
        activeSessions.removeAll(sessionsToTerminate);
        userData.setSessions(activeSessions);
        totalTerminated.addAndGet(sessionsToTerminate.size());

        // Produce DB write events for balance persistence
        triggerDBWriteEvents(sessionsToTerminate, userData);

        // Update Redis cache with the reduced session list
        try {
            userCacheService.updateUserAndRelatedCaches(userName, userData, userName);
        } catch (Exception e) {
            log.error("[{}] Failed to update cache for user: {}", LOG_PROCESS, userName, e);
        }
    }

    // -------------------------------------------------------------------------
    // DB write event production
    // -------------------------------------------------------------------------

    /**
     * For each terminated session, find the matching balance entry and produce a
     * DB write event so the accounting service can persist the final state.
     */
    private void triggerDBWriteEvents(List<Session> sessionsToTerminate, UserSessionData userData) {
        List<Balance> balances = userData.getBalance();
        if (balances == null || balances.isEmpty()) {
            return;
        }

        // Build a bucketId → Balance map once for O(1) lookups per session
        Map<String, Balance> balanceByBucketId = new HashMap<>(balances.size() * 2);
        for (Balance balance : balances) {
            if (balance.getBucketId() != null) {
                balanceByBucketId.put(balance.getBucketId(), balance);
            }
        }

        for (Session session : sessionsToTerminate) {
            produceDBWriteIfNeeded(session, balanceByBucketId);
        }
    }

    private void produceDBWriteIfNeeded(Session session, Map<String, Balance> balanceByBucketId) {
        String bucketId = session.getPreviousUsageBucketId();
        if (bucketId == null) {
            return;
        }

        Balance balance = balanceByBucketId.get(bucketId);
        if (balance == null) {
            return;
        }

        // Only write when quota is at or below available balance
        // (indicates the balance was consumed during this session)
        if (balance.getQuota() < session.getAvailableBalance()) {
            return;
        }

        DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(
                balance,
                balance.getBucketUsername(),
                session.getSessionId(),
                EventType.UPDATE_EVENT
        );

        log.debug("[{}] Triggering DB write for terminated session: {}, bucketId: {}",
                LOG_PROCESS, session.getSessionId(), bucketId);

        try {
            accountProducer.produceDBWriteEvent(dbWriteRequest);
        } catch (Exception e) {
            log.error("[{}] Failed to produce DB write event for session: {}",
                    LOG_PROCESS, session.getSessionId(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Absolute timeout check
    // -------------------------------------------------------------------------

    /**
     * Returns true if the session has exceeded its absolute timeout
     * ({@code absoluteTimeOut} seconds from {@code sessionStartTime}).
     */
    private boolean isSessionAbsoluteTimeoutExceeded(Session session) {
        if (session == null
                || session.getSessionInitiatedTime() == null
                || session.getAbsoluteTimeOut() == null
                || session.getSessionStartTime() == null) {
            return false;
        }

        try {
            long timeoutSeconds = Long.parseLong(session.getAbsoluteTimeOut().trim());
            LocalDateTime expiryTime = session.getSessionStartTime().plusSeconds(timeoutSeconds);
            boolean isExpired = LocalDateTime.now().isAfter(expiryTime);

            if (isExpired) {
                log.info("[{}] Absolute timeout exceeded for session: {}, started: {}, timeout: {}s, expiry: {}",
                        LOG_PROCESS, session.getSessionId(),
                        session.getSessionInitiatedTime(), timeoutSeconds, expiryTime);
            }
            return isExpired;

        } catch (NumberFormatException e) {
            log.warn("[{}] Invalid absoluteTimeOut format '{}' for session: {} - {}",
                    LOG_PROCESS, session.getAbsoluteTimeOut(), session.getSessionId(), e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Monitoring helpers
    // -------------------------------------------------------------------------

    private void logIndexStats(long expiryThresholdMillis) {
        try {
            long total = sessionExpiryIndex.getTotalIndexedSessions();
            log.info("[{}] Session expiry index - total indexed: {}", LOG_TERMINATE, total);
        } catch (Exception e) {
            log.warn("[{}] Failed to get total index count: {}", LOG_TERMINATE, e.getMessage());
        }

        try {
            long expired = sessionExpiryIndex.getExpiredSessionCount(expiryThresholdMillis);
            log.info("[{}] Session expiry index - expired sessions: {}", LOG_TERMINATE, expired);
        } catch (Exception e) {
            log.warn("[{}] Failed to get expired session count: {}", LOG_TERMINATE, e.getMessage());
        }
    }
}
