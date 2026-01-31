package com.axonect.aee.template.baseapp.domain.entities.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Kafka event payload for bucket expiry notifications
 * Sent to notify users about upcoming bucket/plan expirations
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BucketExpiryNotification implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Username of the user to notify
     */
    @JsonProperty("username")
    private String username;

    /**
     * User ID
     */
    @JsonProperty("user_id")
    private Long userId;

    /**
     * Service instance ID
     */
    @JsonProperty("service_id")
    private Long serviceId;

    /**
     * Bucket instance ID
     */
    @JsonProperty("bucket_instance_id")
    private Long bucketInstanceId;

    /**
     * Bucket ID
     */
    @JsonProperty("bucket_id")
    private String bucketId;

    /**
     * Plan name (dynamic field in message)
     */
    @JsonProperty("plan_name")
    private String planName;

    /**
     * Date when the bucket/plan expires
     */
    @JsonProperty("date_of_expiry")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dateOfExpiry;

    /**
     * Number of days until expiry
     */
    @JsonProperty("days_to_expire")
    private Integer daysToExpire;

    /**
     * The notification message content with placeholders replaced
     * Example: "Your plan Premium Plan will expire in 2 days on 2026-01-31. Please renew to continue services."
     */
    @JsonProperty("message")
    private String message;

    /**
     * Message type (e.g., "EXPIRE")
     */
    @JsonProperty("message_type")
    private String messageType;

    /**
     * Template ID used for this notification
     */
    @JsonProperty("template_id")
    private Long templateId;

    /**
     * Timestamp when notification was generated
     */
    @JsonProperty("notification_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime notificationTime;

    /**
     * Current balance remaining in bucket
     */
    @JsonProperty("current_balance")
    private Long currentBalance;

    /**
     * Initial balance of bucket
     */
    @JsonProperty("initial_balance")
    private Long initialBalance;
}
