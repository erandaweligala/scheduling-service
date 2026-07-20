package com.axonect.aee.template.baseapp.domain.entities.dto.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Payload wrapper grouping the sections of an accounting CDR event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CdrPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private SessionCdr session;
    private UserCdr user;
    private NetworkCdr network;
    private AccountingCdr accounting;
}
