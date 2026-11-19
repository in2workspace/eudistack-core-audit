package com.eudistack.audit.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceRequirementTest {

    @Test
    void writerAndReaderRequireAnAudience() {
        List<String> blank = List.of(" ");
        List<String> configured = List.of("https://audit.example");

        assertThatThrownBy(() -> CoreConfiguration.requireAudience(AuditRole.WRITER, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit.security.audience");
        assertThatThrownBy(() -> CoreConfiguration.requireAudience(AuditRole.READER, blank))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> CoreConfiguration.requireAudience(AuditRole.WRITER, configured))
                .doesNotThrowAnyException();
        assertThatCode(() -> CoreConfiguration.requireAudience(AuditRole.ALL, null)).doesNotThrowAnyException();
        assertThatCode(() -> CoreConfiguration.requireAudience(AuditRole.ARCHIVER, null)).doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("audience as a list")
    class AudienceAsAList {

        @Test
        @DisplayName("requireAudience_readerWithAnEmptyList_isRefused")
        void requireAudience_readerWithAnEmptyList_isRefused() {
            // Arrange
            List<String> audience = List.of();

            // Act + Assert
            assertThatThrownBy(() -> CoreConfiguration.requireAudience(AuditRole.READER, audience))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("audit.security.audience");
        }

        @Test
        @DisplayName("requireAudience_writerWithOnlyBlankValues_isRefused")
        void requireAudience_writerWithOnlyBlankValues_isRefused() {
            // Arrange
            List<String> audience = List.of("", " ");

            // Act + Assert
            assertThatThrownBy(() -> CoreConfiguration.requireAudience(AuditRole.WRITER, audience))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("requireAudience_readerWithATenantMarkerValue_isAccepted")
        void requireAudience_readerWithATenantMarkerValue_isAccepted() {
            // Arrange
            List<String> audience = List.of("https://audit.example", "vc-auth-client-{tenant}");

            // Act + Assert
            assertThatCode(() -> CoreConfiguration.requireAudience(AuditRole.READER, audience))
                    .doesNotThrowAnyException();
        }
    }
}
