package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ChildTemplateTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChildTemplateTableRepository extends JpaRepository<ChildTemplateTable, Long> {

    @Query("SELECT c FROM ChildTemplateTable c WHERE c.messageType = 'EXPIRE'")
    List<ChildTemplateTable> findAllExpireTemplates();
}
