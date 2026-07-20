package com.axonect.aee.template.baseapp.domain.entities.dto.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Top-level accounting CDR (Call Detail Record) event published to Kafka.
 * Generated, for example, when a session is torn down on idle timeout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingCdrEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String eventId;
    private String eventType;
    private String eventVersion;
    private Instant eventTimestamp;
    private String source;
    private String partitionKey;
    private CdrPayload payload;
}
