package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class Balance {
    private Long initialBalance;
    private Long quota;
    private LocalDateTime serviceExpiry;
    private LocalDateTime bucketExpiryDate;
    private String bucketId;
    private String serviceId;
    private Long priority;
    private LocalDateTime serviceStartDate;
    private String serviceStatus;
    private String timeWindow;
    private Long consumptionLimit;
    private Long consumptionLimitWindow;
    private String bucketUsername;
    private boolean isUnlimited;
    private List<ConsumptionRecord> consumptionHistory = new ArrayList<>();
    private boolean isGroup;
    private long usage;

}
