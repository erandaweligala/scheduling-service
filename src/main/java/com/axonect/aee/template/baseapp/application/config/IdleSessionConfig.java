package com.axonect.aee.template.baseapp.application.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for idle session termination scheduler.
 * Configures timeout thresholds, scheduler interval, and batch processing.
 */
@Configuration
@ConfigurationProperties(prefix = "idle-session")
@Getter
@Setter
public class IdleSessionConfig {

    /**
     * Whether the idle session terminator is enabled.
     */
    private boolean enabled = true;

    /**
     * The idle session timeout threshold in minutes.
     * Sessions idle longer than this threshold will be terminated.
     * Default is 60 minutes.
     */
    private int timeoutMinutes = 60;

    /**
     * Scheduler interval in milliseconds.
     * Default is 300000 ms (5 minutes).
     */
    private long schedulerIntervalMs = 300000;

    /**
     * Batch size for processing expired sessions per scheduler run.
     */
    private int batchSize = 100;

    /**
     * Kafka topic for DB write events produced on session termination.
     */
    private String dbWriteTopic = "db-write-events";
}
