package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance,Long> {
//    List<ServiceInstance> findByUsernameAndRecurringFlagTrue(String username);
    List<ServiceInstance> findByUsernameAndRecurringFlagTrueAndNextCycleStartDate(String username, LocalDate expiryDate);

    List<ServiceInstance> findByUsernameInAndRecurringFlagTrueAndNextCycleStartDate(
            List<String> usernames, LocalDateTime nextCycleStartDate);

    List<ServiceInstance> findByUsernameInAndRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
            List<String> usernames, LocalDateTime nextCycleStartDate, LocalDateTime expiryDate);
}
