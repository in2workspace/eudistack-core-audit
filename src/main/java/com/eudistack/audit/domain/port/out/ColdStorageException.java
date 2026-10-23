package com.eudistack.audit.domain.port.out;

/**
 * The storage is not available or refuses the write.
 */
public final class ColdStorageException extends RuntimeException {

    public ColdStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
