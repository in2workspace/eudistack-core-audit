package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.catalogue.CatalogueValidator;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.InsufficientPermissionException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventOutcome;
import com.eudistack.audit.domain.model.Power;
import com.eudistack.audit.domain.model.RecordStatus;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.support.InMemoryEventStore;
import com.eudistack.audit.support.TestCatalogue;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordEventsWorkflowTest {

    private final InMemoryEventStore store = new InMemoryEventStore().withTenant("red-logistica").withTenant("sandbox");
    private final RecordEventsWorkflow workflow = new RecordEventsWorkflow(
            new CatalogueValidator(TestCatalogue.withFixtures()), store, store, store,
            new SelfEventFactory(Clock.systemUTC(), "2.2.0"), AuditTelemetryPort.noop());

    private static final Caller WRITER = new Caller("issuer-client", ActorKind.SYSTEM, null, null, "VATES-E", "platform",
            null, List.of(new Power("organization", "EUDISTACK", "Audit", List.of("Write"))));

    private static ObjectNode event(String tenant) {
        return TestEvents.issuanceCompleted(tenant, "corr-1", "12345678Z");
    }

    @Test
    void record_validEventsOfTwoTenants_eachInItsSchema() {
        List<EventOutcome> outcomes = workflow.recordBatch(WRITER,
                TestEvents.batch(event("red-logistica"), event("SANDBOX")), "trace");

        assertThat(outcomes).extracting(EventOutcome::status).containsOnly(RecordStatus.ACCEPTED);
        assertThat(store.events("red_logistica")).hasSize(1);
        assertThat(store.events("sandbox")).singleElement()
                .extracting(AuditEvent::tenant).isEqualTo("sandbox");
    }

    @Test
    void record_invalidEvents_rejectedAndStoredInPlatform_othersStored() {
        ObjectNode unknownType = event("red-logistica").put("event_type", "Teleported");
        ObjectNode undeclaredField = event("red-logistica");
        ((ObjectNode) undeclaredField.get("details")).put("holder_phone", "600");
        ObjectNode valid = event("red-logistica");

        List<EventOutcome> outcomes = workflow.recordBatch(WRITER, TestEvents.batch(unknownType, undeclaredField, valid), "t");

        assertThat(outcomes).extracting(EventOutcome::reason).containsExactly(RejectionReason.UNKNOWN_EVENT_TYPE,
                RejectionReason.INVALID_EVENT, null);
        assertThat(outcomes.get(1).violations()).isNotEmpty();
        assertThat(store.events("red_logistica")).hasSize(1);
        assertThat(store.events("platform")).hasSize(2)
                .allSatisfy(e -> assertThat(e.eventType()).isEqualTo("AuditEventRejected"));
    }

    @Test
    void record_unknownTenant_rejected_platformEventStored() {
        List<EventOutcome> outcomes = workflow.recordBatch(WRITER,
                TestEvents.batch(event("nowhere"), event("platform")), "t");

        assertThat(outcomes.get(0).reason()).isEqualTo(RejectionReason.UNKNOWN_TENANT);
        assertThat(outcomes.get(1).status()).isEqualTo(RecordStatus.ACCEPTED);
        assertThat(store.events("platform")).extracting(AuditEvent::eventType)
                .containsExactlyInAnyOrder("AuditEventRejected", "IssuanceCompleted");
    }

    @Test
    void record_resentIdentical_isDuplicate_otherContent_isConflict() {
        ObjectNode original = event("red-logistica");
        workflow.recordBatch(WRITER, TestEvents.batch(original), "t");

        ObjectNode changed = original.deepCopy().put("resource", "issuance:other");
        List<EventOutcome> outcomes = workflow.recordBatch(WRITER, TestEvents.batch(original.deepCopy(), changed), "t");

        assertThat(outcomes).extracting(EventOutcome::status).containsExactly(RecordStatus.DUPLICATE, RecordStatus.REJECTED);
        assertThat(outcomes.get(1).reason()).isEqualTo(RejectionReason.EVENT_ID_CONFLICT);
        assertThat(store.events("red_logistica")).hasSize(1);
    }

    @Test
    void record_resentInvalidBatch_leavesOneRejectionOnly() {
        ObjectNode invalid = event("nowhere");

        workflow.recordBatch(WRITER, TestEvents.batch(invalid), "t");
        workflow.recordBatch(WRITER, TestEvents.batch(invalid.deepCopy()), "t");

        assertThat(store.events("platform")).hasSize(1);
    }

    @Test
    void record_withoutWritePower_isDeniedAndAudited() {
        Caller reader = new Caller("auditor", ActorKind.PERSON, null, null, "VATES-B1", "red-logistica", null,
                List.of(new Power("organization", "RED-LOGISTICA", "Audit", List.of("Read"))));

        assertThatThrownBy(() -> workflow.recordBatch(reader, TestEvents.batch(event("red-logistica")), "t"))
                .isInstanceOf(InsufficientPermissionException.class);
        assertThat(store.events("platform")).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("AuditWriteDenied");
            assertThat(e.reason()).isEqualTo("PermissionMissing");
            assertThat(e.details().get("event_count").asInt()).isEqualTo(1);
        });
    }

    @Test
    void record_malformedBatch_isBadRequestAndStoresNothing() {
        ObjectNode tooMany = TestEvents.MAPPER.createObjectNode();
        var events = tooMany.putArray("events");
        for (int i = 0; i < 101; i++) {
            events.add(event("red-logistica"));
        }

        assertThatThrownBy(() -> workflow.recordBatch(WRITER, tooMany, "t")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> workflow.recordBatch(WRITER, null, "t")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> workflow.recordBatch(WRITER, TestEvents.MAPPER.createObjectNode(), "t"))
                .isInstanceOf(BadRequestException.class);
        assertThat(store.events("red_logistica")).isEmpty();
        assertThat(store.events("platform")).isEmpty();
    }

    @Test
    void record_storeFailsAtCommit_nothingStays() {
        store.failNextCommit();

        assertThatThrownBy(() -> workflow.recordBatch(WRITER, TestEvents.batch(event("red-logistica"), event("nowhere")), "t"))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(store.events("red_logistica")).isEmpty();
        assertThat(store.events("platform")).isEmpty();
    }

    @Test
    void recordAuthenticationFailure_leavesWriteDeniedInvalidToken() {
        workflow.recordAuthenticationFailure(7, "trace-9");

        assertThat(store.events("platform")).singleElement().satisfies(e -> {
            assertThat(e.reason()).isEqualTo("InvalidToken");
            assertThat(e.actor().get("id").asText()).isEqualTo("anonymous");
            assertThat(e.correlationId()).isEqualTo("trace-9");
        });
    }
}
