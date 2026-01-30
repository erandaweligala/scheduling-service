package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * - NEXT_CYCLE_START_DATE on the specified date (compares date only, ignores time)
     * - EXPIRY_DATE is after the specified date (not expired)
     * Supports pagination for batch processing.
     */
    @Query("SELECT s FROM ServiceInstance s WHERE s.recurringFlag = true " +
            "AND s.nextCycleStartDate >= :dayStart " +
            "AND s.nextCycleStartDate < :dayEnd " +
            "AND s.expiryDate > :expiryDate")
    Page<ServiceInstance> findByRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd,
            @Param("expiryDate") LocalDateTime expiryDate,
            Pageable pageable);
}
