# eudistack-core-audit — repo guide

Audit API of EUDIStack (EUD-244): validates audit events against the event catalogue, stores them per
tenant schema, serves them to readers with powers, archives them seven years under S3 Object Lock.

Read [docs/architecture.md](docs/architecture.md) before changing anything structural. The specification
(PRD, technical design, catalogue, OpenAPI) lives in
`../eudistack-platform-dev/docs/features/EUD-244-api-auditoria/`.

## Invariants

- Hexagonal: `domain/` imports no framework; `application/` no infrastructure. Enforced by `HexagonalArchitectureTest`.
- Spring MVC on virtual threads, plain JDBC (`JdbcClient`), no JPA.
- One binary, `audit.role` decides what runs; each role opens only its pool with its own DB role (AD-10).
- Tenant schema only from `platform.tenant_directory`, set with `set_config('search_path', …, true)` inside a transaction. Never `SET SESSION`, never `connectionInitSql`.
- No operation updates or deletes an event; only the archiver deletes, by identifier, after a verified copy.
- Catalogue snapshots in `src/main/resources/catalogue/` are immutable: a new version is a new file.
- Never log event bodies, actors, details or tokens.
- Time through the `Clock` bean.

## Commands

| Task | Command |
|------|---------|
| Build, all tests, checkstyle, coverage | `./gradlew build` |
| Unit tests only | `./gradlew test` |
| Integration tests (Docker) | `./gradlew integrationTest` |
| Local stack | `docker compose up --build` |
| Local tokens for manual testing | `./gradlew devTokens` (see docs/local-testing.md) |
