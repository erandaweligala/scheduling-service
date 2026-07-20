package com.axonect.aee.template.baseapp.domain.entities.dto.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Session section of an accounting CDR event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCdr implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String sessionTime;
    private Instant startTime;
    private Instant updateTime;
    private Instant sessionStopTime;
    private String nasIdentifier;
    private String nasIpAddress;
    private String nasPort;
    private String nasPortType;
}
