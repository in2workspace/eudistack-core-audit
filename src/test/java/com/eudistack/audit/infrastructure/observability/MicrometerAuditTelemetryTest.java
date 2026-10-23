package com.eudistack.audit.infrastructure.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spans of design §3.10: identifiers are high-cardinality attributes (spans only, never metric labels),
 * and the attributes known only at the end ({@code audit.status}, {@code audit.archive.*}) reach the span.
 */
class MicrometerAuditTelemetryTest {

    private final List<Observation.Context> stopped = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MicrometerAuditTelemetry telemetry;

    MicrometerAuditTelemetryTest() {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        telemetry = new MicrometerAuditTelemetry(meters, observations);
    }

    @Test
    void span_withResultAttributes_addsThemWhenTheWorkEnds() {
        String result = telemetry.span("audit.event.persist", Map.of("audit.tenant", "sandbox"), () -> "accepted",
                status -> Map.of("audit.status", status));

        assertThat(result).isEqualTo("accepted");
        assertThat(stopped).singleElement().satisfies(context -> {
            assertThat(context.getName()).isEqualTo("audit.event.persist");
            assertThat(context.getHighCardinalityKeyValues()).extracting(KeyValue::getKey)
                    .containsExactlyInAnyOrder("audit.tenant", "audit.status");
            assertThat(context.getLowCardinalityKeyValues()).isEmpty();
        });
    }

    @Test
    void span_failing_recordsTheErrorAndStops() {
        assertThatThrownBy(() -> telemetry.span("audit.archive.tenant", Map.of(), () -> {
            throw new IllegalStateException("boom");
        }, ignored -> Map.of("audit.archive.result", "success"))).hasMessage("boom");

        assertThat(stopped).singleElement().satisfies(context -> {
            assertThat(context.getError()).hasMessage("boom");
            assertThat(context.getHighCardinalityKeyValues()).isEmpty();
        });
    }

    @Test
    void metrics_carryOnlyTheLabelsOfTheDesign() {
        telemetry.eventRecorded("rejected", "invalid_event");
        telemetry.batchRecorded("ok", 1_000_000);

        assertThat(meters.get("audit.events").tag("status", "rejected").tag("error", "invalid_event").counter()
                .count()).isEqualTo(1.0);
        assertThat(meters.get("audit.batch.latency").tag("outcome", "ok").timer().count()).isEqualTo(1);
    }

    @Test
    void archiveLag_isTheLargestOpenDelayOfEachScope() {
        telemetry.archiveLag("red-logistica", false, 90);
        telemetry.archiveLag("sandbox", false, 10);

        assertThat(meters.get("audit.archive.lag").tag("scope", "tenant").gauge().value()).isEqualTo(90);
        assertThat(meters.get("audit.archive.lag").tag("scope", "platform").gauge().value()).isZero();
    }
}
