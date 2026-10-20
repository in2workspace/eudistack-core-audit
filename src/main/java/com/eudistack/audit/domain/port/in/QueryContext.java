package com.eudistack.audit.domain.port.in;

import com.eudistack.audit.domain.model.Caller;

/**
 * Who queries and from where.
 *
 * @param caller       authenticated caller
 * @param hostTenantId tenant taken from the request host, or {@code null} when it could not be taken
 * @param traceId      trace identifier of the request, used as correlation_id of the query event
 */
public record QueryContext(Caller caller, String hostTenantId, String traceId) {
}
