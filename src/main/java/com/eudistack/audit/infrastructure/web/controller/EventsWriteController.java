package com.eudistack.audit.infrastructure.web.controller;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventOutcome;
import com.eudistack.audit.domain.port.in.RecordEventsPort;
import com.eudistack.audit.infrastructure.config.AuditRole;
import com.eudistack.audit.infrastructure.config.ConditionalOnAuditRole;
import com.eudistack.audit.infrastructure.web.ApiRequest;
import com.eudistack.audit.infrastructure.web.TraceIds;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * {@code POST /api/v1/events} (OpenAPI {@code recordEvents}). The body is read raw: each event is
 * validated, hashed and, when rejected, stored exactly as it arrived.
 */
@RestController
@ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.ALL})
public class EventsWriteController {

    private final RecordEventsPort recordEvents;
    private final TraceIds traceIds;
    private final ObjectReader reader;

    public EventsWriteController(RecordEventsPort recordEvents, TraceIds traceIds, ObjectMapper mapper) {
        this.recordEvents = recordEvents;
        this.traceIds = traceIds;
        // A duplicated key would make "the event as received" ambiguous: such a body is not valid JSON here.
        this.reader = mapper.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PostMapping(path = {ApiRequest.COLLECTION, ApiRequest.PREFIX + ApiRequest.COLLECTION},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> recordBatch(HttpServletRequest request,
                                                  @RequestBody(required = false) byte[] body) {
        Caller caller = ApiRequest.authenticatedCaller(request);
        List<EventOutcome> outcomes = recordEvents.recordBatch(caller, parse(body), traceIds.current());
        return ResponseEntity.ok(toResponse(outcomes));
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return reader.readTree(body);
        } catch (IOException _) {
            return null;
        }
    }

    static ObjectNode toResponse(List<EventOutcome> outcomes) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        ArrayNode results = response.putArray("results");
        for (EventOutcome outcome : outcomes) {
            ObjectNode item = results.addObject();
            item.put("event_id", outcome.eventId());
            item.put("status", outcome.status().wireValue());
            if (outcome.reason() != null) {
                ObjectNode error = item.putObject("error");
                error.put("type", outcome.reason().errorType());
                error.put("title", outcome.reason().title());
                error.put("status", 422);
                if (!outcome.violations().isEmpty()) {
                    ArrayNode violations = error.putArray("violations");
                    outcome.violations().forEach(v -> violations.addObject()
                            .put("field", v.field()).put("message", v.message()));
                }
            }
        }
        return response;
    }
}
