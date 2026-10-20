-- Template of a tenant schema of the audit database (technical design §3.8, AD-3).
-- Applied per tenant by the `provisioner` task as audit_migrator, with the tenant schema as the
-- Flyway default schema. The row in platform.tenant_directory is written only after this succeeds.
-- Placeholders: ${writer_role}, ${reader_role}, ${archiver_role}.

CREATE TABLE audit_event (
  event_id       uuid        PRIMARY KEY,
  event_type     text        NOT NULL,
  event_version  text        NOT NULL,
  occurred_at    timestamptz NOT NULL,
  recorded_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  tenant         text        NOT NULL,
  organization   text,
  source         text        NOT NULL,
  actor          jsonb       NOT NULL,
  actor_id       text        GENERATED ALWAYS AS (actor->>'id') STORED,
  action         text        NOT NULL,
  resource       text        NOT NULL,
  result         text        NOT NULL CHECK (result IN ('Success', 'Denied', 'Failure')),
  reason         text,
  correlation_id text        NOT NULL,
  details        jsonb       NOT NULL,
  payload_hash   bytea       NOT NULL
);

CREATE INDEX audit_event_occurred_idx      ON audit_event (occurred_at, event_id);
CREATE INDEX audit_event_recorded_idx      ON audit_event (recorded_at);
CREATE INDEX audit_event_type_occurred_idx ON audit_event (event_type, occurred_at, event_id);
CREATE INDEX audit_event_correlation_idx   ON audit_event (correlation_id, occurred_at);
CREATE INDEX audit_event_resource_idx      ON audit_event (resource);
CREATE INDEX audit_event_actor_idx         ON audit_event (actor_id, occurred_at);
CREATE INDEX audit_event_organization_idx  ON audit_event (organization, occurred_at);
CREATE INDEX audit_event_result_idx        ON audit_event (result, occurred_at);
CREATE INDEX audit_event_details_gin       ON audit_event USING gin (details jsonb_path_ops);

CREATE TRIGGER audit_event_guard_row
  BEFORE INSERT OR UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION platform.audit_event_guard();

CREATE TRIGGER audit_event_guard_truncate
  BEFORE TRUNCATE ON audit_event
  FOR EACH STATEMENT EXECUTE FUNCTION platform.audit_event_guard();

GRANT USAGE ON SCHEMA "${flyway:defaultSchema}" TO ${writer_role}, ${reader_role}, ${archiver_role};

GRANT INSERT ON audit_event TO ${writer_role};
GRANT SELECT (event_id, payload_hash) ON audit_event TO ${writer_role};
GRANT SELECT, INSERT ON audit_event TO ${reader_role};
GRANT SELECT, INSERT, DELETE ON audit_event TO ${archiver_role};
