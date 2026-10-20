package com.eudistack.audit.infrastructure.config;

/**
 * What a process of this single binary runs ({@code audit.role}, design §3.2, AD-10).
 */
public enum AuditRole {
    /** {@code POST /api/v1/events} on the internal host, pool {@code audit-writer}. */
    WRITER,
    /** {@code GET} on the tenant host, pool {@code audit-reader}. */
    READER,
    /** Daily archiving and recovery, pool {@code audit-archiver}. No business HTTP. */
    ARCHIVER,
    /** Writer + reader + archiver, each with its own pool. Local and CI only, never deployed. */
    ALL,
    /** One-shot schema provisioning, pool {@code audit-migrator}. */
    PROVISIONER;

    public boolean writes() {
        return this == WRITER || this == ALL;
    }

    public boolean reads() {
        return this == READER || this == ALL;
    }

    public boolean archives() {
        return this == ARCHIVER || this == ALL;
    }
}
