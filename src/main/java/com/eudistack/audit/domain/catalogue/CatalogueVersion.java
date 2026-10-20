package com.eudistack.audit.domain.catalogue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable snapshot of the catalogue ({@code catalog_version}). Each event is validated and read
 * with the snapshot of the version it carries (FR-19).
 *
 * @param version     semantic version
 * @param types       event types by name
 * @param actorFields declaration of {@code types.actor}
 */
public record CatalogueVersion(String version, Map<String, EventTypeSpec> types, Map<String, FieldSpec> actorFields) {

    public CatalogueVersion {
        Objects.requireNonNull(version, "version");
        types = Map.copyOf(types);
        actorFields = actorFields == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(actorFields));
    }

    public Optional<EventTypeSpec> type(String name) {
        return Optional.ofNullable(types.get(name));
    }
}
