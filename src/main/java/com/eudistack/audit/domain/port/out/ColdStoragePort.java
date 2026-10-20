package com.eudistack.audit.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cold storage of archived events: write once, locked until {@code retainUntil} (AD-7). There is no
 * delete operation on purpose.
 */
public interface ColdStoragePort {

    /**
     * Writes an object locked in compliance mode until {@code retainUntil}.
     *
     * @throws ColdStorageException when the storage is not available or refuses the write
     */
    void put(String key, byte[] content, String contentType, Instant retainUntil);

    Optional<byte[]> get(String key);

    List<String> list(String prefix);

    /**
     * Retain-until date of the compliance lock of an object, empty when it is not locked.
     */
    Optional<Instant> lockedUntil(String key);

    /**
     * Reference of an object for {@code archive_ref}, e.g. {@code s3://bucket/key}.
     */
    String reference(String key);

    final class ColdStorageException extends RuntimeException {
        public ColdStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
