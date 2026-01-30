package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.Plan;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PlanRepository extends JpaRepository<Plan,Long> {

    @Cacheable(value = "plans", key = "#planId", unless = "#result == null || !#result.isPresent()")
    Optional<Plan> findByPlanId(String planId);

    @Cacheable(value = "plans", key = "#planIds.toString()", unless = "#result == null || #result.isEmpty()")
    List<Plan> findByPlanIdIn(Set<String> planIds);
}
