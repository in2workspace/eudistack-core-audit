package com.eudistack.audit.domain.catalogue;

import com.eudistack.audit.domain.model.Result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One event type of one catalogue version.
 *
 * @param name       event type
 * @param action     the only business verb the type allows
 * @param results    results the type allows
 * @param reasons    reason vocabulary and the result each reason determines
 * @param searchable {@code details} paths the query API can filter on
 * @param details    declared {@code details} fields, common details of the category included
 */
public record EventTypeSpec(
        String name,
        String action,
        Set<Result> results,
        Map<String, Result> reasons,
        List<String> searchable,
        Map<String, FieldSpec> details
) {

    public EventTypeSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        results = Set.copyOf(results);
        reasons = Map.copyOf(reasons);
        searchable = searchable == null ? List.of() : List.copyOf(searchable);
        details = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public boolean isSearchable(String path) {
        return searchable.contains(path);
    }
}
