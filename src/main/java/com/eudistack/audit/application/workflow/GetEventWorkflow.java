package com.eudistack.audit.application.workflow;

import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.EventNotFoundException;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.StoredEvent;
import com.eudistack.audit.domain.policy.QueryWindowPolicy;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.in.GetEventPort;
import com.eudistack.audit.domain.port.in.QueryContext;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.EventQueryPort;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One event by its {@code event_id} (FR-14). Absent, out of scope (another tenant or another
 * organization) and out of the online period all answer the same 404 {@code event_not_found} (AC-10).
 */
public final class GetEventWorkflow implements GetEventPort {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final QueryAccessGuard guard;
    private final EventQueryPort queries;
    private final AuditTelemetryPort telemetry;
    private final Clock clock;

    public GetEventWorkflow(QueryAccessGuard guard, EventQueryPort queries, AuditTelemetryPort telemetry,
                            Clock clock) {
        this.guard = guard;
        this.queries = queries;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    public AuditEvent get(QueryContext context, String eventId, String tenant) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            if (eventId == null || !UUID_PATTERN.matcher(eventId).matches()) {
                throw new BadRequestException("event_id", "event_id must be a UUID");
            }
            if (tenant != null && tenant.length() > 100) {
                throw new BadRequestException("tenant", "tenant must not exceed 100 characters");
            }
            ReadScope scope = guard.authorize(context, tenant, SelfEventFactory.OPERATION_GET, null);
            UUID id = UUID.fromString(eventId);
            Optional<StoredEvent> found = telemetry.span("audit.query.get",
                    Map.of("audit.event_id", eventId, "audit.tenant", scope.auditTarget().tenantId()),
                    () -> queries.findById(scope, id, QueryWindowPolicy.oldestOnline(clock.instant())));
            if (found.isEmpty()) {
                guard.recordDenial(context, scope.auditTarget(), SelfEventFactory.OPERATION_GET, scope.label(), null,
                        "EventNotFound");
                outcome = "not_found";
                throw new EventNotFoundException("The event does not exist");
            }
            guard.recordGranted(context, scope, SelfEventFactory.OPERATION_GET, null, 1);
            outcome = "ok";
            return found.get().event();
        } finally {
            telemetry.queryServed("get", outcome, System.nanoTime() - start);
        }
    }
}
