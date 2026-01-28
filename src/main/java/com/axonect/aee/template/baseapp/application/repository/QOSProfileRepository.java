package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.QOSProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QOSProfileRepository extends JpaRepository<QOSProfile,Long> {

    Optional<QOSProfile> findById(Long qosId);
}
