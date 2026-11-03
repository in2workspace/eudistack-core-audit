package com.eudistack.audit.infrastructure.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingStructuredLogEncoderTest {

    @Test
    void encode_MessageWithEmail_WritesTheJsonLineMasked() {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), new MockEnvironment());
        MaskingStructuredLogEncoder encoder = new MaskingStructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.start();
        LoggingEvent event = new LoggingEvent(MaskingStructuredLogEncoderTest.class.getName(),
                context.getLogger("test-logger"), Level.INFO, "Notifying holder@empresa.com", null, null);
        event.setMDCPropertyMap(Map.of("contact", "other@empresa.com"));

        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(line)
                .contains("Notifying " + MaskingPatternLayout.REPLACEMENT)
                .doesNotContain("holder@empresa.com")
                .doesNotContain("other@empresa.com");
    }
}
