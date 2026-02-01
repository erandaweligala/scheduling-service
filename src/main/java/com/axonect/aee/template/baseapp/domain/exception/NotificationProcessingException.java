package com.axonect.aee.template.baseapp.domain.exception;

/**
 * Exception thrown when notification processing fails
 */
public class NotificationProcessingException extends RuntimeException {

    public NotificationProcessingException(String message) {
        super(message);
    }

    public NotificationProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
