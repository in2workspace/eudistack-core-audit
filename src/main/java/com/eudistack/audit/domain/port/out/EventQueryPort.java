package com.eudistack.audit.domain.port.out;

import com.eudistack.audit.domain.model.QuerySpec;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.StoredEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the event store. Every method is scoped: it only reads the schemas of the scope and,
 * for a {@code domain} reader, only the caller's organization.
 */
public interface EventQueryPort {

    /**
     * Events of the scope matching the query, in listing order, at most {@code spec.limit() + 1}
     * (the extra one tells whether there is a next page).
     */
    List<StoredEvent> find(ReadScope scope, QuerySpec spec);

    /**
     * The event, if it is in the scope and its {@code occurred_at} is not before {@code onlineFrom}.
     */
    Optional<StoredEvent> findById(ReadScope scope, UUID eventId, Instant onlineFrom);
}
