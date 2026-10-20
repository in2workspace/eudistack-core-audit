package com.eudistack.audit.infrastructure.persistence.tenant;

import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Function;

/**
 * One Hikari pool of one database role (AD-2, AD-10), with its JDBC client and transactions.
 *
 * <p>The schema is never fixed on the connection: {@link #inTransaction} callers set it with
 * {@link SearchPath#setLocal}, which only lasts until the transaction ends, so a pooled connection
 * never carries a tenant into the next request (design §3.4.7).
 */
public final class JdbcPool {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcPool(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * Runs the work in one transaction. A pool timeout, a lost session or a failed commit becomes a
     * {@link ServiceUnavailableException}: nothing of the work stays.
     */
    public <T> T inTransaction(Function<JdbcClient, T> work) {
        try {
            return transactions.execute(status -> work.apply(jdbc));
        } catch (DataAccessException | TransactionException e) {
            throw new ServiceUnavailableException("The audit store is not available", e);
        }
    }
}
