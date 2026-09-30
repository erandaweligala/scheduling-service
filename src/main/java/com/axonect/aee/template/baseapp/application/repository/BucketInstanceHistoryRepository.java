package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstanceHistory;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface BucketInstanceHistoryRepository extends JpaRepository<BucketInstanceHistory, Long> {

    List<BucketInstanceHistory> findByOriginalId(Long originalId);

    List<BucketInstanceHistory> findByServiceId(Long serviceId);

    List<BucketInstanceHistory> findByBatchId(String batchId);

    /**
     * Copy every BUCKET_INSTANCE row owned by the given services into BUCKET_INSTANCE_HISTORY
     * with a single INSERT ... SELECT. Each bucket inherits its parent service's deletion
     * reason (CYCLE_END_DATE_EXPIRED when the parent's CYCLE_END_DATE falls in
     * [dayStart, dayEnd), otherwise EXPIRY_DATE_EXPIRED).
     *
     * @return number of history rows inserted
     */
    @Modifying
    @Query(value = "INSERT INTO BUCKET_INSTANCE_HISTORY (" +
            "ID, ORIGINAL_ID, BUCKET_ID, SERVICE_ID, BUCKET_TYPE, RULE, PRIORITY, " +
            "INITIAL_BALANCE, CURRENT_BALANCE, USAGE, CARRY_FORWARD, MAX_CARRY_FORWARD, " +
            "TOTAL_CARRY_FORWARD, CARRY_FORWARD_VALIDITY, TIME_WINDOW, CONSUMPTION_LIMIT, " +
            "CONSUMPTION_LIMIT_WINDOW, EXPIRATION, ORIGINAL_UPDATED_AT, IS_UNLIMITED, " +
            "DELETION_REASON, ARCHIVED_AT, BATCH_ID) " +
            "SELECT BUCKET_INSTANCE_HISTORY_SEQ.NEXTVAL, b.ID, b.BUCKET_ID, b.SERVICE_ID, b.BUCKET_TYPE, " +
            "b.RULE, b.PRIORITY, b.INITIAL_BALANCE, b.CURRENT_BALANCE, b.USAGE, b.CARRY_FORWARD, " +
            "b.MAX_CARRY_FORWARD, b.TOTAL_CARRY_FORWARD, b.CARRY_FORWARD_VALIDITY, b.TIME_WINDOW, " +
            "b.CONSUMPTION_LIMIT, b.CONSUMPTION_LIMIT_WINDOW, b.EXPIRATION, b.UPDATED_AT, b.IS_UNLIMITED, " +
            "CASE WHEN s.CYCLE_END_DATE >= :dayStart AND s.CYCLE_END_DATE < :dayEnd " +
            "THEN '" + Constants.DELETION_REASON_CYCLE_END + "' " +
            "ELSE '" + Constants.DELETION_REASON_EXPIRY + "' END, " +
            ":archivedAt, :batchId " +
            "FROM BUCKET_INSTANCE b JOIN SERVICE_INSTANCE s ON s.ID = b.SERVICE_ID " +
            "WHERE b.SERVICE_ID IN (:serviceIds)",
            nativeQuery = true)
    int archiveBucketInstancesOfServices(@Param("serviceIds") Collection<Long> serviceIds,
                                         @Param("dayStart") LocalDateTime dayStart,
                                         @Param("dayEnd") LocalDateTime dayEnd,
                                         @Param("batchId") String batchId,
                                         @Param("archivedAt") LocalDateTime archivedAt);
}
