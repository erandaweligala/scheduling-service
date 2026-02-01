package com.axonect.aee.template.baseapp.domain.exception;

/**
 * Exception thrown when a cache operation times out
 */
public class CacheTimeoutException extends RuntimeException {

    public CacheTimeoutException(String message) {
        super(message);
    }

    public CacheTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
