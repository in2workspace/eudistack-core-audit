package com.eudistack.audit.domain.catalogue;

/**
 * Field types the catalogue uses. Named types ({@code person}, {@code hash}, {@code delivery_method},
 * {@code actor}) are expanded by the catalogue parser into one of these.
 */
public enum FieldType {
    STRING,
    BOOLEAN,
    INTEGER,
    DATE_TIME,
    URI,
    EMAIL,
    UUID,
    SEMVER,
    ENUM,
    /** Object whose fields the catalogue declares; any other field is rejected. */
    OBJECT,
    /** Object without sub-schema in the catalogue ({@code type: object}): any JSON object. */
    FREE_OBJECT,
    ARRAY
}
