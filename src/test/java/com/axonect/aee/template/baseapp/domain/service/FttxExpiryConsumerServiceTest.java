package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.BucketExpiryNotification;
import com.axonect.aee.template.baseapp.domain.entities.dto.FttxExpiryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FttxExpiryConsumerServiceTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private FttxExpiryConsumerService fttxExpiryConsumerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fttxExpiryConsumerService, "bucketExpiryTopic", "bucket-expiry-notifications");
    }

    // -------------------------------------------------------------------------
    // mapToNotification tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mapToNotification - maps all fields correctly from a valid FTTX EXPIRY event")
    void mapToNotification_MapsAllFieldsCorrectly() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000001",
                "100 MBPS",
                "2026-02-20T23:59:59",
                "2026-02-19T13:35:03.999Z",
                "Quota has reached the configured threshold"
        );

        BucketExpiryNotification notification = fttxExpiryConsumerService.mapToNotification(event);

        // username ← data.serviceLineNumber
        assertEquals("5100000001", notification.getUsername());

        // planName ← data.planDescription
        assertEquals("100 MBPS", notification.getPlanName());

        // message ← data.message
        assertEquals("Quota has reached the configured threshold", notification.getMessage());

        // messageType ← meta.eventType
        assertEquals("EXPIRY", notification.getMessageType());

        // userId and serviceId are not provided by the event
        assertNull(notification.getUserId());
        assertNull(notification.getServiceId());

        // templateId is not provided by the event
        assertNull(notification.getTemplateId());

        // dateOfExpiry ← data.expiryDate
        assertEquals(LocalDateTime.of(2026, 2, 20, 23, 59, 59), notification.getDateOfExpiry());

        // notificationTime ← meta.messageTimeStamp (offset stripped)
        assertEquals(LocalDateTime.of(2026, 2, 19, 13, 35, 3, 999_000_000), notification.getNotificationTime());

        // daysToExpire = days between 2026-02-19 and 2026-02-20 = 1
        assertEquals(1, notification.getDaysToExpire());
    }

    @Test
    @DisplayName("mapToNotification - daysToExpire is 0 when expiryDate equals notificationTime date")
    void mapToNotification_DaysToExpire_Zero_WhenSameDay() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000001",
                "50 MBPS",
                "2026-02-19T23:59:59",
                "2026-02-19T08:00:00Z",
                "Expiring today"
        );

        BucketExpiryNotification notification = fttxExpiryConsumerService.mapToNotification(event);

        assertEquals(0, notification.getDaysToExpire());
    }

    @Test
    @DisplayName("mapToNotification - daysToExpire is 0 when expiryDate is before notificationTime date")
    void mapToNotification_DaysToExpire_Zero_WhenExpired() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000001",
                "50 MBPS",
                "2026-02-18T23:59:59",   // expiry already passed
                "2026-02-19T08:00:00Z",
                "Already expired"
        );

        BucketExpiryNotification notification = fttxExpiryConsumerService.mapToNotification(event);

        assertEquals(0, notification.getDaysToExpire());
    }

    @Test
    @DisplayName("mapToNotification - handles null expiryDate with fallback to current time")
    void mapToNotification_NullExpiryDate_UsesFallback() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000002",
                "200 MBPS",
                null,
                "2026-02-19T13:35:03.999Z",
                "Test message"
        );

        BucketExpiryNotification notification = fttxExpiryConsumerService.mapToNotification(event);

        assertNotNull(notification.getDateOfExpiry());
    }

    @Test
    @DisplayName("mapToNotification - handles null messageTimeStamp with fallback to current time")
    void mapToNotification_NullMessageTimeStamp_UsesFallback() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000003",
                "100 MBPS",
                "2026-02-20T23:59:59",
                null,
                "Test message"
        );

        BucketExpiryNotification notification = fttxExpiryConsumerService.mapToNotification(event);

        assertNotNull(notification.getNotificationTime());
    }

    // -------------------------------------------------------------------------
    // handleFttxExpiryEvent tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleFttxExpiryEvent - publishes notification for valid FTTX EXPIRY event")
    void handleFttxExpiryEvent_ValidEvent_PublishesNotification() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000001",
                "100 MBPS",
                "2026-02-20T23:59:59",
                "2026-02-19T13:35:03.999Z",
                "Quota has reached the configured threshold"
        );

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        fttxExpiryConsumerService.handleFttxExpiryEvent(event);

        ArgumentCaptor<BucketExpiryNotification> captor = ArgumentCaptor.forClass(BucketExpiryNotification.class);
        verify(kafkaTemplate).send(eq("bucket-expiry-notifications"), eq("5100000001"), captor.capture());

        BucketExpiryNotification sent = captor.getValue();
        assertEquals("5100000001", sent.getUsername());
        assertEquals("100 MBPS", sent.getPlanName());
        assertEquals("EXPIRY", sent.getMessageType());
        assertEquals(1, sent.getDaysToExpire());
    }

    @Test
    @DisplayName("handleFttxExpiryEvent - skips event when eventType is not EXPIRY")
    void handleFttxExpiryEvent_WrongEventType_Skipped() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000001", "100 MBPS", "2026-02-20T23:59:59", "2026-02-19T13:35:03.999Z", "msg"
        );
        event.getMeta().setEventType("QUOTA");

        fttxExpiryConsumerService.handleFttxExpiryEvent(event);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("handleFttxExpiryEvent - skips event when productType is not FTTX")
    void handleFttxExpiryEvent_WrongProductType_Skipped() {
        FttxExpiryEvent event = buildValidEvent(
                "5100000001", "100 MBPS", "2026-02-20T23:59:59", "2026-02-19T13:35:03.999Z", "msg"
        );
        event.getMeta().setProductType("MOBILE");

        fttxExpiryConsumerService.handleFttxExpiryEvent(event);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("handleFttxExpiryEvent - skips null event gracefully")
    void handleFttxExpiryEvent_NullEvent_Skipped() {
        fttxExpiryConsumerService.handleFttxExpiryEvent(null);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("handleFttxExpiryEvent - skips event with null meta")
    void handleFttxExpiryEvent_NullMeta_Skipped() {
        FttxExpiryEvent event = new FttxExpiryEvent();
        event.setMeta(null);
        event.setData(new FttxExpiryEvent.EventData());

        fttxExpiryConsumerService.handleFttxExpiryEvent(event);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("handleFttxExpiryEvent - skips event with null data")
    void handleFttxExpiryEvent_NullData_Skipped() {
        FttxExpiryEvent event = new FttxExpiryEvent();
        event.setMeta(buildMeta("EXPIRY", "FTTX", "2026-02-19T13:35:03.999Z", "evt-001"));
        event.setData(null);

        fttxExpiryConsumerService.handleFttxExpiryEvent(event);

        verifyNoInteractions(kafkaTemplate);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FttxExpiryEvent buildValidEvent(
            String serviceLineNumber,
            String planDescription,
            String expiryDate,
            String messageTimeStamp,
            String message) {

        FttxExpiryEvent.EventMeta meta = buildMeta("EXPIRY", "FTTX", messageTimeStamp, "234567890-23456789");
        FttxExpiryEvent.EventData data = FttxExpiryEvent.EventData.builder()
                .emailId("test@gmail.com")
                .contactNumber("987654322")
                .message(message)
                .serviceLineNumber(serviceLineNumber)
                .planDescription(planDescription)
                .planCode("CLOID")
                .expiryDate(expiryDate)
                .build();

        return FttxExpiryEvent.builder()
                .meta(meta)
                .data(data)
                .build();
    }

    private FttxExpiryEvent.EventMeta buildMeta(
            String eventType,
            String productType,
            String messageTimeStamp,
            String eventId) {

        return FttxExpiryEvent.EventMeta.builder()
                .eventId(eventId)
                .source("AAA/SingleView")
                .eventType(eventType)
                .messageTimeStamp(messageTimeStamp)
                .productType(productType)
                .build();
    }
}
