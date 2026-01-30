package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance,Long> {
//    List<ServiceInstance> findByUsernameAndRecurringFlagTrue(String username);
    List<ServiceInstance> findByUsernameAndRecurringFlagTrueAndNextCycleStartDate(String username, LocalDate expiryDate);

    List<ServiceInstance> findByUsernameInAndRecurringFlagTrueAndNextCycleStartDate(
            List<String> usernames, LocalDateTime nextCycleStartDate);

    /**
     * Find services for batch processing that match the following criteria:
     * - RECURRING_FLAG = true
     * - NEXT_CYCLE_START_DATE = specified date (tomorrow)
     * - EXPIRY_DATE is after the specified date (not expired)
     * Supports pagination for batch processing.
     */
    Page<ServiceInstance> findByRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
            LocalDateTime nextCycleStartDate, LocalDateTime expiryDate, Pageable pageable);
}
