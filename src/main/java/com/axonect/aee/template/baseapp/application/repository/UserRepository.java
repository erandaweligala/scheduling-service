package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.UserEntity;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    Page<UserEntity> findAllByStatus(String status, Pageable pageable);

    @Query("SELECT u.userName FROM UserEntity u WHERE u.status = :status")
    Page<String> findUserNamesByStatus(@Param("status") UserStatus status, Pageable pageable);

    @Cacheable(value = "users", key = "#userName", unless = "#result == null")
    UserEntity findAllByUserName(String userName);

    @Cacheable(value = "users", key = "#userNames.toString()", unless = "#result == null || #result.isEmpty()")
    List<UserEntity> findByUserNameIn(Set<String> userNames);
}
