package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.*;

/**
 * Balance information for user session
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Balance {
    private String balanceId;
    private Long amount;
    private String currency;
    private String type;
}
