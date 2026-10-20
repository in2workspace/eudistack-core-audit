package com.eudistack.audit.infrastructure.web.controller;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.model.EventPage;
import com.eudistack.audit.domain.policy.NulCharacters;
import com.eudistack.audit.domain.port.in.GetEventPort;
import com.eudistack.audit.domain.port.in.ListEventsPort;
import com.eudistack.audit.domain.port.in.ListRequest;
import com.eudistack.audit.domain.port.in.QueryContext;
import com.eudistack.audit.infrastructure.config.AuditRole;
import com.eudistack.audit.infrastructure.config.ConditionalOnAuditRole;
import com.eudistack.audit.infrastructure.web.ApiRequest;
import com.eudistack.audit.infrastructure.web.TraceIds;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/events} and {@code GET /api/v1/events/{event_id}} (OpenAPI {@code listEvents},
 * {@code getEvent}). Parameters are read raw and validated by the use case, so every malformed value is a
 * contract {@code BAD_REQUEST} and the received filters can be recorded as they came.
 */
@RestController
@ConditionalOnAuditRole({AuditRole.READER, AuditRole.ALL})
public class EventsQueryController {

    private final ListEventsPort listEvents;
    private final GetEventPort getEvent;
    private final TraceIds traceIds;

    public EventsQueryController(ListEventsPort listEvents, GetEventPort getEvent, TraceIds traceIds) {
        this.listEvents = listEvents;
        this.getEvent = getEvent;
        this.traceIds = traceIds;
    }

    @GetMapping(path = {ApiRequest.COLLECTION, ApiRequest.PREFIX + ApiRequest.COLLECTION},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> list(HttpServletRequest request) {
        Map<String, String[]> params = parameters(request);
        ListRequest listRequest = new ListRequest(
                single(params, "from"),
                single(params, "to"),
                multi(params, "event_type"),
                multi(params, "result"),
                single(params, "correlation_id"),
                single(params, "resource"),
                single(params, "actor_id"),
                single(params, "details_path"),
                single(params, "details_value"),
                single(params, "organization"),
                single(params, "tenant"),
                single(params, "limit"),
                single(params, "cursor"),
                receivedFilters(params));
        EventPage page = listEvents.list(context(request), listRequest);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        ArrayNode events = body.putArray("events");
        page.events().forEach(e -> events.add(e.toJson()));
        if (page.nextCursor() == null) {
            body.putNull("next_cursor");
        } else {
            body.put("next_cursor", page.nextCursor());
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping(path = {ApiRequest.COLLECTION + "/{eventId}", ApiRequest.PREFIX + ApiRequest.COLLECTION + "/{eventId}"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> get(HttpServletRequest request, @PathVariable String eventId) {
        String tenant = single(parameters(request), "tenant");
        return ResponseEntity.ok(getEvent.get(context(request), eventId, tenant).toJson());
    }

    private QueryContext context(HttpServletRequest request) {
        return new QueryContext(ApiRequest.caller(request),
                (String) request.getAttribute(ApiRequest.HOST_TENANT), traceIds.current());
    }

    /**
     * Query parameters, refusing U+0000: PostgreSQL cannot bind it nor store it in the
     * {@code AuditTrailQueried} of the query, so it would end in a 503.
     */
    private static Map<String, String[]> parameters(HttpServletRequest request) {
        Map<String, String[]> params = request.getParameterMap();
        params.forEach((name, values) -> {
            if (!name.equals(NulCharacters.strip(name))
                    || Arrays.stream(values).anyMatch(v -> !v.equals(NulCharacters.strip(v)))) {
                throw new BadRequestException(NulCharacters.strip(name), "Parameters must not contain U+0000");
            }
        });
        return params;
    }

    private static String single(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        if (values == null || values.length == 0) {
            return null;
        }
        if (values.length > 1) {
            throw new BadRequestException(name, name + " must be sent once");
        }
        return values[0];
    }

    /**
     * {@code style: form, explode: false}: comma-separated; a repeated parameter is accepted too.
     */
    private static List<String> multi(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).forEach(result::add);
            }
        }
        return result;
    }

    private static ObjectNode receivedFilters(Map<String, String[]> params) {
        ObjectNode filters = JsonNodeFactory.instance.objectNode();
        params.forEach((name, values) -> {
            if ("cursor".equals(name)) {
                return;
            }
            if (values.length == 1) {
                filters.put(name, values[0]);
            } else {
                ArrayNode array = filters.putArray(name);
                Arrays.stream(values).forEach(array::add);
            }
        });
        return filters;
    }
}
