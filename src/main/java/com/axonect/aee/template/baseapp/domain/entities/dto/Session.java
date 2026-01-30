package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.*;

/**
 * Session information for user
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Session {
    private String sessionId;
    private String sessionStartTime;
    private String sessionEndTime;
    private String status;
}
