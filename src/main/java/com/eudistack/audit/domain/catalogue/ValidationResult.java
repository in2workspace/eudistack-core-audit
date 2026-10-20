package com.eudistack.audit.domain.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.model.Violation;

import java.util.List;

/**
 * Outcome of validating one received event against the catalogue.
 */
public sealed interface ValidationResult {

    /**
     * @param event    the event, built from the received JSON
     * @param received the received JSON, untouched (it is what the payload hash covers)
     */
    record Valid(AuditEvent event, JsonNode received) implements ValidationResult {
    }

    record Invalid(RejectionReason reason, List<Violation> violations) implements ValidationResult {

        public Invalid {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }
}
