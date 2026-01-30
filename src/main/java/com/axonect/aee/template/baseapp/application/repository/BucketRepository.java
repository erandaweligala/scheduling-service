package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.Bucket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BucketRepository extends JpaRepository<Bucket,String> {

    Optional<Bucket> findByBucketId(String bucketId);

    List<Bucket> findByBucketIdIn(Set<String> bucketIds);
}
