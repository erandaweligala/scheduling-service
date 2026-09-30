package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstanceHistory;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ServiceInstanceHistoryRepository extends JpaRepository<ServiceInstanceHistory, Long> {

    List<ServiceInstanceHistory> findByOriginalId(Long originalId);

    List<ServiceInstanceHistory> findByBatchId(String batchId);

    /**
     * Copy the given SERVICE_INSTANCE rows into SERVICE_INSTANCE_HISTORY with a single
     * INSERT ... SELECT, so the rows never leave the database and history IDs are drawn from
     * the sequence server-side. DELETION_REASON is CYCLE_END_DATE_EXPIRED when CYCLE_END_DATE
     * falls in [dayStart, dayEnd), otherwise EXPIRY_DATE_EXPIRED.
     *
     * @return number of history rows inserted
     */
    @Modifying
    @Query(value = "INSERT INTO SERVICE_INSTANCE_HISTORY (" +
            "ID, ORIGINAL_ID, PLAN_ID, PLAN_NAME, PLAN_TYPE, RECURRING_FLAG, USERNAME, " +
            "CYCLE_START_DATE, CYCLE_END_DATE, NEXT_CYCLE_START_DATE, SERVICE_START_DATE, EXPIRY_DATE, " +
            "STATUS, ORIGINAL_CREATED_AT, ORIGINAL_UPDATED_AT, REQUEST_ID, IS_GROUP, " +
            "DELETION_REASON, ARCHIVED_AT, BATCH_ID) " +
            "SELECT SERVICE_INSTANCE_HISTORY_SEQ.NEXTVAL, s.ID, s.PLAN_ID, s.PLAN_NAME, s.PLAN_TYPE, " +
            "s.RECURRING_FLAG, s.USERNAME, s.CYCLE_START_DATE, s.CYCLE_END_DATE, s.NEXT_CYCLE_START_DATE, " +
            "s.SERVICE_START_DATE, s.EXPIRY_DATE, s.STATUS, s.CREATED_AT, s.UPDATED_AT, s.REQUEST_ID, s.IS_GROUP, " +
            "CASE WHEN s.CYCLE_END_DATE >= :dayStart AND s.CYCLE_END_DATE < :dayEnd " +
            "THEN '" + Constants.DELETION_REASON_CYCLE_END + "' " +
            "ELSE '" + Constants.DELETION_REASON_EXPIRY + "' END, " +
            ":archivedAt, :batchId " +
            "FROM SERVICE_INSTANCE s WHERE s.ID IN (:serviceIds)",
            nativeQuery = true)
    int archiveServiceInstances(@Param("serviceIds") Collection<Long> serviceIds,
                                @Param("dayStart") LocalDateTime dayStart,
                                @Param("dayEnd") LocalDateTime dayEnd,
                                @Param("batchId") String batchId,
                                @Param("archivedAt") LocalDateTime archivedAt);
}
