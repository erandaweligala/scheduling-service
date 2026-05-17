package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceHistoryRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.DBWriteRequest;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstanceHistory;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstanceHistory;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    @Mock
    private RecurrentServiceProducer recurrentServiceProducer;

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
    @DisplayName("Activates pending services in chunks until none remain")
    void activatePendingServiceInstances_processesAllChunks() {
        ServiceInstance s1 = serviceWithId(1L);
        ServiceInstance s2 = serviceWithId(2L);
        ServiceInstance s3 = serviceWithId(3L);

        when(serviceInstanceRepository.findByStatus(eq(Constants.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1, s2)))
                .thenReturn(new PageImpl<>(List.of(s3)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        when(serviceInstanceRepository.bulkUpdateStatus(anyCollection(),
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(2)
                .thenReturn(1);

        int updated = service.activatePendingServiceInstances("batch-1");

        assertThat(updated).isEqualTo(3);
        verify(serviceInstanceRepository, times(3))
                .findByStatus(eq(Constants.PENDING), any(Pageable.class));
        verify(serviceInstanceRepository, times(2))
                .bulkUpdateStatus(anyCollection(), eq(Constants.PENDING),
                        eq(Constants.ACTIVE), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Activation publishes one UPDATE DB write event per service in chunk")
    void activatePendingServiceInstances_publishesKafkaEvents() {
        ServiceInstance s1 = serviceWithId(1L);
        ServiceInstance s2 = serviceWithId(2L);

        when(serviceInstanceRepository.findByStatus(eq(Constants.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1, s2)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(serviceInstanceRepository.bulkUpdateStatus(anyCollection(),
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(2);

        service.activatePendingServiceInstances("batch-kafka");

        ArgumentCaptor<DBWriteRequest> requestCaptor = ArgumentCaptor.forClass(DBWriteRequest.class);
        verify(recurrentServiceProducer, times(2))
                .publishDBWriteEvent(requestCaptor.capture(), anyString());
        assertThat(requestCaptor.getAllValues())
                .extracting(DBWriteRequest::getEventType)
                .containsOnly("UPDATE");
        assertThat(requestCaptor.getAllValues())
                .extracting(DBWriteRequest::getTableName)
                .containsOnly("SERVICE_INSTANCE");
        assertThat(requestCaptor.getAllValues())
                .allMatch(r -> Constants.ACTIVE.equals(r.getColumnValues().get("STATUS")));
    }

    @Test
    @DisplayName("Activation skips Kafka events when no rows were updated")
    void activatePendingServiceInstances_skipsKafkaWhenZeroUpdated() {
        ServiceInstance s1 = serviceWithId(1L);
        when(serviceInstanceRepository.findByStatus(eq(Constants.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1)));
        when(serviceInstanceRepository.bulkUpdateStatus(anyCollection(),
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(0);

        service.activatePendingServiceInstances("batch-zero");

        verify(recurrentServiceProducer, never()).publishDBWriteEvent(any(), anyString());
    }

    @Test
    @DisplayName("Activation stops if a chunk reports zero rows updated")
    void activatePendingServiceInstances_stopsOnZeroUpdated() {
        ServiceInstance s1 = serviceWithId(1L);
        when(serviceInstanceRepository.findByStatus(eq(Constants.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1)));
        when(serviceInstanceRepository.bulkUpdateStatus(anyCollection(),
                eq(Constants.PENDING), eq(Constants.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(0);

        int updated = service.activatePendingServiceInstances("batch-x");

        assertThat(updated).isZero();
        verify(serviceInstanceRepository, times(1))
                .findByStatus(eq(Constants.PENDING), any(Pageable.class));
    }

    // -------- cleanup --------

    @Test
    @DisplayName("Archives services and buckets, then deletes both, before stopping when next page is empty")
    void cleanupExpiredServiceInstances_archivesAndDeletes() {
        LocalDate yesterday = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).minusDays(1);
        LocalDateTime cycleEndYesterday = yesterday.atTime(10, 0);
        LocalDateTime expiryYesterday = yesterday.atTime(15, 0);

        ServiceInstance s1 = serviceWithId(101L);
        s1.setServiceCycleEndDate(cycleEndYesterday);
        s1.setExpiryDate(yesterday.plusDays(30).atStartOfDay());

        ServiceInstance s2 = serviceWithId(102L);
        s2.setServiceCycleEndDate(yesterday.plusDays(5).atStartOfDay());
        s2.setExpiryDate(expiryYesterday);

        BucketInstance b1 = bucket(11L, 101L);
        BucketInstance b2 = bucket(12L, 102L);

        when(serviceInstanceRepository.findByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1, s2)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        when(bucketInstanceRepository.findByServiceIdIn(any(Set.class)))
                .thenReturn(List.of(b1, b2));
        when(bucketInstanceRepository.deleteAllByServiceIdIn(any(Set.class))).thenReturn(2);
        when(serviceInstanceRepository.deleteAllByIdIn(anyCollection())).thenReturn(2);

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-c");

        assertThat(summary.getServicesDeleted()).isEqualTo(2);
        assertThat(summary.getBucketsDeleted()).isEqualTo(2);
        assertThat(summary.getFailedChunks()).isZero();

        ArgumentCaptor<List<ServiceInstanceHistory>> serviceHistoryCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(serviceInstanceHistoryRepository).saveAll(serviceHistoryCaptor.capture());
        List<ServiceInstanceHistory> savedServices = serviceHistoryCaptor.getValue();
        assertThat(savedServices).hasSize(2);
        assertThat(savedServices)
                .extracting(ServiceInstanceHistory::getOriginalId)
                .containsExactlyInAnyOrder(101L, 102L);
        assertThat(savedServices)
                .extracting(ServiceInstanceHistory::getDeletionReason)
                .containsExactlyInAnyOrder(
                        Constants.DELETION_REASON_CYCLE_END,
                        Constants.DELETION_REASON_EXPIRY);
        assertThat(savedServices).allMatch(h -> "batch-c".equals(h.getBatchId()));

        ArgumentCaptor<List<BucketInstanceHistory>> bucketHistoryCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(bucketInstanceHistoryRepository).saveAll(bucketHistoryCaptor.capture());
        List<BucketInstanceHistory> savedBuckets = bucketHistoryCaptor.getValue();
        assertThat(savedBuckets).hasSize(2);
        assertThat(savedBuckets)
                .extracting(BucketInstanceHistory::getOriginalId)
                .containsExactlyInAnyOrder(11L, 12L);

        verify(bucketInstanceRepository).deleteAllByServiceIdIn(Set.of(101L, 102L));
        ArgumentCaptor<Collection<Long>> idCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(serviceInstanceRepository).deleteAllByIdIn(idCaptor.capture());
        assertThat(idCaptor.getValue()).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    @DisplayName("Cleanup publishes a bundled DELETE event per service with related history inserts and bucket deletes")
    void cleanupExpiredServiceInstances_publishesKafkaEvents() {
        LocalDate yesterday = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).minusDays(1);
        ServiceInstance s1 = serviceWithId(301L);
        s1.setServiceCycleEndDate(yesterday.atTime(8, 0));
        s1.setExpiryDate(yesterday.plusDays(10).atStartOfDay());

        BucketInstance b1 = bucket(31L, 301L);

        when(serviceInstanceRepository.findByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(bucketInstanceRepository.findByServiceIdIn(any(Set.class))).thenReturn(List.of(b1));
        when(bucketInstanceRepository.deleteAllByServiceIdIn(any(Set.class))).thenReturn(1);
        when(serviceInstanceRepository.deleteAllByIdIn(anyCollection())).thenReturn(1);

        service.cleanupExpiredServiceInstances("batch-kafka");

        ArgumentCaptor<DBWriteRequest> requestCaptor = ArgumentCaptor.forClass(DBWriteRequest.class);
        verify(recurrentServiceProducer).publishDBWriteEvent(requestCaptor.capture(), eq("301"));

        DBWriteRequest mainEvent = requestCaptor.getValue();
        assertThat(mainEvent.getEventType()).isEqualTo("DELETE");
        assertThat(mainEvent.getTableName()).isEqualTo("SERVICE_INSTANCE");
        assertThat(mainEvent.getWhereConditions()).containsEntry("ID", 301L);
        assertThat(mainEvent.getRelatedWrites()).isNotNull();
        assertThat(mainEvent.getRelatedWrites())
                .extracting(DBWriteRequest::getTableName)
                .containsExactly("SERVICE_INSTANCE_HISTORY", "BUCKET_INSTANCE_HISTORY", "BUCKET_INSTANCE");
        assertThat(mainEvent.getRelatedWrites())
                .extracting(DBWriteRequest::getEventType)
                .containsExactly("INSERT", "INSERT", "DELETE");
    }

    @Test
    @DisplayName("Cleanup with no candidates is a no-op")
    void cleanupExpiredServiceInstances_noCandidates() {
        when(serviceInstanceRepository.findByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-empty");

        assertThat(summary.getServicesDeleted()).isZero();
        assertThat(summary.getBucketsDeleted()).isZero();
        verify(serviceInstanceHistoryRepository, never()).saveAll(any());
        verify(bucketInstanceHistoryRepository, never()).saveAll(any());
        verify(bucketInstanceRepository, never()).deleteAllByServiceIdIn(any());
        verify(serviceInstanceRepository, never()).deleteAllByIdIn(any());
        verify(recurrentServiceProducer, never()).publishDBWriteEvent(any(), anyString());
    }

    @Test
    @DisplayName("Cleanup still archives services even when no buckets exist for them")
    void cleanupExpiredServiceInstances_noBuckets() {
        LocalDate yesterday = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).minusDays(1);
        ServiceInstance s1 = serviceWithId(201L);
        s1.setServiceCycleEndDate(yesterday.atTime(8, 0));
        s1.setExpiryDate(yesterday.plusDays(10).atStartOfDay());

        when(serviceInstanceRepository.findByCycleEndOrExpiryWithinDay(
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(bucketInstanceRepository.findByServiceIdIn(any(Set.class)))
                .thenReturn(Collections.emptyList());
        when(serviceInstanceRepository.deleteAllByIdIn(anyCollection())).thenReturn(1);

        ServiceInstanceLifecycleService.CleanupSummary summary =
                service.cleanupExpiredServiceInstances("batch-no-buckets");

        assertThat(summary.getServicesDeleted()).isEqualTo(1);
        assertThat(summary.getBucketsDeleted()).isZero();
        verify(bucketInstanceHistoryRepository, never()).saveAll(any());
        verify(bucketInstanceRepository, never()).deleteAllByServiceIdIn(any());
    }

    private static ServiceInstance serviceWithId(Long id) {
        ServiceInstance s = ServiceInstance.builder()
                .planId("plan-" + id)
                .planName("name-" + id)
                .planType("type")
                .recurringFlag(Boolean.TRUE)
                .username("user-" + id)
                .serviceStartDate(LocalDateTime.now())
                .expiryDate(LocalDateTime.now().plusDays(30))
                .status(Constants.PENDING)
                .requestId("req-" + id)
                .build();
        s.setId(id);
        return s;
    }

    private static BucketInstance bucket(Long id, Long serviceId) {
        BucketInstance b = new BucketInstance();
        b.setId(id);
        b.setServiceId(serviceId);
        b.setBucketId("b-" + id);
        b.setRule("r");
        b.setPriority(1L);
        b.setInitialBalance(100L);
        b.setCurrentBalance(50L);
        b.setUsage(50L);
        b.setCarryForward(false);
        b.setTimeWindow("24H");
        b.setIsUnlimited(false);
        return b;
    }
}
