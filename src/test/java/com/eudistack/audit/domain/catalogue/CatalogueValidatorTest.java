package com.eudistack.audit.domain.catalogue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.model.Violation;
import com.eudistack.audit.support.TestCatalogue;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueValidatorTest {

    private final CatalogueValidator validator = new CatalogueValidator(TestCatalogue.withFixtures());

    private static ObjectNode issuanceCompleted() {
        return TestEvents.issuanceCompleted("red-logistica", "c-1", "12345678Z");
    }

    private List<Violation> violationsOf(ObjectNode event) {
        ValidationResult result = validator.validate(event);
        assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
        ValidationResult.Invalid invalid = (ValidationResult.Invalid) result;
        assertThat(invalid.reason()).isEqualTo(RejectionReason.INVALID_EVENT);
        return invalid.violations();
    }

    @Test
    void validate_validEvent_isValid() {
        assertThat(validator.validate(issuanceCompleted())).isInstanceOf(ValidationResult.Valid.class);
    }

    @Test
    void validate_unknownType_isUnknownEventType() {
        ObjectNode event = issuanceCompleted().put("event_type", "IssuanceExploded");

        ValidationResult result = validator.validate(event);

        assertThat(result).isInstanceOfSatisfying(ValidationResult.Invalid.class,
                invalid -> assertThat(invalid.reason()).isEqualTo(RejectionReason.UNKNOWN_EVENT_TYPE));
    }

    @Test
    void validate_unpublishedVersion_isUnknownEventType() {
        ObjectNode event = issuanceCompleted().put("event_version", "9.0.0");

        assertThat(validator.validate(event)).isInstanceOfSatisfying(ValidationResult.Invalid.class,
                invalid -> assertThat(invalid.reason()).isEqualTo(RejectionReason.UNKNOWN_EVENT_TYPE));
    }

    @Test
    void validate_undeclaredDetailsField_isInvalidWithItsPath() {
        ObjectNode event = issuanceCompleted();
        ((ObjectNode) event.get("details")).put("holder_phone", "+34600000000");

        assertThat(violationsOf(event)).containsExactly(
                new Violation("details.holder_phone", "Field not declared by IssuanceCompleted 2.2.0"));
    }

    @Test
    void validate_recordedAtSent_isInvalid() {
        ObjectNode event = issuanceCompleted().put("recorded_at", "2026-10-01T00:00:00Z");

        assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("recorded_at");
    }

    @Test
    void validate_actionOfAnotherType_isInvalid() {
        ObjectNode event = issuanceCompleted().put("action", "Revoke");

        assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("action");
    }

    @Test
    void validate_resultNotAllowedByType_isInvalid() {
        ObjectNode event = issuanceCompleted().put("result", "Failure").put("reason", "SigningFailed");

        assertThat(violationsOf(event)).extracting(Violation::field).contains("result");
    }

    @Test
    void validate_reasonOutOfVocabulary_isInvalid() {
        ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-2", "1Z")
                .put("result", "Denied").put("reason", "TokenStolen");

        assertThat(violationsOf(event)).extracting(Violation::field).contains("reason");
    }

    @Test
    void validate_reasonOfAnotherResult_isInvalid() {
        ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-2", "1Z")
                .put("result", "Denied").put("reason", "RequestExpired");

        assertThat(violationsOf(event)).extracting(Violation::message)
                .contains("RequestExpired determines the result Failure");
    }

    @Test
    void validate_reasonWithSuccess_isInvalid() {
        ObjectNode event = issuanceCompleted().put("reason", "PolicyDenied");

        assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("reason");
    }

    @Test
    void validate_unknownActorField_isInvalid() {
        ObjectNode event = issuanceCompleted();
        ((ObjectNode) event.get("actor")).put("token", "eyJ...");

        assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("actor.token");
    }

    @Test
    void validate_nulCharacter_isInvalidWithItsPath() {
        ObjectNode event = issuanceCompleted();
        ((ObjectNode) event.get("actor")).put("id", "a\u0000b");

        assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("actor.id");
    }

    @Test
    void validate_yearOutsideRfc3339_isInvalid() {
        // OffsetDateTime parses "+10000-…", which PostgreSQL cannot store as timestamptz.
        ObjectNode event = issuanceCompleted().put("occurred_at", "+10000-01-01T00:00:00Z");

        assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("occurred_at");
    }

    @Nested
    class RequiredConditions {

        @Test
        void validate_evaluableConditionHolds_requiresField() {
            ObjectNode event = issuanceCompleted();
            ((ObjectNode) event.get("details")).remove("accepted_by_holder");

            assertThat(violationsOf(event)).extracting(Violation::field)
                    .containsExactly("details.accepted_by_holder");
        }

        @Test
        void validate_evaluableConditionDoesNotHold_fieldOptional() {
            ObjectNode event = issuanceCompleted();
            ObjectNode details = (ObjectNode) event.get("details");
            details.put("delivery_method", "direct");
            details.remove("accepted_by_holder");
            details.remove("wallet_interaction");

            assertThat(validator.validate(event)).isInstanceOf(ValidationResult.Valid.class);
        }

        @Test
        void validate_naturalLanguageCondition_fieldOptional() {
            ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-3", "1Z");
            ObjectNode details = (ObjectNode) event.get("details");
            details.remove("presented_attestation_type");
            details.remove("attestation_issuer");

            assertThat(validator.validate(event)).isInstanceOf(ValidationResult.Valid.class);
        }

        @Test
        void validate_resultDependentField_requiredOnlyOnSuccess() {
            ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-4", "1Z");
            ((ObjectNode) event.get("details")).remove("holder");

            assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("details.holder");

            event.put("result", "Denied").put("reason", "HolderDeclined");
            assertThat(validator.validate(event)).isInstanceOf(ValidationResult.Valid.class);
        }
    }

    /**
     * AC-18: events of several catalogue versions are accepted at the same time, each validated with
     * its own version.
     */
    @Nested
    class SeveralVersions {

        private ObjectNode issuanceRequested(String version, boolean onBehalf) {
            ObjectNode event = issuanceCompleted();
            event.put("event_type", "IssuanceRequested").put("event_version", version);
            ObjectNode details = event.putObject("details");
            details.put("attestation_type", "employee.sd.1");
            details.put("issuance_mode", "ThirdParty");
            details.put("delivery_method", "direct");
            details.putObject("holder").put("identifier", "12345678Z");
            if (onBehalf) {
                details.put("on_behalf_of_organization", true);
            }
            return event;
        }

        @Test
        void validate_eventOfOlderVersion_acceptedWithItsVersion() {
            assertThat(validator.validate(issuanceRequested("2.0.0", false))).isInstanceOf(ValidationResult.Valid.class);
            assertThat(validator.validate(issuanceRequested("2.1.0", true))).isInstanceOf(ValidationResult.Valid.class);
        }

        @Test
        void validate_fieldAddedLater_rejectedUnderTheOlderVersion() {
            assertThat(violationsOf(issuanceRequested("2.0.0", true))).containsExactly(new Violation(
                    "details.on_behalf_of_organization", "Field not declared by IssuanceRequested 2.0.0"));
        }

        @Test
        void validate_typeAbsentFromOlderVersion_isUnknownEventType() {
            ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-5", "1Z")
                    .put("event_version", "2.0.0");

            assertThat(validator.validate(event)).isInstanceOfSatisfying(ValidationResult.Invalid.class,
                    invalid -> assertThat(invalid.reason()).isEqualTo(RejectionReason.UNKNOWN_EVENT_TYPE));
        }
    }

    /**
     * AC-17: the catalogue is an allow-list, so a verification event cannot carry the presented
     * attestation, a token or a secret.
     */
    @Nested
    class NoSecretsNorAttestation {

        @Test
        void validate_presentedAttestationInDetails_isRejected() {
            ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-6", "12345678Z");
            ((ObjectNode) event.get("details")).put("vp_token", "eyJhbGciOi...");

            assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("details.vp_token");
        }

        @Test
        void validate_holderInClear_isAccepted() {
            ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-6", "12345678Z");

            assertThat(validator.validate(event)).isInstanceOfSatisfying(ValidationResult.Valid.class,
                    valid -> assertThat(valid.event().details().at("/holder/identifier").asText()).isEqualTo("12345678Z"));
        }

        @Test
        void validate_secretInHolder_isRejected() {
            ObjectNode event = TestEvents.presentationVerification("red-logistica", "c-6", "12345678Z");
            ((ObjectNode) event.at("/details/holder")).put("password", "s3cret");

            assertThat(violationsOf(event)).extracting(Violation::field).containsExactly("details.holder.password");
        }
    }

    @Test
    void publishedCatalogue_everyTypeOfThePrd_isDeclared() {
        CatalogueVersion published = TestCatalogue.published().latest();

        assertThat(published.version()).isEqualTo("2.2.0");
        assertThat(published.types()).hasSize(36).containsKeys("IssuanceRequested", "PresentationVerification",
                "EvidenceStoreAccess", "AuditTrailQueried", "AuditWriteDenied", "AuditEventRejected",
                "AuditEventsArchived");
    }

    @Test
    void attributeEvidence_itemCondition_usesTheItemResult() {
        ObjectNode event = issuanceCompleted();
        event.put("event_type", "AttributeVerificationEvidence").put("action", "VerifyAttributes");
        ObjectNode details = event.putObject("details");
        details.putArray("attribute_evidence").addObject()
                .put("attribute", "given_name")
                .put("verification_type", "Documentary")
                .put("evidence_reference", "doc-1")
                .put("result", "Denied");

        assertThat(violationsOf(event)).extracting(Violation::field)
                .containsExactly("details.attribute_evidence[0].reason");
    }
}
