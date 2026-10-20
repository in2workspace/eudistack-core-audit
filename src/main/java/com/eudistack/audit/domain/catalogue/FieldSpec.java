package com.eudistack.audit.domain.catalogue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declaration of one field of the catalogue.
 *
 * @param name     field name
 * @param type     field type
 * @param required when the field is required
 * @param values   allowed values, for {@link FieldType#ENUM}
 * @param pattern  pattern a string must match, or {@code null}
 * @param fields   declared fields, for {@link FieldType#OBJECT}
 * @param items    element declaration, for {@link FieldType#ARRAY}
 */
public record FieldSpec(
        String name,
        FieldType type,
        RequiredRule required,
        List<String> values,
        Pattern pattern,
        Map<String, FieldSpec> fields,
        FieldSpec items
) {

    public FieldSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(required, "required");
        values = values == null ? List.of() : List.copyOf(values);
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public FieldSpec withName(String newName) {
        return new FieldSpec(newName, type, required, values, pattern, fields, items);
    }

    public FieldSpec withRequired(RequiredRule newRequired) {
        return new FieldSpec(name, type, newRequired, values, pattern, fields, items);
    }
}
