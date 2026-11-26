package com.eudistack.audit.domain.catalogue;

import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.model.Violation;
import com.eudistack.audit.support.TestCatalogue;
import com.eudistack.audit.support.TestEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The events of the issuance portal, published in 2.5.0. */
class IssuancePortalCatalogueTest {

    private static final String PROCESS_ID = "0b6f1d2e-2f3a-4c5b-9d8e-1a2b3c4d5e6f";
    private static final String ENTITLEMENT_DETAILS = """
            {"attestation_type": "doctorid.sd.1", "adapter": "http", "identifier_type": "colegiado",
             "duration_ms": 120}""";

    private final CatalogueValidator validator = new CatalogueValidator(TestCatalogue.published());

    static Stream<Arguments> portalEvents() {
        String journey = "{\"step\": \"started\", \"state\": \"in_progress\"}";
        String process = "{\"request_id\": \"r1\", \"issuance_process_id\": \"" + PROCESS_ID + "\"}";
        return Stream.of(
                Arguments.of("JourneyStarted", "StartJourney", """
                        {"identification_method": "certificate", "step": "started", "state": "in_progress"}"""),
                Arguments.of("JourneyStepReached", "ReachStep", journey),
                Arguments.of("JourneyCompleted", "CompleteJourney", journey),
                Arguments.of("JourneyEnded", "EndJourney", "{\"end_cause\": \"expired\"}"),
                Arguments.of("JourneyWithdrawn", "WithdrawJourney", """
                        {"withdrawal_reason": "not_entitled", "step": "identified", "state": "withdrawn"}"""),
                Arguments.of("PortalIssuanceRequested", "RequestIssuance", process),
                Arguments.of("OfferRenewalRequested", "RenewOffer", process),
                Arguments.of("IssuanceRequestSaved", "SaveRequest", """
                        {"request_id": "r1", "issuance_process_id": "%s", "origin": "holder_declared"}"""
                        .formatted(PROCESS_ID)),
                Arguments.of("OnboardingTicketRequested", "RequestOnboardingTicket", "{}"),
                Arguments.of("EmailVerificationCodeRequested", "SendVerificationCode", "{}"),
                Arguments.of("EmailVerificationCodeChecked", "CheckVerificationCode", "{}"),
                Arguments.of("EntitlementChecked", "CheckEntitlement", ENTITLEMENT_DETAILS),
                Arguments.of("RequestDataDisputed", "DisputeRequestData", """
                        {"request_id": "r1", "attestation_type": "doctorid.sd.1", "adapter": "http",
                         "notice": "sent", "duration_ms": 80}"""),
                Arguments.of("SupportIncidentDelivered", "DeliverIncident", "{\"incident_type\": \"technical\"}"));
    }

    @Nested
    @DisplayName("Event types of the issuance portal")
    class EventTypes {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.eudistack.audit.domain.catalogue.IssuancePortalCatalogueTest#portalEvents")
        void validate_successfulPortalEvent_isValid(String type, String action, String details) {
            // Arrange
            ObjectNode event = event(type, action, details);

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        }

        @Test
        void validate_portalEventUnderAPreviousVersion_isUnknownEventType() {
            // Arrange
            ObjectNode event = event("JourneyEnded", "EndJourney", "{\"end_cause\": \"holder\"}")
                    .put("event_version", "2.2.0");

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(result).isInstanceOfSatisfying(ValidationResult.Invalid.class,
                    invalid -> assertThat(invalid.reason()).isEqualTo(RejectionReason.UNKNOWN_EVENT_TYPE));
        }

        @Test
        void validate_personalDataInDetails_isInvalid() {
            // Arrange
            ObjectNode event = event("EmailVerificationCodeChecked", "CheckVerificationCode",
                    "{\"email\": \"holder@example.org\"}");

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(violationsOf(result)).extracting(Violation::field).containsExactly("details.email");
        }
    }

    @Nested
    @DisplayName("EntitlementChecked")
    class EntitlementChecked {

        @ParameterizedTest
        @CsvSource({"NotFound, Denied", "NoRequests, Denied", "NoAvailableRequests, Denied",
                "Unavailable, Failure"})
        void validate_reasonThePortalGives_isValid(String reason, String outcome) {
            // Arrange
            ObjectNode event = event("EntitlementChecked", "CheckEntitlement", ENTITLEMENT_DETAILS)
                    .put("result", outcome).put("reason", reason);

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"NotEntitled", "IdentifierMismatch"})
        void validate_reasonThePortalNeverGives_isInvalid(String reason) {
            // Arrange
            ObjectNode event = event("EntitlementChecked", "CheckEntitlement", ENTITLEMENT_DETAILS)
                    .put("result", "Denied").put("reason", reason);

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(violationsOf(result)).extracting(Violation::field).containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("Fields that may be unknown")
    class OptionalFields {

        @Test
        void validate_failedDisputeWithoutAttestationType_isValid() {
            // Arrange
            ObjectNode event = event("RequestDataDisputed", "DisputeRequestData", """
                    {"request_id": "r1", "adapter": "none", "notice": "failed", "duration_ms": 5}""")
                    .put("result", "Failure").put("reason", "DeliveryFailed");

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        }

        @Test
        void validate_rejectedIssuanceWithoutProcess_isValid() {
            // Arrange
            ObjectNode event = event("PortalIssuanceRequested", "RequestIssuance", "{\"request_id\": \"r1\"}")
                    .put("result", "Denied").put("reason", "Rejected");

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        }

        @Test
        void validate_failedOnboardingWithoutProcess_isInvalid() {
            // Arrange
            ObjectNode event = event("PortalIssuanceRequested", "RequestIssuance", "{\"request_id\": \"r1\"}")
                    .put("result", "Failure").put("reason", "OnboardingFailed");

            // Act
            ValidationResult result = validator.validate(event);

            // Assert
            assertThat(violationsOf(result)).extracting(Violation::field)
                    .containsExactly("details.issuance_process_id");
        }
    }

    private static ObjectNode event(String type, String action, String details) {
        ObjectNode event = TestEvents.MAPPER.createObjectNode()
                .put("event_id", UUID.randomUUID().toString())
                .put("event_type", type)
                .put("event_version", "2.5.0")
                .put("occurred_at", "2026-11-26T10:00:00Z")
                .put("tenant", "sandbox")
                .put("source", "issuance-portal")
                .put("action", action)
                .put("resource", "journey:" + PROCESS_ID)
                .put("result", "Success")
                .put("correlation_id", PROCESS_ID);
        event.putObject("actor").put("kind", "Holder").put("id", "a".repeat(64));
        try {
            event.set("details", TestEvents.MAPPER.readTree(details));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return event;
    }

    private static List<Violation> violationsOf(ValidationResult result) {
        assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
        return ((ValidationResult.Invalid) result).violations();
    }
}
