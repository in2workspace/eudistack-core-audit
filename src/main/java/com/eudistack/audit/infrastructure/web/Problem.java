package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.eudistack.audit.domain.model.Violation;

import java.util.List;

/**
 * Problem details in the shape of the OpenAPI {@code Problem} (RFC 9457). {@code type} is an error
 * code of the contract, not a URI. Never carries a stack trace, SQL or the token.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Problem(String type, String title, int status, String detail, String instance,
                      List<ViolationBody> violations) {

    public static final String MEDIA_TYPE = "application/problem+json";

    public record ViolationBody(String field, String message) {
    }

    public static List<ViolationBody> of(List<Violation> violations) {
        return violations == null ? List.of() : violations.stream()
                .map(v -> new ViolationBody(v.field(), v.message())).toList();
    }
}
