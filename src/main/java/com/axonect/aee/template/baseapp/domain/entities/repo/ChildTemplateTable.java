package com.axonect.aee.template.baseapp.domain.entities.repo;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Entity representing child template configurations for notifications
 * Stores template configurations for different message types (EXPIRE, QUOTA, etc.)
 */
@Entity
@Table(name = "CHILD_TEMPLATE_TABLE", schema = "AAA")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChildTemplateTable implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false, precision = 19)
    private Long id;

    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt;

    /**
     * Number of days before expiry to trigger notification
     * Example: If set to 2, notification will be sent 2 days before bucket expires
     */
    @Column(name = "DAYS_TO_EXPIRE", precision = 10)
    private Integer daysToExpire;

    /**
     * Template message content with dynamic placeholders
     * Example: "Your plan {PLAN_NAME} will expire in {DAYS_TO_EXPIRE} days on {DATE_OF_EXPIRY}. Please renew to continue services."
     */
    @Lob
    @Column(name = "MESSAGE_CONTENT")
    private String messageContent;

    /**
     * Type of message: EXPIRE, QUOTA, etc.
     */
    @Column(name = "MESSAGE_TYPE", length = 20)
    private String messageType;

    /**
     * Quota percentage threshold for quota-based notifications (0-100)
     */
    @Column(name = "QUOTA_PERCENTAGE", precision = 10)
    private Integer quotaPercentage;

    /**
     * Reference to super/parent template if applicable
     */
    @Column(name = "SUPER_TEMPLATE_ID", precision = 19)
    private Long superTemplateId;

    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
