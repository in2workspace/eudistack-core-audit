package com.eudistack.audit.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.eudistack.audit.domain.exception.BadRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch of 1 to 100 events as received (FR-07). Each element stays raw JSON: it is validated,
 * hashed and, when rejected, stored exactly as it arrived.
 */
public record EventBatch(List<JsonNode> events) {

    public static final int MAX_EVENTS = 100;

    public EventBatch {
        events = List.copyOf(events);
    }

    /**
     * Checks the shape of the batch. A malformed batch is rejected as a whole and nothing is stored.
     *
     * @param body parsed request body, or {@code null} when it was not JSON
     */
    public static EventBatch parse(JsonNode body) {
        if (body == null) {
            throw new BadRequestException("", "The body is not valid JSON");
        }
        if (!body.isObject()) {
            throw new BadRequestException("", "The body must be a JSON object");
        }
        JsonNode events = body.get("events");
        if (events == null || !events.isArray()) {
            throw new BadRequestException("events", "events is required and must be an array");
        }
        if (events.isEmpty()) {
            throw new BadRequestException("events", "events must contain at least 1 event");
        }
        if (events.size() > MAX_EVENTS) {
            throw new BadRequestException("events", "events must contain at most " + MAX_EVENTS + " events");
        }
        List<JsonNode> list = new ArrayList<>(events.size());
        events.forEach(list::add);
        return new EventBatch(list);
    }

    /**
     * Number of events of a body, for {@code AuditWriteDenied.details.event_count}: the size of the
     * {@code events} array when it can be read, 0 otherwise.
     */
    public static int countEvents(JsonNode body) {
        if (body == null || !body.isObject()) {
            return 0;
        }
        JsonNode events = body.get("events");
        return events != null && events.isArray() ? events.size() : 0;
    }
}
