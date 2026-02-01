package com.axonect.aee.template.baseapp.domain.exception;

/**
 * Exception thrown when serialization or deserialization of cache data fails
 */
public class CacheSerializationException extends RuntimeException {

    public CacheSerializationException(String message) {
        super(message);
    }

    public CacheSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
