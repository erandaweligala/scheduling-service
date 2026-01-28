package com.axonect.aee.template.baseapp.domain.entities.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheBucketRequest {

    @NotNull(message = "Initial balance is required")
    private Long initialBalance;

    @NotNull(message = "Quota is required")
    private Long quota;

    @NotBlank(message = "Service expiry is required")
    private String serviceExpiry;

    @NotBlank(message = "Bucket ID is required")
    private String bucketId;

    @NotBlank(message = "Service ID is required")
    private Long serviceId;

    @NotNull(message = "Priority is required")
    private Integer priority;

    @NotBlank(message = "Service start date is required")
    private String serviceStartDate;

    @NotBlank(message = "Service status is required")
    private String serviceStatus;

    @NotBlank(message = "Time window is required")
    private String timeWindow;

    @NotNull(message = "Consumption limit is required")
    private Long consumptionLimit;

    @NotNull(message = "Consumption limit window is required")
    private Integer consumptionLimitWindow;

    @NotBlank(message = "Bucket username is required")
    private String bucketUsername;

    @JsonProperty("group")
    @NotNull(message = "Group flag is required")
    private Boolean group;
}
