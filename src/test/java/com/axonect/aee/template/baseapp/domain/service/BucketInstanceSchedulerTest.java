package com.axonect.aee.template.baseapp.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BucketInstanceSchedulerTest {

    @Mock
    private DeleteBucketInstanceService deleteBucketInstanceService;

    @Mock
    private ExpiryNotificationService expiryNotificationService;

    @Mock
    private BucketInstanceScheduler self; // Mock for the self-proxy

    @InjectMocks
    private BucketInstanceScheduler scheduler;

    // --- Tests for scheduleDeleteExpiredBuckets ---

    @Test
    @DisplayName("Should successfully call transactional delete method")
    void scheduleDeleteExpiredBuckets_Success() {
        // Since 'self' is @Autowired @Lazy, we set it manually via ReflectionTestUtils
        ReflectionTestUtils.setField(scheduler, "self", self);

        scheduler.scheduleDeleteExpiredBuckets();

        verify(self, times(1)).deleteExpiredBucketsTransactional();
    }

    @Test
    @DisplayName("Should log error when transactional delete fails")
    void scheduleDeleteExpiredBuckets_Exception() {
        ReflectionTestUtils.setField(scheduler, "self", self);
        doThrow(new RuntimeException("DB Error")).when(self).deleteExpiredBucketsTransactional();

        // This should catch the exception internally and log it (verified by non-explosion)
        scheduler.scheduleDeleteExpiredBuckets();

        verify(self, times(1)).deleteExpiredBucketsTransactional();
    }

    @Test
    @DisplayName("Should call service layer when transactional method is executed")
    void deleteExpiredBucketsTransactional_Success() {
        scheduler.deleteExpiredBucketsTransactional();
        verify(deleteBucketInstanceService, times(1)).deleteExpiredBucketInstance();
    }

    // --- Tests for scheduleExpiryNotifications ---

    @Test
    @DisplayName("Should successfully process expiry notifications")
    void scheduleExpiryNotifications_Success() {
        when(expiryNotificationService.processExpiryNotifications()).thenReturn(5);

        scheduler.scheduleExpiryNotifications();

        verify(expiryNotificationService, times(1)).processExpiryNotifications();
    }

}