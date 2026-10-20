package com.eudistack.audit.domain.exception;

/**
 * `details_path` is not declared searchable by every requested type (`path_not_searchable`).
 */
public final class PathNotSearchableException extends AuditDomainException {

    public PathNotSearchableException(String message) {
        super(message);
    }
}
