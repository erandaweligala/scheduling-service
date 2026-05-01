package com.axonect.aee.template.baseapp.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceInstanceSchedulerTest {

    @Mock
    private ServiceInstanceLifecycleService lifecycleService;

    @InjectMocks
    private ServiceInstanceScheduler scheduler;

    @Test
    @DisplayName("Activation scheduler delegates to lifecycle service")
    void scheduleActivate_delegates() {
        when(lifecycleService.activatePendingServiceInstances(anyString())).thenReturn(7);

        scheduler.scheduleActivatePendingServiceInstances();

        verify(lifecycleService, times(1)).activatePendingServiceInstances(anyString());
    }

    @Test
    @DisplayName("Activation scheduler swallows exceptions so the cron keeps running")
    void scheduleActivate_swallowsException() {
        when(lifecycleService.activatePendingServiceInstances(anyString()))
                .thenThrow(new RuntimeException("DB outage"));

        scheduler.scheduleActivatePendingServiceInstances();

        verify(lifecycleService, times(1)).activatePendingServiceInstances(anyString());
    }

    @Test
    @DisplayName("Cleanup scheduler delegates to lifecycle service")
    void scheduleCleanup_delegates() {
        when(lifecycleService.cleanupExpiredServiceInstances(anyString()))
                .thenReturn(new ServiceInstanceLifecycleService.CleanupSummary());

        scheduler.scheduleCleanupExpiredServiceInstances();

        verify(lifecycleService, times(1)).cleanupExpiredServiceInstances(anyString());
    }

    @Test
    @DisplayName("Cleanup scheduler swallows exceptions so the cron keeps running")
    void scheduleCleanup_swallowsException() {
        doThrow(new RuntimeException("boom"))
                .when(lifecycleService).cleanupExpiredServiceInstances(anyString());

        scheduler.scheduleCleanupExpiredServiceInstances();

        verify(lifecycleService, times(1)).cleanupExpiredServiceInstances(anyString());
    }
}
