package com.axonect.aee.template.baseapp.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis Sorted Set based index for tracking session expiry times.
 * Provides O(log N) complexity for finding expired sessions instead of O(N) full scan.
 *
 * <p>Key structure:
 * <ul>
 *   <li>Sorted Set Key: "session:expiry:index"</li>
 *   <li>Member: "userId:sessionId"</li>
 *   <li>Score: Unix timestamp of expected expiry time (milliseconds)</li>
 * </ul>
 *
 * <p>Complexity:
 * <ul>
 *   <li>ZADD: O(log N) per insertion</li>
 *   <li>ZRANGEBYSCORE: O(log N + K) where K is expired count</li>
 *   <li>ZREM: O(log N) per removal</li>
 * </ul>
 */
@Component
@Slf4j
public class SessionExpiryIndex {

    private static final String EXPIRY_INDEX_KEY = "session:";
    private static final String MEMBER_SEPARATOR = ":";

    private final ZSetOperations<String, String> zSetOps;

    public SessionExpiryIndex(RedisTemplate<String, String> redisTemplateString) {
        this.zSetOps = redisTemplateString.opsForZSet();
    }

    /**
     * Register a session with its expected expiry time.
     * Call this when a session is created or updated.
     *
     * @param userId           the user ID
     * @param sessionId        the session ID
     * @param expiryTimeMillis Unix timestamp when session should expire
     */
    public void registerSession(String userId, String sessionId, long expiryTimeMillis) {
        String member = buildMember(userId, sessionId);
        zSetOps.add(EXPIRY_INDEX_KEY, member, expiryTimeMillis);
        log.debug("Registered session in expiry index: {} -> {}", member, expiryTimeMillis);
    }

    /**
     * Update a session's expiry time after activity.
     *
     * @param userId              the user ID
     * @param sessionId           the session ID
     * @param newExpiryTimeMillis new expiry timestamp
     */
    public void updateSessionExpiry(String userId, String sessionId, long newExpiryTimeMillis) {
        String member = buildMember(userId, sessionId);
        zSetOps.add(EXPIRY_INDEX_KEY, member, newExpiryTimeMillis);
        log.debug("Updated session expiry: {} -> {}", member, newExpiryTimeMillis);
    }

    /**
     * Remove a single session from the expiry index.
     * Call this when a session is terminated.
     *
     * @param userId    the user ID
     * @param sessionId the session ID
     */
    public void removeSession(String userId, String sessionId) {
        String member = buildMember(userId, sessionId);
        zSetOps.remove(EXPIRY_INDEX_KEY, (Object) member);
        log.debug("Removed session from expiry index: {}", member);
    }

    /**
     * Remove multiple sessions from the expiry index in a single operation.
     *
     * @param members list of raw "userId:sessionId" members to remove
     * @return count of removed members
     */
    public long removeSessions(List<String> members) {
        if (members == null || members.isEmpty()) {
            return 0;
        }
        Long removed = zSetOps.remove(EXPIRY_INDEX_KEY, members.toArray());
        return removed != null ? removed : 0;
    }

    /**
     * Get expired sessions up to a given timestamp with a batch limit.
     * This is the core optimization: O(log N + K) instead of O(N) full scan.
     *
     * @param expiryThresholdMillis sessions with score <= this value are considered expired
     * @param limit                 maximum number of sessions to return
     * @return list of SessionExpiryEntry with userId and sessionId
     */
    public List<SessionExpiryEntry> getExpiredSessions(long expiryThresholdMillis, int limit) {
        log.info("Querying expired sessions with threshold: {}, limit: {}", expiryThresholdMillis, limit);

        Set<String> members = zSetOps.rangeByScore(
                EXPIRY_INDEX_KEY,
                Double.NEGATIVE_INFINITY,
                expiryThresholdMillis,
                0,
                limit
        );

        List<SessionExpiryEntry> entries = new ArrayList<>();
        if (members != null) {
            for (String member : members) {
                SessionExpiryEntry entry = parseMember(member);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    /**
     * Get count of expired sessions for monitoring.
     *
     * @param expiryThresholdMillis threshold timestamp
     * @return count of expired sessions
     */
    public long getExpiredSessionCount(long expiryThresholdMillis) {
        Long count = zSetOps.count(EXPIRY_INDEX_KEY, Double.NEGATIVE_INFINITY, expiryThresholdMillis);
        return count != null ? count : 0;
    }

    /**
     * Get total sessions in the index for monitoring.
     *
     * @return total count of indexed sessions
     */
    public long getTotalIndexedSessions() {
        Long total = zSetOps.size(EXPIRY_INDEX_KEY);
        return total != null ? total : 0;
    }

    private String buildMember(String userId, String sessionId) {
        return userId + MEMBER_SEPARATOR + sessionId;
    }

    private SessionExpiryEntry parseMember(String member) {
        if (member == null || member.isEmpty()) {
            return null;
        }
        int separatorIndex = member.indexOf(MEMBER_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex >= member.length() - 1) {
            log.warn("Invalid member format in expiry index: {}", member);
            return null;
        }
        String userId = member.substring(0, separatorIndex);
        String sessionId = member.substring(separatorIndex + 1);
        return new SessionExpiryEntry(userId, sessionId, member);
    }

    /**
     * Entry representing an expired session from the index.
     */
    public record SessionExpiryEntry(String userId, String sessionId, String rawMember) {
    }
}
