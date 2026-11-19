package com.eudistack.audit.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SortOrder")
class SortOrderTest {

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource({"asc, ASC", "desc, DESC"})
    @DisplayName("fromWire_knownValue_isItsOrder")
    void fromWire_knownValue_isItsOrder(String value, SortOrder expected) {
        // Arrange + Act
        var order = SortOrder.fromWire(value);

        // Assert
        assertThat(order).contains(expected);
    }

    @ParameterizedTest(name = "\"{0}\" is not an order")
    @NullAndEmptySource
    @ValueSource(strings = {"DESC", "Asc", "newest"})
    @DisplayName("fromWire_anythingElse_isEmpty")
    void fromWire_anythingElse_isEmpty(String value) {
        // Arrange + Act
        var order = SortOrder.fromWire(value);

        // Assert
        assertThat(order).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SortOrder.class)
    @DisplayName("wireValue_roundTripsThroughFromWire")
    void wireValue_roundTripsThroughFromWire(SortOrder order) {
        // Arrange + Act
        var parsed = SortOrder.fromWire(order.wireValue());

        // Assert
        assertThat(parsed).contains(order);
    }
}
