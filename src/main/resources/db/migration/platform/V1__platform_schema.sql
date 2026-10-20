-- Schema `platform` of the audit database (technical design §3.8, AD-10).
-- Applied by the `provisioner` task as audit_migrator; never by a runtime role.
-- Placeholders: ${writer_role}, ${reader_role}, ${archiver_role}.

-- Events with tenant = platform, AuditEventRejected, AuditWriteDenied, AuditEventsArchived and
-- AuditTrailQueried of the platform administrator. Same table as every tenant schema.
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

-- Append-only guard, shared by every schema (design §3.8 "Triggers"):
--   UPDATE and TRUNCATE: always rejected, whoever runs them.
--   DELETE: only the archiver, which deletes by identifier after a verified copy.
--   INSERT by the reader: only its AuditTrailQueried.
--   INSERT by the archiver: only AuditEventsArchived and the AuditTrailQueried of a recovery.
CREATE FUNCTION audit_event_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'TRUNCATE' THEN
    RAISE EXCEPTION 'audit_event is append-only: TRUNCATE is not allowed' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'audit_event is append-only: UPDATE is not allowed' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'DELETE' THEN
    IF current_user <> '${archiver_role}' THEN
      RAISE EXCEPTION 'audit_event: only the archiver deletes events' USING ERRCODE = '42501';
    END IF;
    RETURN OLD;
  END IF;
  IF current_user = '${reader_role}' AND NEW.event_type <> 'AuditTrailQueried' THEN
    RAISE EXCEPTION 'audit_event: the reader only inserts AuditTrailQueried' USING ERRCODE = '42501';
  END IF;
  IF current_user = '${archiver_role}' AND NEW.event_type NOT IN ('AuditEventsArchived', 'AuditTrailQueried') THEN
    RAISE EXCEPTION 'audit_event: the archiver only inserts its own events' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER audit_event_guard_row
  BEFORE INSERT OR UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_event_guard();

CREATE TRIGGER audit_event_guard_truncate
  BEFORE TRUNCATE ON audit_event
  FOR EACH STATEMENT EXECUTE FUNCTION audit_event_guard();

-- The only source of schema names. A tenant without a row here does not exist (unknown_tenant).
CREATE TABLE tenant_directory (
  tenant_id      text        PRIMARY KEY,
  schema_name    text        NOT NULL UNIQUE CHECK (schema_name ~ '^[a-z][a-z0-9_]{0,62}$'),
  provisioned_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Fixed one-minute windows of the query volume limit (FR-18).
CREATE TABLE rate_window (
  bucket_key   text        NOT NULL,
  window_start timestamptz NOT NULL,
  hit_count    integer     NOT NULL,
  PRIMARY KEY (bucket_key, window_start)
);

-- One archiver per tenant and day.
CREATE TABLE archive_lock (
  tenant_id  text        NOT NULL,
  day        date        NOT NULL,
  locked_by  text        NOT NULL,
  locked_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, day)
);

-- Grants of AD-10 (design §3.4.2). Nobody gets UPDATE on audit_event.
GRANT USAGE ON SCHEMA platform TO ${writer_role}, ${reader_role}, ${archiver_role};

GRANT INSERT ON audit_event TO ${writer_role};
GRANT SELECT (event_id, payload_hash) ON audit_event TO ${writer_role};
GRANT SELECT, INSERT ON audit_event TO ${reader_role};
GRANT SELECT, INSERT, DELETE ON audit_event TO ${archiver_role};

GRANT SELECT ON tenant_directory TO ${writer_role}, ${reader_role}, ${archiver_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON rate_window TO ${reader_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON archive_lock TO ${archiver_role};
