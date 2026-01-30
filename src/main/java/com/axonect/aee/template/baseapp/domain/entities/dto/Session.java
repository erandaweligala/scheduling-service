package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Session {
    private String sessionId;
    private LocalDateTime sessionInitiatedTime;
    private LocalDateTime sessionStartTime;
    private String previousUsageBucketId;
    private Integer sessionTime;
    private Long previousTotalUsageQuotaValue;
    private String framedId;
    private String nasIp;
    private String nasPortId;
    private boolean isNewSession;
    private long availableBalance;
    private String groupId;
    private String userName;
    private String serviceId;
    private String absoluteTimeOut;
    private String userStatus;
    private long userConcurrency;

}
