package com.eudistack.audit.domain.model;

/**
 * A field-level violation. {@code field} is the dotted path of the field ({@code details.holder_phone}).
 */
public record Violation(String field, String message) {
}
