package com.eudistack.audit.domain.port.in;

import com.eudistack.audit.domain.model.EventPage;

/**
 * Query face of the audit API: {@code GET /api/v1/events}. Every call, granted or denied, leaves an
 * {@code AuditTrailQueried} (FR-17).
 */
public interface ListEventsPort {

    EventPage list(QueryContext context, ListRequest request);
}
