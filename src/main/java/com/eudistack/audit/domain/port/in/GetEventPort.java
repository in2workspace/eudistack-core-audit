package com.eudistack.audit.domain.port.in;

import com.eudistack.audit.domain.model.AuditEvent;

/**
 * Query face of the audit API: {@code GET /api/v1/events/{event_id}}. Every call, granted or denied,
 * leaves an {@code AuditTrailQueried} (FR-17).
 */
public interface GetEventPort {

    /**
     * @param tenant {@code tenant} parameter, platform administrator only, or {@code null}
     */
    AuditEvent get(QueryContext context, String eventId, String tenant);
}
