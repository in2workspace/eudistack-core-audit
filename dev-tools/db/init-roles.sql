-- LOCAL DEVELOPMENT ONLY. Database roles of AD-10 for the compose stack.
-- In every deployed environment the database `audit` and these roles are created by the platform IaC
-- (eudistack-platform-iac), with secrets from the secret store — never these passwords.
CREATE ROLE audit_migrator LOGIN PASSWORD 'migrator';
CREATE ROLE audit_writer   LOGIN PASSWORD 'writer';
CREATE ROLE audit_reader   LOGIN PASSWORD 'reader';
CREATE ROLE audit_archiver LOGIN PASSWORD 'archiver';

GRANT CREATE ON DATABASE audit TO audit_migrator;
REVOKE CREATE ON DATABASE audit FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
