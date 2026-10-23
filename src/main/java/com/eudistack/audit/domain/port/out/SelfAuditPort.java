package com.eudistack.audit.domain.port.out;

import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;

/**
 * Stores, in its own committed transaction, an event the API generates about itself.
 */
public interface SelfAuditPort {

    void storeEvent(TenantRef target, AuditEvent event);
}
