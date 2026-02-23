package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ChildTemplateTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for managing ChildTemplateTable entities
 */
@Repository
public interface ChildTemplateTableRepository extends JpaRepository<ChildTemplateTable, Long> {

    /**
     * Find all templates by message type
     *
     * @param messageType the type of message (e.g., "EXPIRE", "QUOTA")
     * @return list of templates matching the message type
     */
    List<ChildTemplateTable> findByMessageType(String messageType);

    /**
     * Find all EXPIRE type templates with their days to expire configuration
     * Used for bucket expiry notification processing
     *
     * @return list of all EXPIRE templates
     */
    @Query("SELECT c FROM ChildTemplateTable c WHERE c.messageType = 'EXPIRE' ORDER BY c.daysToExpire")
    List<ChildTemplateTable> findAllExpireTemplates();

    /**
     * Find templates by message type and days to expire
     * Useful for specific threshold-based notifications
     *
     * @param messageType the type of message
     * @param daysToExpire the days before expiry
     * @return list of matching templates
     */
    List<ChildTemplateTable> findByMessageTypeAndDaysToExpire(String messageType, Integer daysToExpire);
}
