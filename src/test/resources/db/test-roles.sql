-- Database roles of AD-10 for the Testcontainers database. In every real environment they are created
-- by the platform IaC (dev-tools/db/init-roles.sql mirrors this file for the local stack).
CREATE ROLE audit_migrator LOGIN PASSWORD 'migrator';
CREATE ROLE audit_writer   LOGIN PASSWORD 'writer';
CREATE ROLE audit_reader   LOGIN PASSWORD 'reader';
CREATE ROLE audit_archiver LOGIN PASSWORD 'archiver';

DO $$
BEGIN
  EXECUTE format('GRANT CREATE ON DATABASE %I TO audit_migrator', current_database());
  EXECUTE format('REVOKE CREATE ON DATABASE %I FROM PUBLIC', current_database());
END
$$;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
