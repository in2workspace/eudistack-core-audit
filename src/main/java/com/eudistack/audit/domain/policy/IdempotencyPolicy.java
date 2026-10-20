package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Idempotency by {@code event_id} (FR-08, AD-4).
 *
 * <ul>
 *   <li>The payload hash is the SHA-256 of the RFC 8785 canonical JSON of the event as received
 *       ({@code recorded_at} is never part of it: it is never sent). Same {@code event_id} and same
 *       hash is a {@code duplicate}; same {@code event_id} and another hash is an {@code event_id_conflict}.</li>
 *   <li>The {@code event_id} of an {@code AuditEventRejected} is a UUIDv5 of the received hash (decision
 *       D-6): a producer that resends a batch because it lost the 200 does not leave a second rejection.</li>
 * </ul>
 */
public final class IdempotencyPolicy {

    /** Namespace of the UUIDv5 of rejected events. Fixed: changing it breaks the deduplication. */
    static final UUID REJECTION_NAMESPACE = UUID.fromString("6f6b2a9e-3c1d-5e0f-9a7b-2d4c8e1f0a35");

    private IdempotencyPolicy() {
    }

    public static byte[] payloadHash(JsonNode received) {
        return CanonicalJson.sha256(received);
    }

    public static boolean sameContent(byte[] stored, byte[] received) {
        return MessageDigest.isEqual(stored, received);
    }

    public static UUID rejectionEventId(JsonNode received) {
        return uuidV5(REJECTION_NAMESPACE, "rejected:" + CanonicalJson.hex(payloadHash(received)));
    }

    /**
     * Name-based UUID, version 5 (RFC 9562 §5.5).
     */
    static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits())
                    .array());
            byte[] hash = Arrays.copyOf(sha1.digest(name.getBytes(StandardCharsets.UTF_8)), 16);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
