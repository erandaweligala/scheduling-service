package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceInstanceLifecycleServiceTest {

    @Mock
    private ServiceInstanceRepository serviceInstanceRepository;
    @Mock
    private BucketInstanceRepository bucketInstanceRepository;
    @Mock
    private ServiceInstanceHistoryRepository serviceInstanceHistoryRepository;
    @Mock
    private BucketInstanceHistoryRepository bucketInstanceHistoryRepository;

    @InjectMocks
    private ServiceInstanceLifecycleService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "activationChunkSize", 2);
        ReflectionTestUtils.setField(service, "cleanupChunkSize", 2);
        // 'self' is normally a @Lazy proxy provided by Spring; in unit tests we route
        // self-calls through the same instance so the @Transactional methods still execute.
        ReflectionTestUtils.setField(service, "self", service);
    }

    // -------- activation --------

    @Test
    @DisplayName("Activates pending services with one limited UPDATE per chunk until a short chunk")
    void activatePendingServiceInstances_processesAllChunks() {
        when(serviceInstanceRepository.updateStatusLimited(
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class), eq(2)))
                .thenReturn(2)
                .thenReturn(2)
                .thenReturn(1);

        int updated = service.activatePendingServiceInstances("batch-1");

        assertThat(updated).isEqualTo(5);
        verify(serviceInstanceRepository, times(3)).updateStatusLimited(
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class), eq(2));
    }

    @Test
    @DisplayName("Activation stops after a single statement when nothing is pending")
    void activatePendingServiceInstances_nothingPending() {
        when(serviceInstanceRepository.updateStatusLimited(
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class), anyInt()))
                .thenReturn(0);

        int updated = service.activatePendingServiceInstances("batch-zero");

        assertThat(updated).isZero();
        verify(serviceInstanceRepository, times(1)).updateStatusLimited(
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class), anyInt());
    }

    @Test
    @DisplayName("Activation keeps rows from committed chunks and stops when a chunk fails")
    void activatePendingServiceInstances_stopsOnChunkFailure() {
        when(serviceInstanceRepository.updateStatusLimited(
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class), anyInt()))
                .thenReturn(2)
                .thenThrow(new CannotAcquireLockException("ORA-00060"));

        int updated = service.activatePendingServiceInstances("batch-fail");

        assertThat(updated).isEqualTo(2);
        verify(serviceInstanceRepository, times(2)).updateStatusLimited(
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class), anyInt());
    }

    // -------- cleanup --------

    @Test
    @DisplayName("Archives services and their buckets, then deletes buckets before services, per chunk")
    void cleanupExpiredServiceInstances_archivesAndDeletes() {
        List<Long> chunk = List.of(101L, 102L);
        when(serviceInstanceRepository.findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(chunk)
                .thenReturn(Collections.emptyList());
        when(serviceInstanceHistoryRepository.archiveServiceInstances(
                eq(chunk), any(LocalDateTime.class), any(LocalDateTime.class), eq("batch-c"), any(LocalDateTime.class)))
                .thenReturn(2);
        when(bucketInstanceHistoryRepository.archiveBucketInstancesOfServices(
                eq(chunk), any(LocalDateTime.class), any(LocalDateTime.class), eq("batch-c"), any(LocalDateTime.class)))
                .thenReturn(3);
        when(bucketInstanceRepository.deleteAllByServiceIdIn(chunk)).thenReturn(3);
        when(serviceInstanceRepository.deleteAllByIdIn(chunk)).thenReturn(2);

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-c");

        assertThat(summary.getServicesDeleted()).isEqualTo(2);
        assertThat(summary.getBucketsDeleted()).isEqualTo(3);
        assertThat(summary.getFailedChunks()).isZero();

        InOrder order = inOrder(serviceInstanceHistoryRepository, bucketInstanceHistoryRepository,
                bucketInstanceRepository, serviceInstanceRepository);
        order.verify(serviceInstanceHistoryRepository).archiveServiceInstances(
                eq(chunk), any(), any(), eq("batch-c"), any());
        order.verify(bucketInstanceHistoryRepository).archiveBucketInstancesOfServices(
                eq(chunk), any(), any(), eq("batch-c"), any());
        order.verify(bucketInstanceRepository).deleteAllByServiceIdIn(chunk);
        order.verify(serviceInstanceRepository).deleteAllByIdIn(chunk);
    }

    @Test
    @DisplayName("Cleanup archives with yesterday's day window in the SL time zone")
    void cleanupExpiredServiceInstances_usesYesterdayWindow() {
        when(serviceInstanceRepository.findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(7L));
        when(serviceInstanceRepository.deleteAllByIdIn(anyList())).thenReturn(1);

        service.cleanupExpiredServiceInstances("batch-window");

        ArgumentCaptor<LocalDateTime> dayStart = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> dayEnd = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(serviceInstanceHistoryRepository).archiveServiceInstances(
                eq(List.of(7L)), dayStart.capture(), dayEnd.capture(), eq("batch-window"), any());

        LocalDateTime expectedStart = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).minusDays(1).atStartOfDay();
        assertThat(dayStart.getValue()).isEqualTo(expectedStart);
        assertThat(dayEnd.getValue()).isEqualTo(expectedStart.plusDays(1));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(serviceInstanceRepository).findIdsByCycleEndOrExpiryWithinDay(
                eq(expectedStart), eq(expectedStart.plusDays(1)), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cleanup does not query again after a chunk smaller than the chunk size")
    void cleanupExpiredServiceInstances_stopsAfterShortChunk() {
        when(serviceInstanceRepository.findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(201L));
        when(serviceInstanceRepository.deleteAllByIdIn(List.of(201L))).thenReturn(1);

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-short");

        assertThat(summary.getServicesDeleted()).isEqualTo(1);
        assertThat(summary.getBucketsDeleted()).isZero();
        verify(serviceInstanceRepository, times(1)).findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Cleanup stops if a chunk deletes no services, to avoid looping on the same rows")
    void cleanupExpiredServiceInstances_stopsWhenNothingDeleted() {
        when(serviceInstanceRepository.findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));
        when(serviceInstanceRepository.deleteAllByIdIn(anyList())).thenReturn(0);

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-stuck");

        assertThat(summary.getServicesDeleted()).isZero();
        verify(serviceInstanceRepository, times(1)).findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Cleanup counts a failed chunk and stops without deleting it")
    void cleanupExpiredServiceInstances_chunkFailure() {
        when(serviceInstanceRepository.findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));
        when(serviceInstanceHistoryRepository.archiveServiceInstances(
                anyList(), any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("ORA-00054"));

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-fail");

        assertThat(summary.getFailedChunks()).isEqualTo(1);
        assertThat(summary.getServicesDeleted()).isZero();
        verify(bucketInstanceRepository, never()).deleteAllByServiceIdIn(any());
        verify(serviceInstanceRepository, never()).deleteAllByIdIn(any());
    }

    @Test
    @DisplayName("Cleanup with no candidates is a no-op")
    void cleanupExpiredServiceInstances_noCandidates() {
        when(serviceInstanceRepository.findIdsByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-empty");

        assertThat(summary.getServicesDeleted()).isZero();
        assertThat(summary.getBucketsDeleted()).isZero();
        verifyNoInteractions(serviceInstanceHistoryRepository, bucketInstanceHistoryRepository,
                bucketInstanceRepository);
        verify(serviceInstanceRepository, never()).deleteAllByIdIn(any());
    }
}
