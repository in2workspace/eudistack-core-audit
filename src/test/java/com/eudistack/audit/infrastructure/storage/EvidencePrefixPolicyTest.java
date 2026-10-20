package com.eudistack.audit.infrastructure.storage;

import com.eudistack.audit.application.workflow.ArchiveFormat;
import com.eudistack.audit.domain.model.TenantRef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-19, contract of the {@code evidence/} prefix: it belongs to the database access detector (FR-20),
 * which writes {@code EvidenceStoreAccess} there with the same compliance lock. The archiver never writes
 * under it, and no tenant can be named so that its archive lands there.
 */
class EvidencePrefixPolicyTest {

    @Test
    void archiveKeys_neverUnderEvidence() {
        LocalDate day = LocalDate.parse("2026-07-20");

        assertThat(ArchiveFormat.eventsKey("red-logistica", day, 0)).isEqualTo("red-logistica/2026/07/20/events.jsonl.gz");
        assertThat(ArchiveFormat.manifestKey("platform", day, 2)).isEqualTo("platform/2026/07/20/manifest-2.json");
        assertThat(ArchiveFormat.dayPrefix("sandbox", day)).doesNotStartWith(TenantRef.EVIDENCE_PREFIX + "/");
    }

    @Test
    void evidence_isReservedAsTenant() {
        assertThatThrownBy(() -> TenantRef.forNewTenant("evidence")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantRef.forNewTenant("EVIDENCE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedAndInvalidTenantIdentifiers_areRefused() {
        // pg-x would map to pg_x, a prefix PostgreSQL reserves for system schemas.
        for (String id : new String[] {"platform", "all", "public", "../x", "a b", "", "pg-x"}) {
            assertThatThrownBy(() -> TenantRef.forNewTenant(id)).as(id).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void tenantIdentifier_mapsToASafeSchemaName() {
        assertThat(TenantRef.forNewTenant("Red-Logistica")).isEqualTo(new TenantRef("red-logistica", "red_logistica"));
        assertThat(TenantRef.forNewTenant("123abc").schemaName()).isEqualTo("t_123abc");
    }
}
