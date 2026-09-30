package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.BucketBalanceUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BucketBalanceRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BucketBalanceRepository repository;

    @Test
    @DisplayName("Sends every update in one JDBC batch and sums the reported row counts")
    void updateBalances_singleBatch() {
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), any(int[].class)))
                .thenReturn(new int[]{1, Statement.SUCCESS_NO_INFO, 0});

        int updated = repository.updateBalances(List.of(
                new BucketBalanceUpdate(11L, 6L, 100L, 400L),
                new BucketBalanceUpdate(12L, 5L, 400L, 600L),
                new BucketBalanceUpdate(13L, 5L, 0L, 50L)));

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(
                eq("UPDATE BUCKET_INSTANCE SET CURRENT_BALANCE = ?, USAGE = ?, UPDATED_AT = ? " +
                        "WHERE ID = ? AND SERVICE_ID = ?"),
                args.capture(), any(int[].class));
        assertThat(args.getValue()).hasSize(3);
        Object[] first = args.getValue().get(0);
        assertThat(first[0]).isEqualTo(100L);
        assertThat(first[1]).isEqualTo(400L);
        assertThat(first[2]).isInstanceOf(Timestamp.class);
        assertThat(first[3]).isEqualTo(11L);
        assertThat(first[4]).isEqualTo(6L);
    }

    @Test
    @DisplayName("Does not touch the database when there is nothing to update")
    void updateBalances_empty() {
        assertThat(repository.updateBalances(List.of())).isZero();
        verifyNoInteractions(jdbcTemplate);
    }
}
