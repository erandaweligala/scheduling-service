package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.IdleSessionConfig;
import com.axonect.aee.template.baseapp.application.repository.BucketBalanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketBalanceUpdate;
import com.axonect.aee.template.baseapp.domain.entities.dto.Session;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdleSessionTerminatorSchedulerTest {

    private static final int BATCH_SIZE = 10;

    @Mock
    private UserCacheService userCacheService;
    @Mock
    private SessionExpiryIndex sessionExpiryIndex;
    @Mock
    private IdleSessionConfig config;
    @Mock
    private AccountProducer accountProducer;
    @Mock
    private BucketBalanceRepository bucketBalanceRepository;
    @Mock
    private MonitoringService monitoringService;

    @InjectMocks
    private IdleSessionTerminatorScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(config.isEnabled()).thenReturn(true);
        when(config.getBatchSize()).thenReturn(BATCH_SIZE);
    }

    @Test
    @DisplayName("Persists the final balances of a whole batch with one call, deduplicated and ordered by bucket ID")
    void terminateIdleSessions_persistsBalancesOncePerBatch() {
        UserSessionData user1 = user("u1", balance("12", "5", 1000L, 400L),
                session("s1", "12", 400L), session("s2", "12", 400L));
        UserSessionData user2 = user("u2", balance("11", "6", 500L, 100L),
                session("s3", "11", 50L));
        givenExpiredSessions(Map.of("u1", user1, "u2", user2),
                entry("u1", "s1"), entry("u1", "s2"), entry("u2", "s3"));

        scheduler.terminateIdleSessions();

        ArgumentCaptor<Collection<BucketBalanceUpdate>> updates = ArgumentCaptor.forClass(Collection.class);
        verify(bucketBalanceRepository, times(1)).updateBalances(updates.capture());
        assertThat(updates.getValue()).containsExactly(
                new BucketBalanceUpdate(11L, 6L, 100L, 400L),
                new BucketBalanceUpdate(12L, 5L, 400L, 600L));
        verify(userCacheService).updateUserAndRelatedCaches(eq("u1"), eq(user1), eq("u1"));
        verify(userCacheService).updateUserAndRelatedCaches(eq("u2"), eq(user2), eq("u2"));
        assertThat(user1.getSessions()).isEmpty();
    }

    @Test
    @DisplayName("Skips balances whose quota is below the session's available balance")
    void terminateIdleSessions_skipsBalanceBelowAvailable() {
        UserSessionData user1 = user("u1", balance("12", "5", 1000L, 100L), session("s1", "12", 400L));
        givenExpiredSessions(Map.of("u1", user1), entry("u1", "s1"));

        scheduler.terminateIdleSessions();

        verify(bucketBalanceRepository, never()).updateBalances(any());
        verify(userCacheService).updateUserAndRelatedCaches(eq("u1"), eq(user1), eq("u1"));
    }

    @Test
    @DisplayName("A balance with a non-numeric ID is skipped without affecting the rest of the batch")
    void terminateIdleSessions_skipsInvalidBalance() {
        UserSessionData user1 = user("u1", balance("not-a-number", "5", 1000L, 400L), session("s1", "not-a-number", 400L));
        UserSessionData user2 = user("u2", balance("11", "6", 500L, 100L), session("s3", "11", 50L));
        givenExpiredSessions(Map.of("u1", user1, "u2", user2), entry("u1", "s1"), entry("u2", "s3"));

        scheduler.terminateIdleSessions();

        ArgumentCaptor<Collection<BucketBalanceUpdate>> updates = ArgumentCaptor.forClass(Collection.class);
        verify(bucketBalanceRepository).updateBalances(updates.capture());
        assertThat(updates.getValue()).containsExactly(new BucketBalanceUpdate(11L, 6L, 100L, 400L));
        verify(userCacheService).updateUserAndRelatedCaches(eq("u1"), eq(user1), eq("u1"));
    }

    @Test
    @DisplayName("A failed balance write does not stop the index cleanup")
    void terminateIdleSessions_dbFailureStillCleansIndex() {
        UserSessionData user1 = user("u1", balance("12", "5", 1000L, 400L), session("s1", "12", 400L));
        givenExpiredSessions(Map.of("u1", user1), entry("u1", "s1"));
        when(bucketBalanceRepository.updateBalances(any())).thenThrow(new CannotAcquireLockException("ORA-00054"));

        scheduler.terminateIdleSessions();

        verify(sessionExpiryIndex).removeSessions(List.of("u1:s1"));
        verify(monitoringService).recordIdleSessionsTerminated(1);
    }

    private void givenExpiredSessions(Map<String, UserSessionData> users,
                                      SessionExpiryIndex.SessionExpiryEntry... entries) {
        when(sessionExpiryIndex.getExpiredSessions(anyLong(), eq(BATCH_SIZE))).thenReturn(List.of(entries));
        when(userCacheService.getUserDataBatchAsMap(anyList())).thenReturn(users);
    }

    private static SessionExpiryIndex.SessionExpiryEntry entry(String userId, String sessionId) {
        return new SessionExpiryIndex.SessionExpiryEntry(userId, sessionId, userId + ":" + sessionId);
    }

    private static UserSessionData user(String userName, Balance balance, Session... sessions) {
        UserSessionData data = new UserSessionData();
        data.setUserName(userName);
        data.setBalance(new ArrayList<>(List.of(balance)));
        data.setSessions(new ArrayList<>(List.of(sessions)));
        return data;
    }

    private static Balance balance(String bucketId, String serviceId, long initialBalance, long quota) {
        Balance balance = new Balance();
        balance.setBucketId(bucketId);
        balance.setServiceId(serviceId);
        balance.setInitialBalance(initialBalance);
        balance.setQuota(quota);
        return balance;
    }

    private static Session session(String sessionId, String bucketId, long availableBalance) {
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setPreviousUsageBucketId(bucketId);
        session.setAvailableBalance(availableBalance);
        return session;
    }
}
