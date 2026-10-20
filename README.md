# eudistack-core-audit

API de auditoría de EUDIStack ([EUD-244](https://eudistack.atlassian.net/browse/EUD-244)): un único servicio
que recibe los eventos de auditoría de los sistemas de la plataforma, los valida contra el catálogo de eventos,
los guarda en el esquema de su tenant, los sirve a quien tiene poder de lectura y los archiva siete años en un
almacenamiento bloqueado.

- Contrato HTTP: [`docs/openapi.yaml`](docs/openapi.yaml)
- Arquitectura, decisiones y cobertura de AC: [`docs/architecture.md`](docs/architecture.md)
- Especificación (PRD, diseño técnico, catálogo): `eudistack-platform-dev/docs/features/EUD-244-api-auditoria/`

## Stack

Java 25 · Spring Boot 3.5 (Spring MVC, hilos virtuales) · JDBC + HikariCP · PostgreSQL 17 (esquema por tenant) ·
Flyway · Nimbus JOSE · AWS SDK v2 (S3 Object Lock) · Micrometer + OpenTelemetry (OTLP) · Gradle Kotlin DSL.
Arquitectura hexagonal: `domain` → `application` → `infrastructure`.

## Roles

Una sola imagen; `AUDIT_ROLE` decide qué arranca:

| Rol | Qué hace | Rol de BD | Host |
|-----|----------|-----------|------|
| `writer` | `POST /api/v1/events` | `audit_writer` | `audit.internal.{env}.eudistack.net` |
| `reader` | `GET /api/v1/events`, `GET /api/v1/events/{id}` | `audit_reader` | `{tenant}.{env}.eudistack.net/audit` |
| `archiver` | Archivado diario 02:00 UTC; `audit.archive.run-once=true` para una sola ejecución; recuperación con `audit.recover.*` | `audit_archiver` | — |
| `provisioner` | One-shot: migra `platform` y da de alta los tenants de `AUDIT_PROVISIONER_TENANTS` | `audit_migrator` | — |
| `all` | writer + reader + archiver. **Solo local y CI** | los tres | ambos |

## Configuración principal

| Variable | Uso |
|----------|-----|
| `AUDIT_ROLE` | Rol del proceso |
| `AUDIT_DB_URL`, `AUDIT_DB_{WRITER,READER,ARCHIVER,MIGRATOR}_{USER,PASSWORD}` | Base `audit` y credenciales de cada rol |
| `AUDIT_SECURITY_JWKS_URI` | JWKS del Verifier (URL interna) |
| `AUDIT_SECURITY_TRUSTED_ISSUERS` | `iss` aceptados, p. ej. `https://{tenant}.stg.eudistack.net/verifier` |
| `AUDIT_SECURITY_AUDIENCE` | `aud` exigido; vacío hasta que el Verifier la emita (PO-12) |
| `AUDIT_QUERY_CURSOR_SECRET` | Clave HMAC de los cursores, igual en todas las réplicas reader (≥ 32 bytes) |
| `AUDIT_ARCHIVE_BUCKET`, `AWS_REGION`, `AUDIT_ARCHIVE_ENDPOINT` | Bucket S3 con Object Lock (Compliance) y SSE-KMS |
| `AUDIT_PROVISIONER_TENANTS` | Tenants a provisionar (`sandbox,red-logistica`) |
| `audit.query.rate-limit*`, `audit.query.rate-limit-overrides.<org>.*` | Límites de FR-18 y ampliaciones por organización |
| `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_ENDPOINT` | Collector OpenTelemetry (OTLP HTTP; por defecto `http://otel-collector:4318/v1/…`) |
| `TRACING_SAMPLING_PROBABILITY`, `OTLP_METRICS_ENABLED`, `OTLP_LOGS_ENABLED` | Muestreo de trazas (0,1) y exportación de métricas y logs |

Los roles de base de datos, la base `audit`, el bucket y sus políticas los crea el IaC de plataforma; para local,
[`dev-tools/db/init-roles.sql`](dev-tools/db/init-roles.sql).

## Comandos

| Tarea | Comando |
|-------|---------|
| Build completo (unit + integración + checkstyle + jacoco) | `./gradlew build` |
| Solo tests unitarios (sin Docker) | `./gradlew test` |
| Solo integración (Testcontainers, requiere Docker) | `./gradlew integrationTest` |
| Stack local (PostgreSQL + provisioner + API `all`) | `docker compose up --build` |
| Tokens locales para probar a mano (sin Verifier) | `./gradlew devTokens` |
| Stack local con OpenTelemetry (Collector + Zipkin) | `OTEL=true TRACING_SAMPLING_PROBABILITY=1 docker compose --profile observability up --build` |

En local la API escucha en `localhost:8090` (actuator en `8091`). La cara de escritura responde con
`Host: audit.internal.localhost` y la de consulta con `Host: <tenant>.localhost`, p. ej.:

```bash
curl -H "Host: sandbox.localhost" -H "Authorization: Bearer $TOKEN" \
     "http://localhost:8090/audit/api/v1/events?event_type=IssuanceCompleted"
```

Para recorrer todos los criterios de aceptación a mano (API, DBeaver en `localhost:5433`, archivado,
OpenTelemetry), con tokens locales de cada perfil, sigue [`docs/local-testing.md`](docs/local-testing.md).

El despliegue (ECS, ingress, IaC) es responsabilidad de arquitectura y no forma parte de este repositorio.
