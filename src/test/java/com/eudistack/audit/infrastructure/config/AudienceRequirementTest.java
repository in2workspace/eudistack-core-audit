package com.eudistack.audit.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceRequirementTest {

    @Test
    void writerAndReaderRequireAnAudience() {
        assertThatThrownBy(() -> CoreConfiguration.requireAudience(AuditRole.WRITER, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit.security.audience");
        assertThatThrownBy(() -> CoreConfiguration.requireAudience(AuditRole.READER, " "))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> CoreConfiguration.requireAudience(AuditRole.WRITER, "https://audit.example"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CoreConfiguration.requireAudience(AuditRole.ALL, null)).doesNotThrowAnyException();
        assertThatCode(() -> CoreConfiguration.requireAudience(AuditRole.ARCHIVER, null)).doesNotThrowAnyException();
    }
}
