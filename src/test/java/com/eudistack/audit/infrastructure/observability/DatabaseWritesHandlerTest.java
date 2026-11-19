package com.eudistack.audit.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.ttddyy.observation.tracing.QueryContext;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseWritesHandlerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DatabaseWritesHandler handler = new DatabaseWritesHandler(meters);

    @Test
    void onStop_Insert_CountsASuccessWithoutTheStatementValues() {
        handler.onStop(query("  INSERT INTO audit_event (event_id, details) VALUES (?, ?)"));

        assertThat(meters.get("db.writes").tag("operation", "insert").tag("outcome", "success").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("db.writes").counter().getId().getTags()).noneMatch(tag ->
                tag.getValue().contains("details") || tag.getKey().equals("error.type"));
    }

    @Test
    void onStop_FailedUpdate_CountsTheSqlStateClassAndNotTheMessage() {
        QueryContext context = query("UPDATE audit_event SET recorded_at = ? WHERE event_id = ?");
        context.setError(new RuntimeException(new SQLException("duplicate key value is user@example.com", "23505")));

        handler.onStop(context);

        assertThat(meters.get("db.writes").tag("operation", "update").tag("outcome", "error")
                .tag("error.type", "23").counter().count()).isEqualTo(1);
        assertThat(meters.get("db.writes").counter().getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("user@example.com"));
    }

    @Test
    void onStop_SelectAndSearchPath_CountNothing() {
        handler.onStop(query("SELECT set_config('search_path', ?, true)"));
        handler.onStop(query("SELECT * FROM audit_event WHERE event_id = ?"));

        assertThat(meters.find("db.writes").counters()).isEmpty();
    }

    private static QueryContext query(String sql) {
        QueryContext context = new QueryContext();
        context.setQueries(List.of(sql));
        return context;
    }
}
