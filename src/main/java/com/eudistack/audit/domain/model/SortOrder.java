package com.eudistack.audit.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Direction of the listing order {@code (occurred_at, event_id)}: the {@code order} query parameter.
 */
public enum SortOrder {
    ASC("asc"),
    DESC("desc");

    private final String wireValue;

    SortOrder(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<SortOrder> fromWire(String value) {
        return Arrays.stream(values()).filter(o -> o.wireValue.equals(value)).findFirst();
    }
}
