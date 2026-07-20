package com.axonect.aee.template.baseapp.domain.entities.dto.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * User section of an accounting CDR event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCdr implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userName;
    private String groupId;
}
