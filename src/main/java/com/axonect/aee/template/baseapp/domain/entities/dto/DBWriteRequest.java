package com.axonect.aee.template.baseapp.domain.entities.dto;

import com.axonect.aee.template.baseapp.domain.enums.EventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for persisting balance state to the database
 * when a session is terminated by the idle session terminator.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DBWriteRequest {
    private String sessionId;
    private String bucketId;
    private String bucketUsername;
    private Long quota;
    private EventType eventType;
}
