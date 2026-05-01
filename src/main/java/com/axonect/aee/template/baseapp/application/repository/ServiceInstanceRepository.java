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
     * Page through service instances whose status equals the given value.
     * Used by the activation scheduler to flip PENDING -> ACTIVE in chunks.
     */
    @Query(value = "SELECT /*+ INDEX(s idx_service_status_recurring) FIRST_ROWS(100) */ " +
            "s.* FROM SERVICE_INSTANCE s WHERE s.STATUS = :status",
            countQuery = "SELECT COUNT(*) FROM SERVICE_INSTANCE s WHERE s.STATUS = :status",
            nativeQuery = true)
    Page<ServiceInstance> findByStatus(@Param("status") String status, Pageable pageable);

    /**
     * Bulk-update STATUS for the given IDs. Returns the number of rows updated.
     * UPDATED_AT is refreshed because @UpdateTimestamp is bypassed for bulk JPQL.
     */
    @Modifying
    @Query("UPDATE ServiceInstance s SET s.status = :newStatus, s.updatedAt = :updatedAt " +
            "WHERE s.id IN :ids AND s.status = :expectedStatus")
    int bulkUpdateStatus(@Param("ids") Collection<Long> ids,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("newStatus") String newStatus,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Page through service instances whose CYCLE_END_DATE OR EXPIRY_DATE falls within the
     * given inclusive-exclusive day window. Used by the cleanup scheduler.
     */
    @Query(value = "SELECT /*+ FIRST_ROWS(100) */ s.* FROM SERVICE_INSTANCE s " +
            "WHERE (s.CYCLE_END_DATE >= :dayStart AND s.CYCLE_END_DATE < :dayEnd) " +
            "   OR (s.EXPIRY_DATE   >= :dayStart AND s.EXPIRY_DATE   < :dayEnd)",
            countQuery = "SELECT COUNT(*) FROM SERVICE_INSTANCE s " +
            "WHERE (s.CYCLE_END_DATE >= :dayStart AND s.CYCLE_END_DATE < :dayEnd) " +
            "   OR (s.EXPIRY_DATE   >= :dayStart AND s.EXPIRY_DATE   < :dayEnd)",
            nativeQuery = true)
    Page<ServiceInstance> findByCycleEndOrExpiryWithinDay(
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd,
            Pageable pageable);

    @Modifying
    @Query("DELETE FROM ServiceInstance s WHERE s.id IN :ids")
    int deleteAllByIdIn(@Param("ids") Collection<Long> ids);
}
