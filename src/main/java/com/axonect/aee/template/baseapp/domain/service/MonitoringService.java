package com.axonect.aee.template.baseapp.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for recording operational metrics from the idle session terminator.
 * Extend this service to push metrics to Prometheus or any monitoring backend.
 */
@Service
@Slf4j
public class MonitoringService {

    /**
     * Record the number of idle sessions terminated in a scheduler run.
     *
     * @param count number of sessions terminated
     */
    public void recordIdleSessionsTerminated(int count) {
        log.info("Monitoring: idle sessions terminated in this run: {}", count);
    }
}
