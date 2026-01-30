package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ConsumptionRecord {
    private LocalDate date;
    private Long bytesConsumed;
    private Integer requestCount;

    /**
     * Constructor for backward compatibility (without requestCount).
     */
    public ConsumptionRecord(LocalDate date, Long bytesConsumed) {
        this.date = date;
        this.bytesConsumed = bytesConsumed;
        this.requestCount = 1;
    }

    /**
     * Add consumption to this daily record.
     */
    public void addConsumption(Long bytes) {
        this.bytesConsumed += bytes;
        this.requestCount++;
    }
}
