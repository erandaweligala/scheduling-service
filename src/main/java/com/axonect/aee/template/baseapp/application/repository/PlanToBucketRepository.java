package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.PlanToBucket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanToBucketRepository extends JpaRepository<PlanToBucket,Long> {

    List<PlanToBucket> findByPlanId(String planId);
}
