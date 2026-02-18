package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.entities.repo.*;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;

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
        bi.setServiceId(1L);
        bi.setBucketId("b1");
        when(bucketInstanceRepository.findByServiceIdIn(any())).thenReturn(List.of(bi));

        when(bucketRepository.findByBucketIdIn(any())).thenReturn(List.of(bucket));
        when(qosProfileRepository.findByIdIn(any())).thenReturn(List.of(qosProfile));

        // Run service
        recurrentServiceService.reactivateExpiredRecurrentServices();

        verify(serviceInstanceRepository, atLeastOnce()).save(any());
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

        // 3. Execution
        assertDoesNotThrow(() -> {
            recurrentServiceService.processServiceInstanceInTransaction(
                    serviceInstance, user, plan, bucketInstanceList,
                    quotaDetails, bucketMap, qosProfileMap
            );
        });

        // 4. Verifications
        verify(serviceInstanceRepository, times(1)).save(any(ServiceInstance.class));
        verify(bucketInstanceRepository, atLeastOnce()).saveAll(any());
        verify(userCacheService, times(1)).updateUserAndRelatedCaches(eq(username), any(), eq(username));
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