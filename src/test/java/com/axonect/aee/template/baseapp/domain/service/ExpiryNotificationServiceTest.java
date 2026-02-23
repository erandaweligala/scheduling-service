package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.ChildTemplateTableRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketExpiryNotification;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.entities.repo.ChildTemplateTable;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.exception.NotificationProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpiryNotificationServiceTest {

    @Mock private ChildTemplateTableRepository childTemplateTableRepository;
    @Mock private ServiceInstanceRepository serviceInstanceRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private UserCacheService userCacheService;

    @InjectMocks
    private ExpiryNotificationService expiryNotificationService;

    @BeforeEach
    void setUp() {
        // Set @Value fields manually
        ReflectionTestUtils.setField(expiryNotificationService, "bucketExpiryTopic", "test-topic");
        ReflectionTestUtils.setField(expiryNotificationService, "batchSize", 100);
    }

    @Test
    @DisplayName("Process - No templates found")
    void processExpiryNotifications_NoTemplates() {
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(Collections.emptyList());

        int result = expiryNotificationService.processExpiryNotifications();

        assertEquals(0, result);
        verifyNoInteractions(serviceInstanceRepository);
    }

    @Test
    @DisplayName("Process - Template with null days to expire")
    void processExpiryNotifications_NullDaysToExpire() {
        ChildTemplateTable template = new ChildTemplateTable();
        template.setId(1L);
        template.setDaysToExpire(null);

        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        int result = expiryNotificationService.processExpiryNotifications();

        assertEquals(0, result);
        verifyNoInteractions(serviceInstanceRepository);
    }

    @Test
    @DisplayName("Process - Happy path with dynamic message replacement and superTemplateId matching")
    void processExpiryNotifications_Success() {
        // 1. Setup Template with superTemplateId
        ChildTemplateTable template = new ChildTemplateTable();
        template.setId(1L);
        template.setDaysToExpire(2);
        template.setMessageContent("Plan {PLAN_NAME} expires on {DATE_OF_EXPIRY} in {DAYS_TO_EXPIRE} days.");
        template.setMessageType("SMS");
        template.setSuperTemplateId(5L);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        // 2. Setup Service Instance with CYCLE_END_DATE
        ServiceInstance service = new ServiceInstance();
        service.setId(201L);
        service.setUsername("user_01");
        service.setPlanName("Premium_Plan");
        service.setServiceCycleEndDate(LocalDateTime.now().plusDays(2));

        when(serviceInstanceRepository.findByServiceCycleEndDateBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(service))) // First page
                .thenReturn(new PageImpl<>(Collections.emptyList())); // End of pagination

        // 3. Setup UserSessionData with matching superTemplateId
        UserSessionData userSessionData = new UserSessionData();
        userSessionData.setSuperTemplateId(5L);
        when(userCacheService.getUserData("user_01")).thenReturn(userSessionData);

        // 4. Mock Kafka Success
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Execute
        int result = expiryNotificationService.processExpiryNotifications();

        // Verify
        assertEquals(1, result);

        // Capture Notification to verify message replacement
        ArgumentCaptor<BucketExpiryNotification> captor = ArgumentCaptor.forClass(BucketExpiryNotification.class);
        verify(kafkaTemplate).send(eq("test-topic"), eq("user_01"), captor.capture());

        String sentMessage = captor.getValue().getMessage();
        assertTrue(sentMessage.contains("Premium_Plan"));
        assertTrue(sentMessage.contains("2 days"));
        assertEquals(1L, captor.getValue().getTemplateId());
    }

    @Test
    @DisplayName("Process - Notification skipped when superTemplateId does not match")
    void processExpiryNotifications_SuperTemplateIdMismatch() {
        // 1. Setup Template with superTemplateId = 5
        ChildTemplateTable template = new ChildTemplateTable();
        template.setId(1L);
        template.setDaysToExpire(2);
        template.setMessageContent("Plan {PLAN_NAME} expires.");
        template.setMessageType("SMS");
        template.setSuperTemplateId(5L);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        // 2. Setup Service Instance with CYCLE_END_DATE
        ServiceInstance service = new ServiceInstance();
        service.setId(201L);
        service.setUsername("user_01");
        service.setPlanName("Basic_Plan");
        service.setServiceCycleEndDate(LocalDateTime.now().plusDays(2));

        when(serviceInstanceRepository.findByServiceCycleEndDateBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(service)));

        // 3. Setup UserSessionData with DIFFERENT superTemplateId (3 != 5)
        UserSessionData userSessionData = new UserSessionData();
        userSessionData.setSuperTemplateId(3L);
        when(userCacheService.getUserData("user_01")).thenReturn(userSessionData);

        // Execute
        int result = expiryNotificationService.processExpiryNotifications();

        // Notification count still increments (sendNotificationForService returns without exception)
        assertEquals(1, result);

        // But Kafka should NOT be called since superTemplateId didn't match
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Process - Notification skipped when user session data not in cache")
    void processExpiryNotifications_UserSessionDataNotFound() {
        // 1. Setup Template
        ChildTemplateTable template = new ChildTemplateTable();
        template.setId(1L);
        template.setDaysToExpire(1);
        template.setSuperTemplateId(5L);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        // 2. Setup Service Instance with CYCLE_END_DATE
        ServiceInstance service = new ServiceInstance();
        service.setId(201L);
        service.setUsername("user_missing_cache");
        service.setServiceCycleEndDate(LocalDateTime.now().plusDays(1));

        when(serviceInstanceRepository.findByServiceCycleEndDateBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(service)));

        // 3. User not found in cache
        when(userCacheService.getUserData("user_missing_cache")).thenReturn(null);

        // Execute
        int result = expiryNotificationService.processExpiryNotifications();

        // Counter increments but no Kafka sent
        assertEquals(1, result);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Process - No service instances found with matching CYCLE_END_DATE")
    void processExpiryNotifications_NoServicesFound() {
        ChildTemplateTable template = new ChildTemplateTable();
        template.setDaysToExpire(1);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        when(serviceInstanceRepository.findByServiceCycleEndDateBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        int result = expiryNotificationService.processExpiryNotifications();

        assertEquals(0, result);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Process - Kafka failure should catch exception and continue")
    void processExpiryNotifications_KafkaError() {
        ChildTemplateTable template = new ChildTemplateTable();
        template.setDaysToExpire(1);
        template.setSuperTemplateId(5L);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        ServiceInstance service = new ServiceInstance();
        service.setId(201L);
        service.setUsername("user_error");
        service.setServiceCycleEndDate(LocalDateTime.now().plusDays(1));

        when(serviceInstanceRepository.findByServiceCycleEndDateBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(service)));

        // Setup UserSessionData with matching superTemplateId
        UserSessionData userSessionData = new UserSessionData();
        userSessionData.setSuperTemplateId(5L);
        when(userCacheService.getUserData("user_error")).thenReturn(userSessionData);

        // Throw exception during Kafka send
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenThrow(new RuntimeException("Kafka Down"));

        int result = expiryNotificationService.processExpiryNotifications();

        assertEquals(0, result); // Counter doesn't increment on failure
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Process - Top-level repository exception")
    void processExpiryNotifications_GeneralException() {
        when(childTemplateTableRepository.findAllExpireTemplates())
                .thenThrow(new RuntimeException("DB Connection Failed"));

        assertThrows(NotificationProcessingException.class, () -> {
            expiryNotificationService.processExpiryNotifications();
        });
    }

    @Test
    @DisplayName("BuildMessage - Null template handles gracefully")
    void buildNotificationMessage_NullTemplate() {
        // Trigger with a template that has null content
        ChildTemplateTable template = new ChildTemplateTable();
        template.setDaysToExpire(1);
        template.setMessageContent(null);
        template.setSuperTemplateId(5L);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        ServiceInstance service = new ServiceInstance();
        service.setId(201L);
        service.setUsername("user");
        service.setServiceCycleEndDate(LocalDateTime.now().plusDays(1));

        when(serviceInstanceRepository.findByServiceCycleEndDateBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(service)));

        // Setup UserSessionData with matching superTemplateId
        UserSessionData userSessionData = new UserSessionData();
        userSessionData.setSuperTemplateId(5L);
        when(userCacheService.getUserData("user")).thenReturn(userSessionData);

        expiryNotificationService.processExpiryNotifications();

        ArgumentCaptor<BucketExpiryNotification> captor = ArgumentCaptor.forClass(BucketExpiryNotification.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());

        assertEquals("Your plan will expire soon. Please renew to continue services.", captor.getValue().getMessage());
    }
}
