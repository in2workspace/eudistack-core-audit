package com.eudistack.audit.infrastructure.archive;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.eudistack.audit.application.workflow.ArchiveEventsWorkflow;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.ArchiveRepositoryPort;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.support.InMemoryColdStorage;
import com.eudistack.audit.support.InMemoryEventStore;
import com.eudistack.audit.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The start and finish lines are emitted by the workflow, inside {@code audit.archive.run}. Logging
 * them after {@code run} returns would leave the finish line without that trace.
 */
class ArchiveWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");

    @Test
    void runOnce_logsStartAndFinishInsideTheArchiveSpan() {
        AtomicBoolean spanOpen = new AtomicBoolean();
        AtomicBoolean finishedWhileOpen = new AtomicBoolean();
        TurboFilter witness = witness(spanOpen, finishedWhileOpen);
        ArchiveWorker worker = worker(new EmptyRepository(), new InMemoryColdStorage(clock()),
                tracking(spanOpen));
        try (LogCapture capture = LogCapture.attach(ArchiveWorker.class.getName())) {
            assertThat(worker.runOnce()).isTrue();
            assertThat(finishedWhileOpen).isTrue();
            assertThat(capture.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getMessage()).isEqualTo("Archiving run started");
                assertThat(LogCapture.value(event, "cutoffDay")).isEqualTo("2026-08-17");
                assertThat(LogCapture.value(event, "scopes")).isEqualTo("1");
                assertThat(event.getKeyValuePairs()).noneMatch(pair -> "tenant".equals(pair.key));
            });
            assertThat(capture.events()).anySatisfy(event -> {
                assertThat(event.getMessage()).isEqualTo("Archiving run finished");
                assertThat(LogCapture.value(event, "tenantDays")).isEqualTo("1");
                assertThat(LogCapture.value(event, "failures")).isEqualTo("0");
                assertThat(LogCapture.value(event, "result")).isEqualTo("ok");
                assertThat(Long.parseLong(LogCapture.value(event, "durationMs"))).isGreaterThanOrEqualTo(0);
            });
        } finally {
            context().resetTurboFilterList();
        }
    }

    @Test
    void runOnce_countsAFailedDayAsWithFailures() {
        InMemoryColdStorage cold = new InMemoryColdStorage(clock());
        cold.failNextPutEndingWith("manifest.json");
        try (LogCapture capture = LogCapture.attach(ArchiveWorker.class.getName())) {
            assertThat(worker(new EmptyRepository(), cold, AuditTelemetryPort.noop()).runOnce()).isFalse();
            assertThat(capture.events()).anySatisfy(event -> {
                assertThat(event.getMessage()).isEqualTo("Archiving run finished");
                assertThat(LogCapture.value(event, "result")).isEqualTo("with_failures");
                assertThat(LogCapture.value(event, "failures")).isEqualTo("1");
            });
        }
    }

    @Test
    void runOnce_logsTheExceptionWhenTheRunAborts() {
        ArchiveRepositoryPort failing = new EmptyRepository() {
            @Override
            public List<LocalDate> daysWithEventsBefore(TenantRef tenant, Instant cutoff) {
                throw new IllegalStateException("store unavailable");
            }
        };
        try (LogCapture capture = LogCapture.attach(ArchiveWorker.class.getName())) {
            assertThat(worker(failing, new InMemoryColdStorage(clock()), AuditTelemetryPort.noop()).runOnce())
                    .isFalse();
            assertThat(capture.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("store unavailable");
            });
        }
    }

    private static TurboFilter witness(AtomicBoolean spanOpen, AtomicBoolean finishedWhileOpen) {
        TurboFilter filter = new TurboFilter() {
            @Override
            public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level,
                    String format, Object[] params, Throwable throwable) {
                if (spanOpen.get() && "Archiving run finished".equals(format)) {
                    finishedWhileOpen.set(true);
                }
                return FilterReply.NEUTRAL;
            }
        };
        filter.start();
        context().addTurboFilter(filter);
        return filter;
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static ArchiveWorker worker(ArchiveRepositoryPort repository, InMemoryColdStorage cold,
            AuditTelemetryPort telemetry) {
        ArchiveEventsWorkflow workflow = new ArchiveEventsWorkflow(new InMemoryEventStore(), repository, cold,
                new SelfEventFactory(clock(), "2.2.0"), telemetry, clock());
        return new ArchiveWorker(workflow);
    }

    /** The archive span is open exactly while its work, including the operational lines, runs. */
    private static AuditTelemetryPort tracking(AtomicBoolean spanOpen) {
        return new AuditTelemetryPort() {
            @Override
            public <T> T span(String name, Map<String, String> attributes, Supplier<T> work) {
                if (!"audit.archive.run".equals(name)) {
                    return work.get();
                }
                spanOpen.set(true);
                try {
                    return work.get();
                } finally {
                    spanOpen.set(false);
                }
            }

            @Override
            public void batchRecorded(String outcome, long nanos) {
            }

            @Override
            public void eventRecorded(String status, String error) {
            }

            @Override
            public void catalogueValidated(boolean valid, long nanos) {
            }

            @Override
            public void queryServed(String operation, String outcome, long nanos) {
            }

            @Override
            public void rateLimited(boolean system, boolean search) {
            }

            @Override
            public void writeDenied(String reason) {
            }

            @Override
            public void archiveRun(boolean success) {
            }

            @Override
            public void archiveLag(String tenantId, boolean platform, long seconds) {
            }
        };
    }

    private static class EmptyRepository implements ArchiveRepositoryPort {
        @Override
        public List<LocalDate> daysWithEventsBefore(TenantRef tenant, Instant cutoff) {
            return List.of();
        }

        @Override
        public List<AuditEvent> eventsRecordedOn(TenantRef tenant, LocalDate day) {
            return List.of();
        }

        @Override
        public Optional<Instant> oldestRecordedAt(TenantRef tenant) {
            return Optional.empty();
        }

        @Override
        public boolean tryLock(String tenantId, LocalDate day, String owner, Instant staleBefore) {
            return true;
        }

        @Override
        public void unlock(String tenantId, LocalDate day, String owner) {
        }

        @Override
        public void deleteArchived(TenantRef tenant, Collection<UUID> eventIds, AuditEvent archivedRecord) {
        }

        @Override
        public void recordOutcome(AuditEvent archivedRecord) {
        }
    }
}
