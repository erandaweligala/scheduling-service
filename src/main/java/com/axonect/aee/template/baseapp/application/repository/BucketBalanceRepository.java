package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.BucketBalanceUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JDBC batch writer for BUCKET_INSTANCE balances. JPA cannot batch these updates without
 * loading every row first, so they are sent as a single JDBC batch instead.
 */
@Repository
@RequiredArgsConstructor
public class BucketBalanceRepository {

    private static final String UPDATE_BALANCE_SQL =
            "UPDATE BUCKET_INSTANCE SET CURRENT_BALANCE = ?, USAGE = ?, UPDATED_AT = ? " +
            "WHERE ID = ? AND SERVICE_ID = ?";
    private static final int[] UPDATE_BALANCE_TYPES =
            {Types.BIGINT, Types.BIGINT, Types.TIMESTAMP, Types.BIGINT, Types.BIGINT};

    private final JdbcTemplate jdbcTemplate;

    /**
     * Update CURRENT_BALANCE and USAGE for every given bucket instance in one JDBC batch and one
     * short transaction. Callers should pass updates ordered by bucket instance ID so concurrent
     * writers lock rows in a consistent order. Transient failures (lock timeouts, deadlocks) are
     * retried, each attempt in a fresh transaction.
     *
     * @return number of rows updated
     */
    @Retryable(retryFor = TransientDataAccessException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public int updateBalances(Collection<BucketBalanceUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        Timestamp updatedAt = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> batchArgs = new ArrayList<>(updates.size());
        for (BucketBalanceUpdate update : updates) {
            batchArgs.add(new Object[]{
                    update.currentBalance(), update.usage(), updatedAt,
                    update.bucketInstanceId(), update.serviceId()});
        }

        int updated = 0;
        for (int count : jdbcTemplate.batchUpdate(UPDATE_BALANCE_SQL, batchArgs, UPDATE_BALANCE_TYPES)) {
            // Statement.SUCCESS_NO_INFO (-2) is reported as 0 rows
            if (count > 0) {
                updated += count;
            }
        }
        return updated;
    }
}
