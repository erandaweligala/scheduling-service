package com.axonect.aee.template.baseapp.domain.entities.dto.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Accounting section of an accounting CDR event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingCdr implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String acctStatusType;
    private Integer acctSessionTime;
    private long totalUsage;
    private long sessionUsage;
    private String serviceId;
    private String bucketId;

    /** Cause of the session teardown, e.g. {@code Idle-Timeout}. */
    private String terminateCause;
}
