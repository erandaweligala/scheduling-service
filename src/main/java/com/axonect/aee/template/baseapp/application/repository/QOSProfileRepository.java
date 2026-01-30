package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.QOSProfile;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface QOSProfileRepository extends JpaRepository<QOSProfile,Long> {

    @Cacheable(value = "qosProfiles", key = "#qosId", unless = "#result == null || !#result.isPresent()")
    Optional<QOSProfile> findById(Long qosId);

    @Cacheable(value = "qosProfiles", key = "#qosIds.toString()", unless = "#result == null || #result.isEmpty()")
    List<QOSProfile> findByIdIn(Set<Long> qosIds);
}
