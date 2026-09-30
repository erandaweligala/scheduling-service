package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance,Long> {

    List<ServiceInstance> findByUsernameInAndRecurringFlagTrueAndNextCycleStartDate(
            List<String> usernames, LocalDateTime nextCycleStartDate);

    /**
     * Find services for batch processing that match the following criteria:
     * - RECURRING_FLAG = true
     * - NEXT_CYCLE_START_DATE on the specified date (compares date only, ignores time)
     * - EXPIRY_DATE is after the specified date (not expired)
     * Supports pagination for batch processing.
     *
     * OPTIMIZED FOR 5M+ RECORDS:
     * - Uses Oracle hint INDEX to force use of composite index
     * - FIRST_ROWS(100) hint optimizes for fast initial row retrieval
     * - Pagination-friendly for large datasets
     */
    @Query(value = "SELECT /*+ INDEX(s idx_service_recurring_next_expiry) FIRST_ROWS(100) */ " +
            "s.* FROM SERVICE_INSTANCE s WHERE s.RECURRING_FLAG = 1 " +
            "AND s.NEXT_CYCLE_START_DATE >= :dayStart " +
            "AND s.NEXT_CYCLE_START_DATE < :dayEnd " +
            "AND s.EXPIRY_DATE > :expiryDate",
            countQuery = "SELECT /*+ INDEX(s idx_service_recurring_next_expiry) */ " +
            "COUNT(*) FROM SERVICE_INSTANCE s WHERE s.RECURRING_FLAG = 1 " +
            "AND s.NEXT_CYCLE_START_DATE >= :dayStart " +
            "AND s.NEXT_CYCLE_START_DATE < :dayEnd " +
            "AND s.EXPIRY_DATE > :expiryDate",
            nativeQuery = true)
    Page<ServiceInstance> findByRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd,
            @Param("expiryDate") LocalDateTime expiryDate,
            Pageable pageable);

    Page<ServiceInstance> findByServiceCycleEndDateBetween(
            LocalDateTime startDateTime,
            LocalDateTime endDateTime,
            Pageable pageable);

    /**
     * Flip up to {@code limit} rows from {@code expectedStatus} to {@code newStatus} in a single
     * statement. Used by the activation scheduler: no rows are read back, so each chunk costs
     * exactly one round trip. Returns the number of rows updated; fewer than {@code limit}
     * means no matching rows remain.
     */
    @Modifying
    @Query(value = "UPDATE /*+ INDEX(s idx_service_status_recurring) */ SERVICE_INSTANCE s " +
            "SET s.STATUS = :newStatus, s.UPDATED_AT = :updatedAt " +
            "WHERE s.STATUS = :expectedStatus AND ROWNUM <= :limit",
            nativeQuery = true)
    int updateStatusLimited(@Param("expectedStatus") String expectedStatus,
                            @Param("newStatus") String newStatus,
                            @Param("updatedAt") LocalDateTime updatedAt,
                            @Param("limit") int limit);

    /**
     * IDs of service instances whose CYCLE_END_DATE OR EXPIRY_DATE falls within the given
     * inclusive-exclusive day window. Used by the cleanup scheduler; returning a List (not a
     * Page) avoids the COUNT query, and only the IDs are fetched.
     */
    @Query("SELECT s.id FROM ServiceInstance s " +
            "WHERE (s.serviceCycleEndDate >= :dayStart AND s.serviceCycleEndDate < :dayEnd) " +
            "   OR (s.expiryDate >= :dayStart AND s.expiryDate < :dayEnd)")
    List<Long> findIdsByCycleEndOrExpiryWithinDay(@Param("dayStart") LocalDateTime dayStart,
                                                  @Param("dayEnd") LocalDateTime dayEnd,
                                                  Pageable pageable);

    @Modifying
    @Query("DELETE FROM ServiceInstance s WHERE s.id IN :ids")
    int deleteAllByIdIn(@Param("ids") Collection<Long> ids);
}
