package com.eudistack.audit.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Outcome of the audited action, as defined by {@code result_semantics} in the catalogue.
 */
public enum Result {
    SUCCESS("Success"),
    DENIED("Denied"),
    FAILURE("Failure");

    private final String wireValue;

    Result(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<Result> fromWire(String value) {
        return Arrays.stream(values()).filter(r -> r.wireValue.equals(value)).findFirst();
    }
}
