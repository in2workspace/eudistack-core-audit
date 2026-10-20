package com.eudistack.audit.domain.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

/**
 * Authenticated caller, built from a verified Verifier access token.
 *
 * @param subject      {@code sub} of the token: user identifier (H2M) or client identifier (M2M)
 * @param kind         {@link ActorKind#PERSON} or {@link ActorKind#SYSTEM}
 * @param name         full name of a person, when the token carries it
 * @param email        email of a person, when the token carries it
 * @param organization {@code mandator.organizationIdentifier}, when the token carries it
 * @param tenant       {@code tenant} claim, normalised to lower case without environment suffix
 * @param attestationId identifier of the attestation presented, when the token carries it
 * @param powers       {@code power} claim
 */
public record Caller(
        String subject,
        ActorKind kind,
        String name,
        String email,
        String organization,
        String tenant,
        String attestationId,
        List<Power> powers
) {

    public Caller {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        powers = powers == null ? List.of() : List.copyOf(powers);
    }

    public boolean isSystem() {
        return kind == ActorKind.SYSTEM;
    }

    /**
     * Actor of the canonical record for the events this API generates on behalf of the caller.
     */
    public ObjectNode toActorJson() {
        ObjectNode actor = JsonNodeFactory.instance.objectNode();
        actor.put("kind", kind.wireValue());
        actor.put("id", subject);
        putIfPresent(actor, "name", name);
        putIfPresent(actor, "email", email);
        putIfPresent(actor, "organization", organization);
        putIfPresent(actor, "attestation_id", attestationId);
        return actor;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
