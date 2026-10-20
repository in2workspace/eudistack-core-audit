package com.eudistack.audit.domain.port.out;

import com.eudistack.audit.domain.catalogue.CatalogueVersion;
import com.eudistack.audit.domain.catalogue.EventTypeSpec;

import java.util.Collection;
import java.util.Optional;

/**
 * Published catalogue snapshots, by {@code catalog_version}.
 */
public interface CatalogueResolverPort {

    Optional<CatalogueVersion> version(String version);

    Collection<CatalogueVersion> all();

    /**
     * Highest published version. The API emits its own events with it.
     */
    CatalogueVersion latest();

    /**
     * Declaration of an event type in the highest version that declares it. Used by the query API
     * to check {@code event_type} and the searchable paths.
     */
    Optional<EventTypeSpec> latestSpecOf(String eventType);
}
