package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.Bucket;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BucketRepository extends JpaRepository<Bucket,String> {

    @Cacheable(value = "buckets", key = "#bucketId", unless = "#result == null || !#result.isPresent()")
    Optional<Bucket> findByBucketId(String bucketId);

    @Cacheable(value = "buckets", key = "#bucketIds.toString()", unless = "#result == null || #result.isEmpty()")
    List<Bucket> findByBucketIdIn(Set<String> bucketIds);
}
