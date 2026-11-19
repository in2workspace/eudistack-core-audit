package com.eudistack.audit.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import net.ttddyy.observation.tracing.QueryContext;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Locale;

/**
 * {@code db.writes} (OBS-16): every {@code INSERT}, {@code UPDATE} or {@code DELETE}, taken from
 * the observation the query span already has. {@code error.type} is the SQLSTATE class, never
 * the message, which can repeat a key or a row.
 */
@Component
public class DatabaseWritesHandler implements ObservationHandler<QueryContext> {

    static final String WRITES = "db.writes";

    private final MeterRegistry registry;

    public DatabaseWritesHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof QueryContext;
    }

    @Override
    public void onStop(QueryContext context) {
        String operation = writeOperation(context);
        if (operation == null) {
            return;
        }
        Counter.Builder builder = Counter.builder(WRITES)
                .description("Writes to the database by operation and outcome")
                .tag("operation", operation);
        Throwable error = context.getError();
        if (error == null) {
            builder.tag("outcome", "success");
        } else {
            builder.tag("outcome", "error").tag("error.type", sqlStateClass(error));
        }
        builder.register(registry).increment();
    }

    /** @return {@code insert}, {@code update} or {@code delete}; null for a read or an empty batch */
    static String writeOperation(QueryContext context) {
        if (context.getQueries() == null || context.getQueries().isEmpty()) {
            return null;
        }
        String sql = context.getQueries().getFirst().stripLeading().toLowerCase(Locale.ROOT);
        for (String operation : new String[]{"insert", "update", "delete"}) {
            if (sql.startsWith(operation)) {
                return operation;
            }
        }
        return null;
    }

    static String sqlStateClass(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().length() >= 2) {
                return sql.getSQLState().substring(0, 2);
            }
        }
        return "unknown";
    }
}
