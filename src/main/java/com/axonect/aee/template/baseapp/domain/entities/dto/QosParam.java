package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.*;

/**
 * QoS parameters for user session
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QosParam {
    private String qosProfileId;
    private Integer bandwidthLimit;
    private Integer latencyThreshold;
    private String priority;
}
