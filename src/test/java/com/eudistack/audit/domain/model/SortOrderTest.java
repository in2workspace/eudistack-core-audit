package com.eudistack.audit.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SortOrder")
class SortOrderTest {

    @Test
    @DisplayName("fromWire_asc_isAscending")
    void fromWire_asc_isAscending() {
        // Arrange
        String value = "asc";

        // Act
        var order = SortOrder.fromWire(value);

        // Assert
        assertThat(order).contains(SortOrder.ASC);
    }

    @Test
    @DisplayName("fromWire_desc_isDescending")
    void fromWire_desc_isDescending() {
        // Arrange
        String value = "desc";

        // Act
        var order = SortOrder.fromWire(value);

        // Assert
        assertThat(order).contains(SortOrder.DESC);
    }

    @Test
    @DisplayName("fromWire_anythingElse_isEmpty")
    void fromWire_anythingElse_isEmpty() {
        // Arrange
        String[] values = {"DESC", "Asc", "newest", "", null};

        // Act
        var parsed = java.util.Arrays.stream(values).map(SortOrder::fromWire).toList();

        // Assert
        assertThat(parsed).allSatisfy(order -> assertThat(order).isEmpty());
    }

    @Test
    @DisplayName("wireValue_roundTripsThroughFromWire")
    void wireValue_roundTripsThroughFromWire() {
        // Arrange
        SortOrder[] orders = SortOrder.values();

        // Act
        var parsed = java.util.Arrays.stream(orders).map(o -> SortOrder.fromWire(o.wireValue()).orElseThrow()).toList();

        // Assert
        assertThat(parsed).containsExactly(orders);
    }
}
