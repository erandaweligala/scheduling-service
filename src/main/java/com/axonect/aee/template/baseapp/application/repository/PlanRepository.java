package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PlanRepository extends JpaRepository<Plan,Long> {

    Optional<Plan> findByPlanId(String planId);

    List<Plan> findByPlanIdIn(Set<String> planIds);
}
