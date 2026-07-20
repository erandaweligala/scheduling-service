package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.IdleSessionConfig;
import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.DBWriteRequest;
import com.axonect.aee.template.baseapp.domain.entities.dto.Session;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.entities.dto.cdr.AccountingCdrEvent;
import com.axonect.aee.template.baseapp.domain.enums.EventType;
import com.axonect.aee.template.baseapp.domain.util.CdrMappingUtil;
import com.axonect.aee.template.baseapp.domain.util.MappingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Optimized scheduler service that terminates idle sessions based on configurable timeout threshold.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IdleSessionTerminatorScheduler {

    private static final String M_TERMINATE = "terminateIdleSessions";
    private static final String M_PROCESS   = "processExpiredSessions";

    private final UserCacheService userCacheService;
    private final SessionExpiryIndex sessionExpiryIndex;
    private final IdleSessionConfig config;
    private final AccountProducer accountProducer;
    private final MonitoringService monitoringService;

    /** Guards against concurrent scheduler executions (equivalent to ConcurrentExecution.SKIP). */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Scheduled task to terminate idle sessions using optimized index-based lookup.
     * Runs at configurable intervals defined by idle-session.scheduler-interval-ms.
     */
    @Scheduled(fixedDelayString = "${idle-session.scheduler-interval-ms:300000}")
    public void terminateIdleSessions() {
        if (!config.isEnabled()) {
            log.debug("[{}] Idle session terminator is disabled, skipping execution", M_TERMINATE);
            return;
        }

        if (!isRunning.compareAndSet(false, true)) {
            log.debug("[{}] Previous run still in progress, skipping concurrent execution", M_TERMINATE);
            return;
        }

        long startTime = System.currentTimeMillis();
        int timeoutMinutes = config.getTimeoutMinutes();
        int batchSize = config.getBatchSize();

        // Calculate expiry threshold - the index stores absolute expiry timestamps,
        // so any session whose expiry score is <= now is considered expired
        long expiryThresholdMillis = Instant.now().toEpochMilli();

        log.info("[{}] Starting optimized idle session termination with timeout: {} minutes, threshold: {}",
                M_TERMINATE, timeoutMinutes, expiryThresholdMillis);


        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(expiryThresholdMillis), ZoneId.systemDefault());

        log.info("[{}] Starting optimized idle session termination with timeout: {} minutes, threshold: {}",
                M_TERMINATE, timeoutMinutes, dateTime);

        // First, log index stats for monitoring
        logIndexStats(expiryThresholdMillis);

        AtomicInteger totalSessionsTerminated = new AtomicInteger(0);
        AtomicInteger totalUsersProcessed     = new AtomicInteger(0);
        AtomicInteger totalBatchesProcessed   = new AtomicInteger(0);

        try {
            // Process expired sessions in batches using the optimized index
            processExpiredSessionsBatched(expiryThresholdMillis, batchSize,
                    totalSessionsTerminated, totalUsersProcessed, totalBatchesProcessed);

            int terminatedCount = totalSessionsTerminated.get();
            // Record idle session termination metrics
            monitoringService.recordIdleSessionsTerminated(terminatedCount);

            log.info("[{}] Idle session termination completed. Batches: {}, Users: {}, Sessions terminated: {}, Duration: {} ms",
                    M_TERMINATE,
                    totalBatchesProcessed.get(),
                    totalUsersProcessed.get(),
                    terminatedCount,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[{}] Error during idle session termination", M_TERMINATE, e);
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Process expired sessions in batches until no more expired sessions exist.
     * Continues processing while a full batch is returned.
     */
    private void processExpiredSessionsBatched(long expiryThresholdMillis, int batchSize,
                                               AtomicInteger totalSessionsTerminated,
                                               AtomicInteger totalUsersProcessed,
                                               AtomicInteger totalBatchesProcessed) {
        int processedCount;
        do {
            processedCount = processOneBatch(expiryThresholdMillis, batchSize,
                    totalSessionsTerminated, totalUsersProcessed, totalBatchesProcessed);
            if (processedCount > 0 && processedCount >= batchSize) {
                log.debug("[{}] Batch complete with {} sessions, checking for more", M_PROCESS, processedCount);
            }
        } while (processedCount >= batchSize);
    }

    /**
     * Process a single batch of expired sessions.
     * Returns the number of sessions processed in this batch.
     */
    private int processOneBatch(long expiryThresholdMillis, int batchSize,
                                AtomicInteger totalSessionsTerminated,
                                AtomicInteger totalUsersProcessed,
                                AtomicInteger totalBatchesProcessed) {

        // Query expired sessions from index - O(log N + K) complexity
        List<SessionExpiryIndex.SessionExpiryEntry> expiredEntries =
                sessionExpiryIndex.getExpiredSessions(expiryThresholdMillis, batchSize);

        if (expiredEntries.isEmpty()) {
            log.debug("[{}] No expired sessions found in this batch", M_PROCESS);
            return 0;
        }

        totalBatchesProcessed.incrementAndGet();
        log.info("[{}] Processing batch of {} expired sessions", M_PROCESS, expiredEntries.size());

        // Group by userId for efficient batch processing
        Map<String, List<SessionExpiryIndex.SessionExpiryEntry>> sessionsByUser = expiredEntries.stream()
                .collect(Collectors.groupingBy(SessionExpiryIndex.SessionExpiryEntry::userId));

        List<String> userIds = new ArrayList<>(sessionsByUser.keySet());
        totalUsersProcessed.addAndGet(userIds.size());

        // Fetch user data using MGET - single network round trip
        Map<String, UserSessionData> userDataMap = userCacheService.getUserDataBatchAsMap(userIds);

        processUsersAndCleanupIndex(userDataMap, sessionsByUser, totalSessionsTerminated);

        return expiredEntries.size();
    }

    /**
     * Process users with expired sessions and clean up the index.
     */
    private void processUsersAndCleanupIndex(Map<String, UserSessionData> userDataMap,
                                             Map<String, List<SessionExpiryIndex.SessionExpiryEntry>> sessionsByUser,
                                             AtomicInteger totalSessionsTerminated) {

        List<String> membersToRemove = new ArrayList<>();

        for (Map.Entry<String, List<SessionExpiryIndex.SessionExpiryEntry>> entry : sessionsByUser.entrySet()) {
            String userId = entry.getKey();
            List<SessionExpiryIndex.SessionExpiryEntry> expiredSessions = entry.getValue();
            UserSessionData userData = userDataMap.get(userId);

            // Collect members to remove from index
            for (SessionExpiryIndex.SessionExpiryEntry sessionEntry : expiredSessions) {
                membersToRemove.add(sessionEntry.rawMember());
            }

            if (userData == null) {
                log.debug("[{}] User data not found for userId: {}, will clean up index entries", M_PROCESS, userId);
                continue;
            }

            try {
                processUserExpiredSessions(userData, expiredSessions, totalSessionsTerminated);
            } catch (Exception e) {
                log.error("[{}] Error processing sessions for userId: {}", M_PROCESS, userId, e);
            }
        }

        // Remove processed entries from index in batch
        long removed = sessionExpiryIndex.removeSessions(membersToRemove);
        log.debug("[{}] Removed {} entries from session expiry index", M_PROCESS, removed);
    }

    /**
     * Process expired sessions for a single user.
     * This method removes expired sessions from cache and triggers DB write operations
     * to persist balance updates for terminated sessions.
     * Also checks for absolute session timeout based on sessionInitiatedTime and sessionTimeOut.
     */
    private void processUserExpiredSessions(UserSessionData userData,
                                            List<SessionExpiryIndex.SessionExpiryEntry> expiredSessionEntries,
                                            AtomicInteger totalSessionsTerminated) {
        if (userData.getSessions() == null || userData.getSessions().isEmpty()) {
            return;
        }

        String userName = userData.getUserName();
        List<Session> sessions = userData.getSessions();

        // Build set of expired session IDs for O(1) lookup - using efficient loop instead of stream
        int expiredCount = expiredSessionEntries.size();
        var expiredSessionIds = HashSet.newHashSet(expiredCount);
        for (SessionExpiryIndex.SessionExpiryEntry entry : expiredSessionEntries) {
            expiredSessionIds.add(entry.sessionId());
        }

        // Find sessions to terminate - using efficient loop instead of stream
        // Check both idle timeout (from index) and absolute timeout (from sessionInitiatedTime)
        List<Session> sessionsToTerminate = new ArrayList<>();
        for (Session session : sessions) {
            boolean shouldTerminate = expiredSessionIds.contains(session.getSessionId()) ||
                    isSessionAbsoluteTimeoutExceeded(session);
            if (shouldTerminate) {
                sessionsToTerminate.add(session);
            }
        }

        if (sessionsToTerminate.isEmpty()) {
            // Sessions may have been terminated by other means
            log.debug("[{}] No matching sessions found for user {}, may have been terminated already", M_PROCESS, userName);
            return;
        }

        log.info("[{}] Terminating {} idle sessions for user: {}", M_PROCESS, sessionsToTerminate.size(), userName);

        // Remove terminated sessions
        List<Session> activeSessions = new ArrayList<>(sessions);
        activeSessions.removeAll(sessionsToTerminate);
        userData.setSessions(activeSessions);

        totalSessionsTerminated.addAndGet(sessionsToTerminate.size());

        // Trigger DB write operations for terminated sessions to persist balance state
        triggerDBRequestInitiate(sessionsToTerminate, userData);

        // Generate a CDR (Call Detail Record) and publish a Kafka event for each terminated session
        generateIdleTimeoutCdrEvents(sessionsToTerminate, userData);

        // Update cache after DB write is initiated
        try {
            userCacheService.updateUserAndRelatedCaches(userName, userData, userName);
        } catch (Exception e) {
            log.error("[{}] Failed to update cache for user: {}", M_PROCESS, userName, e);
        }
    }

    /**
     * Find a balance matching the given bucket ID.
     *
     * @param balances List of balances to search
     * @param bucketId The bucket ID to match
     * @return Matching balance or null if not found
     */
    private Balance findBalanceByBucketId(List<Balance> balances, String bucketId) {
        for (Balance balance : balances) {
            if (bucketId.equals(balance.getBucketId())) {
                return balance;
            }
        }
        return null;
    }

    /**
     * Create a DB write operation for a session if the balance needs to be persisted.
     *
     * @param session The session being terminated
     * @param balance The matching balance
     */
    private void createDBWriteOperationIfNeeded(Session session, Balance balance) {
        if (balance.getQuota() < session.getAvailableBalance()) {
            return;
        }

        DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(
                balance,
                balance.getBucketUsername(),
                session.getSessionId(),
                EventType.UPDATE_EVENT
        );

        log.debug("[{}] Triggered DB write for terminated session: {}, bucketId: {}",
                M_PROCESS, session.getSessionId(), balance.getBucketId());

        try {
            accountProducer.produceDBWriteEvent(dbWriteRequest);
        } catch (Exception e) {
            log.error("[{}] Failed to produce DB write event for session: {}", M_PROCESS, session.getSessionId(), e);
        }
    }

    /**
     * Process a single session and create a DB write operation if needed.
     *
     * @param session  The session to process
     * @param balances List of balances to search for matching bucket
     */
    private void processSessionForDBWrite(Session session, List<Balance> balances) {
        String bucketId = session.getPreviousUsageBucketId();
        if (bucketId == null) {
            return;
        }

        Balance matchingBalance = findBalanceByBucketId(balances, bucketId);
        if (matchingBalance == null) {
            return;
        }

        createDBWriteOperationIfNeeded(session, matchingBalance);
    }

    /**
     * Triggers DB write operations to persist balance state for terminated sessions.
     * Uses efficient loops to avoid stream overhead and produces events for each session.
     *
     * @param sessionsToTerminate list of sessions being terminated
     * @param userData            user session data containing balance information
     */
    private void triggerDBRequestInitiate(List<Session> sessionsToTerminate, UserSessionData userData) {
        if (sessionsToTerminate == null || sessionsToTerminate.isEmpty()) {
            return;
        }

        List<Balance> balances = userData.getBalance();
        if (balances == null || balances.isEmpty()) {
            return;
        }

        for (Session session : sessionsToTerminate) {
            processSessionForDBWrite(session, balances);
        }
    }

    /**
     * Generates a Stop CDR and publishes a Kafka event for each session terminated
     * due to idle timeout. Failures are logged per-session and never abort the
     * termination flow for the remaining sessions.
     *
     * @param sessionsToTerminate the sessions being terminated
     * @param userData            user session data used to backfill user identity
     */
    private void generateIdleTimeoutCdrEvents(List<Session> sessionsToTerminate, UserSessionData userData) {
        if (sessionsToTerminate == null || sessionsToTerminate.isEmpty()) {
            return;
        }

        for (Session session : sessionsToTerminate) {
            try {
                // Backfill user identity from the cache entry when the session omits it
                if (session.getUserName() == null) {
                    session.setUserName(userData.getUserName());
                }
                if (session.getGroupId() == null) {
                    session.setGroupId(userData.getGroupId());
                }

                AccountingCdrEvent cdrEvent = CdrMappingUtil.buildIdleTimeoutStopCdrEvent(session);
                accountProducer.produceCdrEvent(cdrEvent);

                log.debug("[{}] Generated idle-timeout CDR event for session: {}",
                        M_PROCESS, session.getSessionId());
            } catch (Exception e) {
                log.error("[{}] Failed to generate CDR event for session: {}",
                        M_PROCESS, session.getSessionId(), e);
            }
        }
    }

    /**
     * Log index statistics for monitoring.
     */
    private void logIndexStats(long expiryThresholdMillis) {
        try {
            long total = sessionExpiryIndex.getTotalIndexedSessions();
            log.info("[{}] Session expiry index stats - Total indexed: {}", M_TERMINATE, total);
        } catch (Exception e) {
            log.warn("[{}] Failed to get index stats: {}", M_TERMINATE, e.getMessage());
        }

        try {
            long expired = sessionExpiryIndex.getExpiredSessionCount(expiryThresholdMillis);
            log.info("[{}] Session expiry index stats - Expired sessions: {}", M_TERMINATE, expired);
        } catch (Exception e) {
            log.warn("[{}] Failed to get expired count: {}", M_TERMINATE, e.getMessage());
        }
    }

    /**
     * Checks if a session has exceeded its absolute timeout based on sessionInitiatedTime and sessionTimeOut.
     *
     * @param session The session to check
     * @return true if the session has exceeded the absolute timeout, false otherwise
     */
    private boolean isSessionAbsoluteTimeoutExceeded(Session session) {
        if (session == null
                || session.getSessionInitiatedTime() == null
                || session.getAbsoluteTimeOut() == null
                || session.getSessionStartTime() == null) {
            return false;
        }

        try {
            // Parse sessionTimeOut as minutes
            long timeoutMinutes = Long.parseLong(session.getAbsoluteTimeOut().trim());

            // Calculate when the session should expire (sessionStartTime + timeoutMinutes)
            LocalDateTime sessionExpiryTime = session.getSessionStartTime().plusMinutes(timeoutMinutes);

            // Check if current time has exceeded the expiry time
            LocalDateTime currentTime = LocalDateTime.now();
            boolean isExpired = currentTime.isAfter(sessionExpiryTime);

            if (isExpired) {
                log.info("[{}] Absolute timeout exceeded for session: {}, initiated: {}, timeout: {} minutes, expiry: {}",
                        M_PROCESS, session.getSessionId(), session.getSessionInitiatedTime(), timeoutMinutes, sessionExpiryTime);
            }

            return isExpired;
        } catch (NumberFormatException e) {
            log.warn("[{}] Invalid sessionTimeOut format: {}. Expected numeric value in minutes. Error: {}",
                    M_PROCESS, session.getAbsoluteTimeOut().trim(), e.getMessage());
            return false;
        }
    }
}
