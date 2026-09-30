package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.entities.repo.*;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.Constants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurrentServiceServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ServiceInstanceRepository serviceInstanceRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanToBucketRepository planToBucketRepository;
    @Mock private BucketRepository bucketRepository;
    @Mock private QOSProfileRepository qosProfileRepository;
    @Mock private BucketInstanceRepository bucketInstanceRepository;
    @Mock private UserCacheService userCacheService;
    @Mock private ServiceProcessingFailureRepository serviceProcessingFailureRepository;

    @InjectMocks
    @Spy
    private RecurrentServiceService recurrentServiceService;

    private ServiceInstance serviceInstance;
    private UserEntity user;
    private Plan plan;
    private Bucket bucket;
    private QOSProfile qosProfile;
    private PlanToBucket planToBucket;

    @BeforeEach
    void setUp() {


        ReflectionTestUtils.setField(recurrentServiceService, "chunkSize", 10);
        ReflectionTestUtils.setField(recurrentServiceService, "self", recurrentServiceService);

        serviceInstance = new ServiceInstance();
        serviceInstance.setId(1L);
        serviceInstance.setUsername("testUser");
        serviceInstance.setPlanId("plan1");
        serviceInstance.setNextCycleStartDate(LocalDateTime.now().plusDays(1));
        serviceInstance.setExpiryDate(LocalDateTime.now().plusDays(30));

        user = new UserEntity();
        user.setUserName("testUser");
        user.setBilling("1");

        plan = new Plan();
        plan.setPlanId("plan1");
        plan.setRecurringPeriod("MONTHLY");
        plan.setRecurringFlag(true);

        bucket = new Bucket();
        bucket.setBucketId("b1");
        bucket.setQosId(10L);
        bucket.setBucketType("DATA");

        qosProfile = new QOSProfile();
        qosProfile.setId(10L);
        qosProfile.setBngCode("BNG001");

        planToBucket = new PlanToBucket();
        planToBucket.setBucketId("b1");
        planToBucket.setCarryForward(true);
        planToBucket.setMaxCarryForward(100L);
        planToBucket.setInitialQuota(500L);
        planToBucket.setCarryForwardValidity(7);
    }

    @Test
    void reactivateExpiredRecurrentServices_Success() {
        // 1. Setup Service Instance
        serviceInstance.setId(1L);
        serviceInstance.setPlanId("plan1");

        when(serviceInstanceRepository.findByRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(serviceInstance)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        // 2. Setup related data with non-null keys for grouping
        when(userRepository.findByUserNameIn(any())).thenReturn(List.of(user));
        when(planRepository.findByPlanIdIn(any())).thenReturn(List.of(plan));

        // Ensure PlanToBucket has the planId key
        planToBucket.setPlanId("plan1");
        when(planToBucketRepository.findByPlanIdIn(any())).thenReturn(List.of(planToBucket));

        // Ensure BucketInstance has the serviceId key
        BucketInstance bi = new BucketInstance();
        bi.setId(10L);
        bi.setServiceId(1L);
        bi.setBucketId("b1");
        bi.setBucketType("DATA");
        bi.setCurrentBalance(40L);
        bi.setExpiration(LocalDateTime.now().plusDays(1));
        when(bucketInstanceRepository.findByServiceIdIn(any())).thenReturn(List.of(bi));

        when(bucketRepository.findByBucketIdIn(any())).thenReturn(List.of(bucket));
        when(qosProfileRepository.findByIdIn(any())).thenReturn(List.of(qosProfile));
        when(serviceInstanceRepository.saveAndFlush(any(ServiceInstance.class)))
                .thenReturn(ServiceInstance.builder().id(900L).build());

        // Run service
        recurrentServiceService.reactivateExpiredRecurrentServices();

        verify(serviceInstanceRepository, times(1)).saveAndFlush(any(ServiceInstance.class));
        ArgumentCaptor<List<BucketInstance>> buckets = ArgumentCaptor.forClass(List.class);
        verify(bucketInstanceRepository).saveAll(buckets.capture());
        // One new quota bucket plus one carry-forward bucket, both owned by the inserted service row
        assertEquals(2, buckets.getValue().size());
        assertTrue(buckets.getValue().stream().allMatch(b -> Long.valueOf(900L).equals(b.getServiceId())));
        verify(serviceProcessingFailureRepository, never()).save(any());
    }

    @Test
    void processServiceInstanceInTransaction_FullFlow() {
        // 1. Setup Data
        String username = "testUser";
        String planId = "PLAN_001";
        String bucketId = "BUCKET_001";
        Long serviceId = 1L;


        serviceInstance.setId(serviceId);
        serviceInstance.setUsername(username);
        serviceInstance.setPlanId(planId);
        serviceInstance.setNextCycleStartDate(LocalDateTime.now().plusDays(1));
        serviceInstance.setExpiryDate(LocalDateTime.now().plusMonths(1));


        user.setUserName(username);
        user.setBilling("1");


        plan.setPlanId(planId);
        plan.setRecurringPeriod("MONTHLY");
        plan.setRecurringFlag(true);


        bucket.setBucketId(bucketId);
        bucket.setQosId(10L);
        bucket.setBucketType("DATA");


        qosProfile.setId(10L);
        qosProfile.setBngCode("BNG_TEST");

        PlanToBucket p2b = new PlanToBucket();
        p2b.setBucketId(bucketId);
        p2b.setCarryForward(true);
        p2b.setInitialQuota(1000L);
        p2b.setMaxCarryForward(500L);
        p2b.setCarryForwardValidity(30);
        p2b.setTotalCarryForward(2000L); // THIS MUST NOT BE NULL (Fixes line 562)
        p2b.setConsumptionLimitWindow("0");
        p2b.setIsUnlimited(false);

        BucketInstance existingInstance = new BucketInstance();
        existingInstance.setBucketId(bucketId);
        existingInstance.setServiceId(serviceId);
        existingInstance.setCurrentBalance(300L);
        existingInstance.setBucketType("CARRY_FORWARD"); // To trigger CF logic
        existingInstance.setExpiration(LocalDateTime.now().plusDays(5));
        existingInstance.setIsUnlimited(false);

        List<BucketInstance> bucketInstanceList = new ArrayList<>(List.of(existingInstance));
        List<PlanToBucket> quotaDetails = List.of(p2b);
        Map<String, Bucket> bucketMap = Map.of(bucketId, bucket);
        Map<Long, QOSProfile> qosProfileMap = Map.of(10L, qosProfile);

        // 2. Mocking
        when(userCacheService.getUserData(username)).thenReturn(new UserSessionData());
        when(serviceInstanceRepository.saveAndFlush(any(ServiceInstance.class)))
                .thenReturn(ServiceInstance.builder().id(500L).build());

        // 3. Execution
        assertDoesNotThrow(() -> {
            recurrentServiceService.processServiceInstanceInTransaction(
                    serviceInstance, user, plan, bucketInstanceList,
                    quotaDetails, bucketMap, qosProfileMap
            );
        });

        // 4. Verifications
        ArgumentCaptor<ServiceInstance> inserted = ArgumentCaptor.forClass(ServiceInstance.class);
        verify(serviceInstanceRepository, times(1)).saveAndFlush(inserted.capture());
        assertNull(inserted.getValue().getId());
        assertEquals(Constants.PENDING, inserted.getValue().getStatus());
        assertEquals(serviceInstance.getServiceCycleStartDate(), inserted.getValue().getServiceCycleStartDate());
        assertEquals(serviceInstance.getServiceCycleEndDate(), inserted.getValue().getServiceCycleEndDate());
        verify(serviceInstanceRepository, never()).save(any());

        ArgumentCaptor<List<BucketInstance>> buckets = ArgumentCaptor.forClass(List.class);
        verify(bucketInstanceRepository).saveAll(buckets.capture());
        assertEquals(2, buckets.getValue().size());
        assertTrue(buckets.getValue().stream().allMatch(b -> Long.valueOf(500L).equals(b.getServiceId())));
        verify(bucketInstanceRepository, never()).updateCurrentBalance(any(), any(), any());

        InOrder order = inOrder(serviceInstanceRepository, bucketInstanceRepository, userCacheService);
        order.verify(serviceInstanceRepository).saveAndFlush(any(ServiceInstance.class));
        order.verify(bucketInstanceRepository).saveAll(any());
        order.verify(userCacheService).updateUserAndRelatedCaches(eq(username), any(), eq(username));
    }

    @Test
    void processServiceInstanceInTransaction_UpdatesTrimmedCarryForwardBalance() {
        PlanToBucket p2b = new PlanToBucket();
        p2b.setBucketId("b1");
        p2b.setCarryForward(true);
        p2b.setInitialQuota(1000L);
        p2b.setMaxCarryForward(500L);
        p2b.setTotalCarryForward(600L);
        p2b.setCarryForwardValidity(30);

        BucketInstance current = new BucketInstance();
        current.setId(70L);
        current.setBucketId("b1");
        current.setBucketType("DATA");
        current.setCurrentBalance(300L);
        current.setExpiration(LocalDateTime.now().plusDays(1));

        BucketInstance existingCarryForward = new BucketInstance();
        existingCarryForward.setId(77L);
        existingCarryForward.setBucketId("b1");
        existingCarryForward.setBucketType(Constants.CARRY_FORWARD_BUCKET);
        existingCarryForward.setCurrentBalance(400L);
        existingCarryForward.setExpiration(LocalDateTime.now().plusDays(5));

        when(serviceInstanceRepository.saveAndFlush(any(ServiceInstance.class)))
                .thenReturn(ServiceInstance.builder().id(501L).build());

        recurrentServiceService.processServiceInstanceInTransaction(
                serviceInstance, user, plan, List.of(current, existingCarryForward),
                List.of(p2b), Map.of("b1", bucket), Map.of(10L, qosProfile));

        // New carry-forward of 300 plus the existing 400 exceeds the 600 total, so the existing one drops to 300
        verify(bucketInstanceRepository).updateCurrentBalance(eq(77L), eq(300L), any(LocalDateTime.class));
    }

    @Test
    void processServiceInstanceInTransaction_DbFailureSkipsCacheUpdate() {
        planToBucket.setTotalCarryForward(1000L);
        BucketInstance current = new BucketInstance();
        current.setId(70L);
        current.setBucketId("b1");
        current.setBucketType("DATA");
        current.setCurrentBalance(50L);
        current.setExpiration(LocalDateTime.now().plusDays(1));

        when(serviceInstanceRepository.saveAndFlush(any(ServiceInstance.class)))
                .thenReturn(ServiceInstance.builder().id(502L).build());
        when(bucketInstanceRepository.saveAll(any())).thenThrow(new IllegalStateException("insert failed"));

        List<BucketInstance> bucketInstances = List.of(current);
        List<PlanToBucket> quotaDetails = List.of(planToBucket);
        Map<String, Bucket> bucketMap = Map.of("b1", bucket);
        Map<Long, QOSProfile> qosProfileMap = Map.of(10L, qosProfile);
        assertThrows(IllegalStateException.class, () ->
                recurrentServiceService.processServiceInstanceInTransaction(
                        serviceInstance, user, plan, bucketInstances, quotaDetails, bucketMap, qosProfileMap));

        verify(userCacheService, never()).getUserData(any());
        verify(userCacheService, never()).updateUserAndRelatedCaches(any(), any(), any());
    }

    @Test
    void reactivateExpiredRecurrentServices_FailureRecordingErrorDoesNotAbortRun() {
        when(serviceInstanceRepository.findByRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(serviceInstance)));
        // No user for the service, so a failure is recorded
        when(userRepository.findByUserNameIn(any())).thenReturn(Collections.emptyList());
        doThrow(new IllegalStateException("commit failed"))
                .when(recurrentServiceService)
                .saveServiceProcessingFailure(any(), any(), any(), any(), any());

        assertDoesNotThrow(() -> recurrentServiceService.reactivateExpiredRecurrentServices());
    }
    @Test
    void calculateValidityDays_Scenarios() {
        // Daily
        int daily = ReflectionTestUtils.invokeMethod(recurrentServiceService, "calculateValidityDays", "DAILY", "1", LocalDateTime.now());
        assertEquals(1, daily);

        // Weekly
        int weekly = ReflectionTestUtils.invokeMethod(recurrentServiceService, "calculateValidityDays", "WEEKLY", "1", LocalDateTime.now());
        assertEquals(7, weekly);

        // Monthly (Calendar length)
        LocalDateTime febDate = LocalDateTime.of(2024, 2, 1, 0, 0); // Leap year
        int monthly = ReflectionTestUtils.invokeMethod(recurrentServiceService, "calculateValidityDays", "MONTHLY", "1", febDate);
        assertEquals(29, monthly);
    }

    @Test
    void validateServiceData_Failure() {
        // Test missing user
        boolean result = ReflectionTestUtils.invokeMethod(recurrentServiceService, "validateServiceData", serviceInstance, null, plan, "batch1");
        assertFalse(result);
        verify(serviceProcessingFailureRepository).save(any());

        // Test missing plan
        boolean resultPlan = ReflectionTestUtils.invokeMethod(recurrentServiceService, "validateServiceData", serviceInstance, user, null, "batch1");
        assertFalse(resultPlan);
    }

    @Test
    @SuppressWarnings("java:S5778")
    void provisionQuotaOptimized_ThrowsExceptionOnEmptyQuota() {
        assertThrows(AAAException.class, () -> {
            recurrentServiceService.processServiceInstanceInTransaction(
                    serviceInstance, user, plan, Collections.emptyList(), null, Map.of(), Map.of());
        });
    }

    @Test
    void saveServiceProcessingFailure_TruncatesLargeStrings() {
        String largeMsg = "A".repeat(5000);
        Exception ex = new RuntimeException(largeMsg);

        recurrentServiceService.saveServiceProcessingFailure(serviceInstance, plan, "user", ex, "batch1");

        ArgumentCaptor<ServiceProcessingFailure> captor = ArgumentCaptor.forClass(ServiceProcessingFailure.class);
        verify(serviceProcessingFailureRepository).save(captor.capture());
        assertTrue(captor.getValue().getErrorMessage().length() <= 4000);
    }

    @Test
    void updateUserCacheWithBuckets_HandlesNullSession() {
        when(userCacheService.getUserData(anyString())).thenReturn(null);

        // Should log warning and return gracefully (not throw exception)
        ReflectionTestUtils.invokeMethod(recurrentServiceService, "updateUserCacheWithBuckets",
                "testUser", List.of(new BucketInstance()), serviceInstance);

        verify(userCacheService, never()).updateUserAndRelatedCaches(any(), any(), any());
    }

    @Test
    void adjustExistingCFBuckets_ExceedingLimit() {
        BucketInstance existing = new BucketInstance();
        existing.setCurrentBalance(100L);
        List<BucketInstance> updates = new ArrayList<>();

        // Total CF = 150, Limit = 100. Should reduce existing by 50.
        ReflectionTestUtils.invokeMethod(recurrentServiceService, "adjustExistingCFBuckets",
                List.of(existing), 150L, 100L, updates);

        assertEquals(50L, existing.getCurrentBalance());
        assertEquals(1, updates.size());
    }

    @Test
    void updateCycleManagementProperties_ShouldThrowAAAException_OnFailure() {
        ServiceInstance instance = new ServiceInstance(); // Null dates will cause NPE in logic

        user.setUserName("testUser");

        assertThrows(AAAException.class, () ->
                ReflectionTestUtils.invokeMethod(recurrentServiceService, "updateCycleManagementProperties", instance, plan, user)
        );
    }

}