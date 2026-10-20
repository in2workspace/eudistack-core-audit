package com.eudistack.audit.domain.port.out;

import java.util.function.Function;

/**
 * Event store of the write face. The adapter owns the transaction: either the whole batch commits,
 * or nothing stays (a store failure is a {@code ServiceUnavailableException}).
 */
public interface AuditEventRepositoryPort {

    <T> T writeBatch(Function<BatchWriter, T> work);
}
