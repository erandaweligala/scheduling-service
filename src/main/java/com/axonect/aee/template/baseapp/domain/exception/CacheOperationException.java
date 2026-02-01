package com.axonect.aee.template.baseapp.domain.exception;

/**
 * Exception thrown when a cache operation fails
 */
public class CacheOperationException extends RuntimeException {

    public CacheOperationException(String message) {
        super(message);
    }

    public CacheOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
