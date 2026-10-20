package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyPolicyTest {

    @Test
    void rejectionEventId_sameReceivedContent_sameIdentifier() {
        ObjectNode event = TestEvents.issuanceCompleted("unknown", "c-1", "1Z");

        UUID first = IdempotencyPolicy.rejectionEventId(event);
        UUID second = IdempotencyPolicy.rejectionEventId(event.deepCopy());

        assertThat(first).isEqualTo(second);
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
    }

    @Test
    void rejectionEventId_otherContent_otherIdentifier() {
        ObjectNode event = TestEvents.issuanceCompleted("unknown", "c-1", "1Z");
        ObjectNode changed = event.deepCopy().put("source", "verifier");

        assertThat(IdempotencyPolicy.rejectionEventId(event)).isNotEqualTo(IdempotencyPolicy.rejectionEventId(changed));
    }

    @Test
    void uuidV5_matchesRfcVector() {
        // RFC 9562 Appendix A.4: name "www.example.com" in the DNS namespace.
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        assertThat(IdempotencyPolicy.uuidV5(dns, "www.example.com"))
                .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    }

    @Test
    void sameContent_comparesHashes() {
        ObjectNode event = TestEvents.issuanceCompleted("sandbox", "c-1", "1Z");
        byte[] hash = IdempotencyPolicy.payloadHash(event);

        assertThat(IdempotencyPolicy.sameContent(hash, IdempotencyPolicy.payloadHash(event.deepCopy()))).isTrue();
        assertThat(IdempotencyPolicy.sameContent(hash,
                IdempotencyPolicy.payloadHash(event.deepCopy().put("resource", "issuance:other")))).isFalse();
    }
}
