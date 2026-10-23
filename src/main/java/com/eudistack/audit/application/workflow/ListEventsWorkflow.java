package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.catalogue.EventTypeSpec;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.PathNotSearchableException;
import com.eudistack.audit.domain.exception.PeriodArchivedException;
import com.eudistack.audit.domain.exception.UnknownEventTypeException;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.CursorPosition;
import com.eudistack.audit.domain.model.EventPage;
import com.eudistack.audit.domain.model.QuerySpec;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.Result;
import com.eudistack.audit.domain.model.StoredEvent;
import com.eudistack.audit.domain.policy.CanonicalJson;
import com.eudistack.audit.domain.policy.CursorCodec;
import com.eudistack.audit.domain.policy.QueryWindowPolicy;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.in.ListEventsPort;
import com.eudistack.audit.domain.port.in.ListRequest;
import com.eudistack.audit.domain.port.in.QueryContext;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.CatalogueResolverPort;
import com.eudistack.audit.domain.port.out.EventQueryPort;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Listing of events (FR-12, FR-13, FR-15, FR-17; design §3.4.5).
 *
 * <p>Order of checks: scope (403, recorded), request form (400), period (409), then the query. The
 * {@code AuditTrailQueried} of a granted listing is committed before the page is returned.
 */
public final class ListEventsWorkflow implements ListEventsPort {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;
    private static final String DETAILS_VALUE = "details_value";
    private static final Pattern DETAILS_PATH = Pattern.compile("^[a-z_]++(?:\\.[a-z_]++)*+$");
    private static final Pattern RFC3339_DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[Tt]");

    private final QueryAccessGuard guard;
    private final EventQueryPort queries;
    private final CatalogueResolverPort catalogue;
    private final CursorCodec cursors;
    private final AuditTelemetryPort telemetry;
    private final Clock clock;

    public ListEventsWorkflow(QueryAccessGuard guard, EventQueryPort queries, CatalogueResolverPort catalogue,
                              CursorCodec cursors, AuditTelemetryPort telemetry, Clock clock) {
        this.guard = guard;
        this.queries = queries;
        this.catalogue = catalogue;
        this.cursors = cursors;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    public EventPage list(QueryContext context, ListRequest request) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            ReadScope scope = guard.authorize(context, request.tenant(), SelfEventFactory.OPERATION_LIST,
                    request.receivedFilters());
            Validated validated = validate(request, scope);
            EventPage page = telemetry.span("audit.query.list",
                    Map.of("audit.tenant", scope.auditTarget().tenantId(), "audit.scope", scope.kind()),
                    () -> query(scope, validated));
            guard.recordGranted(context, scope, SelfEventFactory.OPERATION_LIST, request.receivedFilters(),
                    page.events().size());
            outcome = "ok";
            return page;
        } finally {
            telemetry.queryServed("list", outcome, System.nanoTime() - start);
        }
    }

    private EventPage query(ReadScope scope, Validated validated) {
        QuerySpec spec = validated.spec();
        List<StoredEvent> rows = queries.find(scope, spec);
        boolean more = rows.size() > spec.limit();
        List<StoredEvent> page = more ? rows.subList(0, spec.limit()) : rows;
        String next = null;
        if (more) {
            CursorPosition last = page.getLast().position();
            CursorPosition position = scope instanceof ReadScope.AllTenants ? last
                    : new CursorPosition(last.occurredAt(), last.eventId(), null);
            next = cursors.encode(new CursorCodec.CursorState(scope.label(), validated.filtersHash(),
                    spec.from(), spec.to(), position));
        }
        List<AuditEvent> events = page.stream().map(StoredEvent::event).toList();
        return new EventPage(events, next);
    }

    private Validated validate(ListRequest request, ReadScope scope) {
        int limit = parseLimit(request.limit());
        maxLength("correlation_id", request.correlationId(), 100);
        maxLength("resource", request.resource(), 200);
        maxLength("actor_id", request.actorId(), 200);
        maxLength("details_path", request.detailsPath(), 100);
        maxLength(DETAILS_VALUE, request.detailsValue(), 200);
        maxLength("organization", request.organization(), 100);
        maxLength("tenant", request.tenant(), 100);
        Instant from = parseInstant("from", request.from());
        Instant to = parseInstant("to", request.to());
        List<Result> results = parseResults(request.results());
        List<EventTypeSpec> types = resolveTypes(request.eventTypes());
        ObjectNode containment = containment(request, types);

        String organization = scope instanceof ReadScope.SingleTenant single && single.organization() != null
                ? single.organization() : request.organization();
        List<String> typeNames = types.stream().map(EventTypeSpec::name).toList();
        String filtersHash = filtersHash(typeNames, results, request, organization);

        Instant now = clock.instant();
        QueryWindowPolicy.Window window;
        CursorPosition after = null;
        if (request.cursor() != null) {
            CursorCodec.CursorState state = cursors.decode(request.cursor());
            if (!state.scope().equals(scope.label()) || !state.filtersHash().equals(filtersHash)
                    || (from != null && !from.equals(state.from())) || (to != null && !to.equals(state.to()))) {
                throw new BadRequestException("cursor", "The cursor does not belong to this query");
            }
            if (state.from().isBefore(QueryWindowPolicy.oldestOnline(now))) {
                throw new PeriodArchivedException(QueryWindowPolicy.oldestOnline(now));
            }
            window = new QueryWindowPolicy.Window(state.from(), state.to());
            after = state.position();
        } else {
            window = QueryWindowPolicy.resolve(from, to, now);
        }
        QuerySpec spec = new QuerySpec(window.from(), window.to(), typeNames, results, request.correlationId(),
                request.resource(), request.actorId(), organization, containment, limit, after);
        return new Validated(spec, filtersHash);
    }

    private ObjectNode containment(ListRequest request, List<EventTypeSpec> types) {
        if (request.detailsPath() == null) {
            if (request.detailsValue() != null) {
                throw new BadRequestException(DETAILS_VALUE, "details_value requires details_path");
            }
            return null;
        }
        List<String> missing = new ArrayList<>();
        if (request.detailsValue() == null) {
            missing.add(DETAILS_VALUE);
        }
        if (types.isEmpty()) {
            missing.add("event_type");
        }
        if (request.from() == null) {
            missing.add("from");
        }
        if (!missing.isEmpty()) {
            throw new BadRequestException("details_path", "details_path requires " + String.join(", ", missing));
        }
        if (!DETAILS_PATH.matcher(request.detailsPath()).matches()) {
            throw new BadRequestException("details_path", "details_path must be a dot path of lower-case names");
        }
        for (EventTypeSpec type : types) {
            if (!type.isSearchable(request.detailsPath())) {
                throw new PathNotSearchableException(request.detailsPath() + " is not searchable for " + type.name());
            }
        }
        String[] segments = request.detailsPath().split("\\.");
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            current = current.putObject(segments[i]);
        }
        current.put(segments[segments.length - 1], request.detailsValue());
        return root;
    }

    private List<EventTypeSpec> resolveTypes(List<String> names) {
        List<EventTypeSpec> types = new ArrayList<>();
        for (String name : new TreeSet<>(names)) {
            Optional<EventTypeSpec> spec = catalogue.latestSpecOf(name);
            if (spec.isEmpty()) {
                throw new UnknownEventTypeException(name + " is not in the catalogue");
            }
            types.add(spec.get());
        }
        return types;
    }

    private static List<Result> parseResults(List<String> values) {
        List<Result> results = new ArrayList<>();
        for (String value : new TreeSet<>(values)) {
            results.add(Result.fromWire(value).orElseThrow(() ->
                    new BadRequestException("result", "result must be Success, Denied or Failure")));
        }
        return results;
    }

    private static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(raw);
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException _) {
            // falls through to the 400
        }
        throw new BadRequestException("limit", "limit must be an integer between 1 and " + MAX_LIMIT);
    }

    private static Instant parseInstant(String field, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (!RFC3339_DATE_PREFIX.matcher(raw).find()) {
                throw new DateTimeParseException("Year must have four digits", raw, 0);
            }
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException _) {
            throw new BadRequestException(field, field + " must be an RFC 3339 date-time");
        }
    }

    private static void maxLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new BadRequestException(field, field + " must not exceed " + max + " characters");
        }
    }

    private static String filtersHash(List<String> types, List<Result> results, ListRequest request,
                                      String organization) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ArrayNode typeArray = node.putArray("event_type");
        types.forEach(typeArray::add);
        ArrayNode resultArray = node.putArray("result");
        results.forEach(r -> resultArray.add(r.wireValue()));
        putIfPresent(node, "correlation_id", request.correlationId());
        putIfPresent(node, "resource", request.resource());
        putIfPresent(node, "actor_id", request.actorId());
        putIfPresent(node, "details_path", request.detailsPath());
        putIfPresent(node, DETAILS_VALUE, request.detailsValue());
        putIfPresent(node, "organization", organization);
        putIfPresent(node, "limit", request.limit());
        return CanonicalJson.hex(CanonicalJson.sha256(node));
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private record Validated(QuerySpec spec, String filtersHash) {
    }
}
