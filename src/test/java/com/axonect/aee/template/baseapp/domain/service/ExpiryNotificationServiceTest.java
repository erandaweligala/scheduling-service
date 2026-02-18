package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ChildTemplateTableRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketExpiryNotification;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpiryNotificationServiceTest {

    @Mock private ChildTemplateTableRepository childTemplateTableRepository;
    @Mock private BucketInstanceRepository bucketInstanceRepository;
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
        verifyNoInteractions(bucketInstanceRepository);
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
        verifyNoInteractions(bucketInstanceRepository);
    }

    @Test
    @DisplayName("Process - Happy path with dynamic message replacement")
    void processExpiryNotifications_Success() {
        // 1. Setup Template
        ChildTemplateTable template = new ChildTemplateTable();
        template.setId(1L);
        template.setDaysToExpire(2);
        template.setMessageContent("Plan {PLAN_NAME} expires on {DATE_OF_EXPIRY} in {DAYS_TO_EXPIRE} days.");
        template.setMessageType("SMS");
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        // 2. Setup Bucket
        BucketInstance bucket = new BucketInstance();
        bucket.setId(101L);
        bucket.setServiceId(201L);
        bucket.setExpiration(LocalDateTime.now().plusDays(2));
        bucket.setCurrentBalance(50L);
        bucket.setInitialBalance(100L);

        when(bucketInstanceRepository.findBucketsExpiringBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(bucket))) // First page
                .thenReturn(new PageImpl<>(Collections.emptyList())); // End of pagination

        // 3. Setup Service Instance
        ServiceInstance service = new ServiceInstance();
        service.setId(201L);
        service.setUsername("user_01");
        service.setPlanName("Premium_Plan");
        when(serviceInstanceRepository.findById(201L)).thenReturn(Optional.of(service));

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
    }

    @Test
    @DisplayName("Process - Service Instance missing")
    void processExpiryNotifications_ServiceNotFound() {
        ChildTemplateTable template = new ChildTemplateTable();
        template.setDaysToExpire(1);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        BucketInstance bucket = new BucketInstance();
        bucket.setServiceId(999L);
        when(bucketInstanceRepository.findBucketsExpiringBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(bucket)));

        when(serviceInstanceRepository.findById(999L)).thenReturn(Optional.empty());

        int result = expiryNotificationService.processExpiryNotifications();

        assertEquals(1, result); // No notification sent because service was missing
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Process - Kafka failure should catch exception and continue")
    void processExpiryNotifications_KafkaError() {
        ChildTemplateTable template = new ChildTemplateTable();
        template.setDaysToExpire(1);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        BucketInstance bucket = new BucketInstance();
        bucket.setServiceId(201L);
        when(bucketInstanceRepository.findBucketsExpiringBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(bucket)));

        ServiceInstance service = new ServiceInstance();
        service.setUsername("user_error");
        when(serviceInstanceRepository.findById(anyLong())).thenReturn(Optional.of(service));

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
        // Using reflection or a wrapper if private, but here we can trigger it
        // by making a bucket with a template that has null content.
        ChildTemplateTable template = new ChildTemplateTable();
        template.setDaysToExpire(1);
        template.setMessageContent(null);
        when(childTemplateTableRepository.findAllExpireTemplates()).thenReturn(List.of(template));

        BucketInstance bucket = new BucketInstance();
        bucket.setServiceId(201L);
        bucket.setExpiration(LocalDateTime.now());
        when(bucketInstanceRepository.findBucketsExpiringBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(bucket)));

        ServiceInstance service = new ServiceInstance();
        service.setUsername("user");
        when(serviceInstanceRepository.findById(anyLong())).thenReturn(Optional.of(service));

        expiryNotificationService.processExpiryNotifications();

        ArgumentCaptor<BucketExpiryNotification> captor = ArgumentCaptor.forClass(BucketExpiryNotification.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());

        assertEquals("Your plan will expire soon. Please renew to continue services.", captor.getValue().getMessage());
    }
}