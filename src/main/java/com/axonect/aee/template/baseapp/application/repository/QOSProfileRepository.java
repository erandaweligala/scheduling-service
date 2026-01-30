package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.QOSProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface QOSProfileRepository extends JpaRepository<QOSProfile,Long> {

    Optional<QOSProfile> findById(Long qosId);

    List<QOSProfile> findByIdIn(Set<Long> qosIds);
}
