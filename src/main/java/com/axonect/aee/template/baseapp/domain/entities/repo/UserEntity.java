package com.axonect.aee.template.baseapp.domain.entities.repo;

import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "AAA_USER")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {

    @Id
    @Column(name = "USER_ID", nullable = false, unique = true, length = 50)
    private String userId;

    @Column(name = "USER_NAME", nullable = false, unique = true)
    private String userName;

    @Column(name = "PASSWORD")
    private String password;

    @Column(name = "ENCRYPTION_METHOD")
    private Integer encryptionMethod;
    /*
        0 – Plain text
        1 – MD5
        2 – CSG/ADL proprietary
        Mandatory only if password is provided
     */

    @Column(name = "NAS_PORT_TYPE", nullable = false)
    private String nasPortType;

    @Column(name = "GROUP_ID")
    private String groupId;

    @Column(name = "BANDWIDTH")
    private String bandwidth;

    @Column(name = "VLAN_ID")
    private String vlanId;

    @Column(name = "CIRCUIT_ID")
    private String circuitId;

    @Column(name = "REMOTE_ID")
    private String remoteId;

    @Column(name = "MAC_ADDRESS")
    private String macAddress;

    @Column(name = "IP_ALLOCATION")
    private String ipAllocation;

    @Column(name = "IP_POOL_NAME")
    private String ipPoolName;

    @Column(name = "IPV4")
    private String ipv4;

    @Column(name = "IPV6")
    private String ipv6;

    @Column(name = "BILLING")
    private String billing;

    @Column(name = "CYCLE_DATE")
    private Integer cycleDate;

    @Column(name = "CONTACT_NAME")
    private String contactName;

    @Column(name = "CONTACT_EMAIL")
    private String contactEmail;

    @Column(name = "CONTACT_NUMBER")
    private String contactNumber;

    @Column(name = "CONCURRENCY")
    private Integer concurrency;

    @Column(name = "BILLING_ACCOUNT_REF")
    private String billingAccountRef;

    @Column(name = "SESSION_TIMEOUT")
    private String sessionTimeout;

    @Column(name = "IDLE_TIMEOUT")
    private String idleTimeout;

    @Column(name = "CUSTOM_TIMEOUT")
    private String customTimeout;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false)
    private UserStatus status;
    // Change from Integer to UserStatus
    /*
        1 – Active
        2 – Suspended
        3 – Inactive
     */

    @Column(name = "REQUEST_ID", nullable = false, unique = true)
    private String requestId;

    @Column(name = "CREATED_DATE", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdDate;

    @Column(name = "UPDATED_DATE")
    private LocalDateTime updatedDate;

}
