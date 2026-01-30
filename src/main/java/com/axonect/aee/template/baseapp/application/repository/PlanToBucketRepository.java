package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.PlanToBucket;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface PlanToBucketRepository extends JpaRepository<PlanToBucket,Long> {

    @Cacheable(value = "planToBuckets", key = "#planId", unless = "#result == null || #result.isEmpty()")
    List<PlanToBucket> findByPlanId(String planId);

    @Cacheable(value = "planToBuckets", key = "#planIds.toString()", unless = "#result == null || #result.isEmpty()")
    List<PlanToBucket> findByPlanIdIn(Set<String> planIds);
}
