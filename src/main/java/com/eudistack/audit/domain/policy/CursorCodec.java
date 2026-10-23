package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.model.CursorPosition;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque, tamper-evident listing cursor: the position of the last event served, the scope and a
 * digest of the filters it belongs to, and the resolved period, signed with HMAC-SHA256.
 *
 * <p>A cursor is only valid with the scope and filters that produced it: reusing it on another tenant
 * or with other filters is a {@code BAD_REQUEST}, never a way to read outside the scope.
 */
public final class CursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final ObjectMapper mapper = new ObjectMapper();

    public CursorCodec(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("The cursor secret must have at least 32 bytes");
        }
        this.secret = secret.clone();
    }

    /**
     * @param scope       scope label the cursor belongs to
     * @param filtersHash digest of the normalised filters
     * @param from        resolved start of the period
     * @param to          resolved end of the period
     * @param position    last event served
     */
    public record CursorState(String scope, String filtersHash, Instant from, Instant to, CursorPosition position) {
    }

    public String encode(CursorState state) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", 1);
        node.put("s", state.scope());
        node.put("f", state.filtersHash());
        node.put("from", state.from().toString());
        node.put("to", state.to().toString());
        node.put("at", state.position().occurredAt().toString());
        node.put("id", state.position().eventId().toString());
        if (state.position().schema() != null) {
            node.put("sc", state.position().schema());
        }
        byte[] payload = CanonicalJson.canonicalize(node).getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    public CursorState decode(String cursor) {
        try {
            int dot = cursor.indexOf('.');
            if (dot <= 0) {
                throw invalid();
            }
            byte[] payload = DECODER.decode(cursor.substring(0, dot));
            byte[] signature = DECODER.decode(cursor.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), signature)) {
                throw invalid();
            }
            JsonNode node = mapper.readTree(payload);
            return new CursorState(
                    node.get("s").asText(),
                    node.get("f").asText(),
                    Instant.parse(node.get("from").asText()),
                    Instant.parse(node.get("to").asText()),
                    new CursorPosition(Instant.parse(node.get("at").asText()), UUID.fromString(node.get("id").asText()),
                            node.hasNonNull("sc") ? node.get("sc").asText() : null));
        } catch (BadRequestException e) {
            throw e;
        } catch (RuntimeException | java.io.IOException _) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC not available", e);
        }
    }

    private static BadRequestException invalid() {
        return new BadRequestException("cursor", "The cursor is not valid");
    }
}
