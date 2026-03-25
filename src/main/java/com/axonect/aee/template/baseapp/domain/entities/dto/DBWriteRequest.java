package com.axonect.aee.template.baseapp.domain.entities.dto;

import com.axonect.aee.template.baseapp.domain.enums.EventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DBWriteRequest {
    private String eventId;
    private EventType eventType;
    private LocalDateTime timestamp;
    private String userName;
    private String sessionId;
    private Map<String, Object> columnValues;
    private Map<String, Object> whereConditions;
    private String tableName;
}
