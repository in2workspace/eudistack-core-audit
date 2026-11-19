package com.eudistack.audit.infrastructure.persistence.tenant;

import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Function;

/**
 * One pool of one database role (AD-2, AD-10), with its JDBC client and transactions.
 *
 * <p>The {@link DataSource} is the bean after datasource-micrometer wraps it. Queries and
 * transactions go through that proxy, so each statement is a child of the current observation.
 * Closing unwraps to the Hikari pool: the proxy itself has no {@code close}.
 *
 * <p>The schema is never fixed on the connection: {@link #inTransaction} callers set it with
 * {@link SearchPath#setLocal}, which only lasts until the transaction ends, so a pooled connection
 * never carries a tenant into the next request (design §3.4.7).
 */
public final class JdbcPool implements DisposableBean {

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcPool(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public DataSource dataSource() {
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

    @Override
    public void destroy() {
        close(dataSource);
    }

    /** Closes the Hikari pool, including when {@code dataSource} is the observation proxy. */
    public static void close(DataSource dataSource) {
        Object target = AopProxyUtils.getSingletonTarget(dataSource);
        if (target instanceof AutoCloseable closeable) {
            close(closeable);
            return;
        }
        if (dataSource instanceof AutoCloseable closeable) {
            close(closeable);
        }
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            throw new IllegalStateException("Could not close the audit pool", e);
        }
    }
}
