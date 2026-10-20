package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.catalogue.CatalogueValidator;
import com.eudistack.audit.domain.catalogue.ValidationResult;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.model.Violation;
import com.eudistack.audit.support.TestCatalogue;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The events the API writes about itself conform to the catalogue it runs with (PO-11).
 */
class SelfEventFactoryTest {

    private final SelfEventFactory factory = new SelfEventFactory(
            Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC), "2.2.0");
    private final CatalogueValidator validator = new CatalogueValidator(TestCatalogue.published());
    private final Caller caller = new Caller("user-1", ActorKind.PERSON, "Ana Pérez", "ana@example.org",
            "VATES-B1", "red-logistica", null, List.of());

    private void assertConforms(AuditEvent event) {
        ValidationResult result = validator.validate(event.toJson());
        assertThat(result).as(() -> result.toString()).isInstanceOf(ValidationResult.Valid.class);
    }

    @Test
    void rejected_conformsAndCarriesTheReceivedEvent() {
        ObjectNode received = TestEvents.issuanceCompleted("unknown-tenant", "c-9", "1Z");

        AuditEvent event = factory.rejected(received, RejectionReason.INVALID_EVENT,
                List.of(new Violation("details.x", "Field not declared")), "trace-1");

        assertConforms(event);
        assertThat(event.tenant()).isEqualTo(TenantRef.PLATFORM_ID);
        assertThat(event.reason()).isEqualTo("InvalidEvent");
        assertThat(event.details().get("received_event")).isEqualTo(received);
        assertThat(event.details().get("declared_tenant").asText()).isEqualTo("unknown-tenant");
        assertThat(event.correlationId()).isEqualTo("c-9");
        assertThat(event.resource()).isEqualTo("audit-event:" + received.get("event_id").asText());
    }

    @Test
    void rejected_withoutViolations_omitsThem() {
        AuditEvent event = factory.rejected(TestEvents.issuanceCompleted("x", "c-9", "1Z"),
                RejectionReason.UNKNOWN_TENANT, List.of(), "trace-1");

        assertConforms(event);
        assertThat(event.details().has("violations")).isFalse();
    }

    @Test
    void rejected_notAnObject_conforms() {
        AuditEvent event = factory.rejected(JsonNodeFactory.instance.textNode("garbage"),
                RejectionReason.INVALID_EVENT, List.of(new Violation("", "The event must be a JSON object")), "t-1");

        assertConforms(event);
        assertThat(event.correlationId()).isEqualTo("t-1");
    }

    @Test
    void writeDenied_conforms() {
        assertConforms(factory.writeDenied(null, "InvalidToken", 3, "trace-1"));
        assertConforms(factory.writeDenied(caller, "PermissionMissing", 0, "trace-1"));
    }

    @Test
    void trailQueried_conforms() {
        TenantRef red = new TenantRef("red-logistica", "red_logistica");
        ObjectNode filters = JsonNodeFactory.instance.objectNode().put("event_type", "IssuanceCompleted");

        assertConforms(factory.trailQueried(caller, red, "List", "red-logistica", filters, null, 4, "t"));
        assertConforms(factory.trailQueried(caller, red, "Get", "red-logistica", null, "EventNotFound", null, "t"));
        assertConforms(factory.trailQueried(caller, TenantRef.PLATFORM, "List", "platform", filters,
                "TenantNotResolved", null, "t"));
    }

    @Test
    void archived_conforms() {
        Instant from = Instant.parse("2026-07-20T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");

        assertConforms(factory.archived("red-logistica", from, to, 12, null, "s3://b/k", "sha256:ab", "run-1"));
        assertConforms(factory.archived("red-logistica", from, to, 12, "CopyMismatch", null, null, "run-1"));
    }
}
