package com.eudistack.audit.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Valid events of the 2.2.0 catalogue, as a producer would send them.
 */
public final class TestEvents {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private TestEvents() {
    }

    /**
     * {@code IssuanceCompleted} of the OpenAPI example, with a fresh {@code event_id}.
     */
    public static ObjectNode issuanceCompleted(String tenant, String correlationId, String holderIdentifier) {
        ObjectNode event = canonical("IssuanceCompleted", "Issue", tenant, correlationId);
        event.put("organization", "VATES-B00000000");
        event.put("resource", "issuance:" + correlationId);
        ObjectNode details = event.putObject("details");
        details.put("provider_legal_name", "Red Logística S.L.");
        details.put("provider_registration_id", "VATES-B00000000");
        details.put("attestation_type", "employee.sd.1");
        details.put("delivery_method", "email");
        details.putObject("holder")
                .put("identifier", holderIdentifier)
                .put("name", "María García López")
                .put("email", "maria.garcia@red-logistica.example");
        ArrayNode attributes = details.putArray("issued_attributes");
        attributes.add("given_name").add("family_name");
        details.put("signing_certificate", "sha256:9f2c");
        details.put("signature_algorithm", "ES256");
        details.put("attestation_hash", "sha256:41ab");
        details.putObject("wallet_interaction").put("protocol", "oid4vci").put("format", "dc+sd-jwt");
        details.put("accepted_by_holder", true);
        return event;
    }

    /**
     * {@code PresentationVerification} with a successful presentation, by the Verifier.
     */
    public static ObjectNode presentationVerification(String tenant, String correlationId, String holderIdentifier) {
        ObjectNode event = canonical("PresentationVerification", "Verify", tenant, correlationId);
        event.put("source", "verifier");
        event.put("organization", "VATES-B00000000");
        event.put("resource", "verification:" + correlationId);
        ObjectNode details = event.putObject("details");
        details.put("oidc_client_id", "portal-rrhh");
        details.put("oidc_client_role", "IAM");
        details.putArray("requested_attributes").add("given_name");
        details.put("presented_attestation_type", "employee.sd.1");
        details.put("attestation_issuer", "did:web:issuer.example");
        details.putArray("disclosed_attributes").add("given_name");
        details.put("presentation_authenticity", "Verified");
        details.put("holder_binding", "Verified");
        details.put("revocation_status", "Valid");
        details.putObject("holder").put("identifier", holderIdentifier).put("name", "María García López");
        details.put("oidc_access_decision", "Granted");
        return event;
    }

    public static ObjectNode withOrganization(ObjectNode event, String organization) {
        event.put("organization", organization);
        return event;
    }

    public static ObjectNode batch(ObjectNode... events) {
        ObjectNode batch = MAPPER.createObjectNode();
        ArrayNode list = batch.putArray("events");
        for (ObjectNode event : events) {
            list.add(event);
        }
        return batch;
    }

    private static ObjectNode canonical(String type, String action, String tenant, String correlationId) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_type", type);
        event.put("event_version", "2.2.0");
        event.put("occurred_at", Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString());
        event.put("tenant", tenant);
        event.put("source", "issuer");
        event.putObject("actor").put("kind", "System").put("id", "issuer");
        event.put("action", action);
        event.put("result", "Success");
        event.put("correlation_id", correlationId);
        return event;
    }
}
