package com.eudistack.audit.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Metrics and spans of design §3.10 with Micrometer: the Observation API opens the spans (exported by
 * {@code micrometer-tracing-bridge-otel} over OTLP) and the meters go out through the OTLP registry.
 * Tenant, correlation and event identifiers are span attributes only, never metric labels.
 */
public final class MicrometerAuditTelemetry implements AuditTelemetryPort {

    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final Map<String, Long> archiveLagByTenant = new ConcurrentHashMap<>();

    public MicrometerAuditTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
        Gauge.builder("audit.archive.lag", archiveLagByTenant, m -> maxLag(m, false))
                .tag("scope", "tenant").baseUnit("seconds").register(meters);
        Gauge.builder("audit.archive.lag", archiveLagByTenant, m -> maxLag(m, true))
                .tag("scope", "platform").baseUnit("seconds").register(meters);
    }

    @Override
    public <T> T span(String name, Map<String, String> attributes, Supplier<T> work) {
        Observation observation = Observation.createNotStarted(name, observations);
        attributes.forEach(observation::highCardinalityKeyValue);
        return observation.observe(work);
    }

    @Override
    public void batchRecorded(String outcome, long nanos) {
        Timer.builder("audit.batch.latency").tag("outcome", outcome).publishPercentiles(0.95)
                .register(meters).record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void eventRecorded(String status, String error) {
        Counter.builder("audit.events").tag("status", status).tag("error", error).register(meters).increment();
    }

    @Override
    public void catalogueValidated(boolean valid, long nanos) {
        Timer.builder("audit.catalog.validation").tag("result", valid ? "valid" : "invalid")
                .register(meters).record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void queryServed(String operation, String outcome, long nanos) {
        Timer.builder("audit.query.latency").tag("operation", operation).tag("outcome", outcome)
                .register(meters).record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void rateLimited(boolean system, boolean search) {
        Counter.builder("audit.ratelimit.rejected").tag("kind", system ? "system" : "person")
                .tag("search", Boolean.toString(search)).register(meters).increment();
    }

    @Override
    public void writeDenied(String reason) {
        Counter.builder("audit.write.denied").tag("reason", reason).register(meters).increment();
    }

    @Override
    public void archiveRun(boolean success) {
        Counter.builder("audit.archive.runs").tag("result", success ? "success" : "failure")
                .register(meters).increment();
    }

    @Override
    public void archiveLag(String tenantId, boolean platform, long seconds) {
        archiveLagByTenant.put((platform ? "platform:" : "tenant:") + tenantId, seconds);
    }

    private static double maxLag(Map<String, Long> lags, boolean platform) {
        String prefix = platform ? "platform:" : "tenant:";
        return lags.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .mapToLong(Map.Entry::getValue).max().orElse(0);
    }
}
