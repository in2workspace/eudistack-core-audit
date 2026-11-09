package com.eudistack.audit.infrastructure.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

/**
 * Writes the JSON console line from the masked event, with the same PII/secret redaction as the
 * OTLP export. The console is structured here, so {@link MaskingPatternLayout} never renders it.
 */
public class MaskingStructuredLogEncoder extends StructuredLogEncoder {

    @Override
    public byte[] encode(ILoggingEvent event) {
        return super.encode(MaskingOpenTelemetryAppender.mask(event));
    }
}
