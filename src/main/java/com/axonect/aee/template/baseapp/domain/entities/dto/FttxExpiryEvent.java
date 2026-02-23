package com.axonect.aee.template.baseapp.domain.entities.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Incoming FTTX expiry event payload from AAA/SingleView source.
 *
 * Example payload:
 * {
 *   "meta": {
 *     "eventId": "234567890-23456789",
 *     "source": "AAA/SingleView",
 *     "eventType": "EXPIRY",
 *     "messageTimeStamp": "2026-02-19T13:35:03.999Z",
 *     "productType": "FTTX"
 *   },
 *   "data": {
 *     "emailId": "test@gmail.com",
 *     "contactNumber": "987654322",
 *     "message": "Quota has reached the configured threshold",
 *     "serviceLineNumber": "5100000001",
 *     "planDescription": "100 MBPS",
 *     "planCode": "CLOID",
 *     "expiryDate": "2026-02-20T23:59:59"
 *   }
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FttxExpiryEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("meta")
    private EventMeta meta;

    @JsonProperty("data")
    private EventData data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventMeta implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Unique identifier for this event */
        @JsonProperty("eventId")
        private String eventId;

        /** Source system (e.g. "AAA/SingleView") */
        @JsonProperty("source")
        private String source;

        /** Type of event (e.g. "EXPIRY") */
        @JsonProperty("eventType")
        private String eventType;

        /** ISO-8601 timestamp when the event was generated (e.g. "2026-02-19T13:35:03.999Z") */
        @JsonProperty("messageTimeStamp")
        private String messageTimeStamp;

        /** Product type (e.g. "FTTX") */
        @JsonProperty("productType")
        private String productType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventData implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Customer email address */
        @JsonProperty("emailId")
        private String emailId;

        /** Customer contact number */
        @JsonProperty("contactNumber")
        private String contactNumber;

        /** Notification message from the source system */
        @JsonProperty("message")
        private String message;

        /** Service line number — used as the username for notification routing */
        @JsonProperty("serviceLineNumber")
        private String serviceLineNumber;

        /** Plan description — used as the plan name (e.g. "100 MBPS") */
        @JsonProperty("planDescription")
        private String planDescription;

        /** Plan code identifier */
        @JsonProperty("planCode")
        private String planCode;

        /** ISO-8601 expiry date-time (e.g. "2026-02-20T23:59:59") */
        @JsonProperty("expiryDate")
        private String expiryDate;
    }
}
