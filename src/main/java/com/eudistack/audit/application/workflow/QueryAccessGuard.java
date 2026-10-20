package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.exception.InsufficientPermissionException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.exception.TenantMismatchException;
import com.eudistack.audit.domain.exception.TenantNotResolvedException;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.ScopePolicy;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.in.QueryContext;
import com.eudistack.audit.domain.port.out.SelfAuditPort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;

import java.util.Optional;

/**
 * Authorisation of a query and its {@code AuditTrailQueried} (FR-15, FR-17, design §3.4.5).
 *
 * <p>A denial is recorded before the 403 leaves; best effort, the 403 never depends on the store. A
 * granted query is recorded — and committed — before its body is written: if that fails the events
 * are not returned (a 503), so no query goes without trace.
 */
public final class QueryAccessGuard {

    private static final System.Logger LOG = System.getLogger(QueryAccessGuard.class.getName());

    private final TenantDirectoryPort directory;
    private final SelfAuditPort selfAudit;
    private final SelfEventFactory selfEvents;

    public QueryAccessGuard(TenantDirectoryPort directory, SelfAuditPort selfAudit, SelfEventFactory selfEvents) {
        this.directory = directory;
        this.selfAudit = selfAudit;
        this.selfEvents = selfEvents;
    }

    /**
     * @param operation {@code List} or {@code Get}
     * @param filters   the query parameters as received (List only)
     */
    public ReadScope authorize(QueryContext context, String tenantParam, String operation, ObjectNode filters) {
        Optional<TenantRef> host = context.hostTenantId() == null ? Optional.empty()
                : directory.find(TenantRef.normalise(context.hostTenantId()));
        ScopePolicy.Decision decision = ScopePolicy.resolve(context.caller(), host, tenantParam, directory::find);
        if (decision instanceof ScopePolicy.Granted granted) {
            return granted.scope();
        }
        ScopePolicy.Denied denied = (ScopePolicy.Denied) decision;
        recordDenial(context, denied.auditTarget(), operation, denied.label(), filters,
                denied.reason().catalogueReason());
        throw switch (denied.reason()) {
            case PERMISSION_MISSING -> new InsufficientPermissionException("The caller lacks a read power");
            case TENANT_MISMATCH -> new TenantMismatchException(
                    "The request tenant is not the tenant of the caller's attestation");
            case TENANT_NOT_RESOLVED -> new TenantNotResolvedException("No tenant could be resolved for the request");
        };
    }

    public void recordGranted(QueryContext context, ReadScope scope, String operation, ObjectNode filters,
                              int returnedCount) {
        try {
            selfAudit.record(scope.auditTarget(), selfEvents.trailQueried(context.caller(), scope.auditTarget(),
                    operation, scope.label(), filters, null, returnedCount, context.traceId()));
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("The query could not be recorded", e);
        }
    }

    public void recordDenial(QueryContext context, TenantRef target, String operation, String label,
                             ObjectNode filters, String reason) {
        try {
            selfAudit.record(target, selfEvents.trailQueried(context.caller(), target, operation, label, filters,
                    reason, null, context.traceId()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Denied AuditTrailQueried could not be stored", e);
        }
    }
}
