# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **EUD-244 — servicio API de auditoría**, según `technical-design.md` de la feature:
  - `POST /api/v1/events` (host interno): lotes de 1 a 100 eventos, validación contra el catálogo por versión,
    guardado por esquema de tenant en una transacción, idempotencia por `event_id` con huella RFC 8785,
    `AuditEventRejected` en `platform` con `event_id` determinista, `AuditWriteDenied`, 503 con `Retry-After`.
  - `GET /api/v1/events` y `GET /api/v1/events/{event_id}` (host del tenant): ámbitos `organization`, `domain`
    y administrador (`platform`, un tenant o `all`), filtros, búsqueda exacta por ruta `searchable` (GIN),
    ventana de 31 días sobre los 90 en línea, cursor firmado, límites de volumen, `AuditTrailQueried` de cada
    consulta concedida o denegada.
  - Separación de caras por host (405), rechazo de `Origin` en el host interno, `Cache-Control: no-store`,
    problemas RFC 9457.
  - Archivado diario a S3 con Object Lock en modo Compliance (7 años), manifiesto verificado antes de borrar,
    `AuditEventsArchived`, recuperación de periodos archivados.
  - Perfiles `writer`, `reader`, `archiver`, `provisioner` y `all`; roles de base de datos separados, triggers
    append-only y migraciones Flyway por tenant.
  - Métricas y spans OpenTelemetry de §3.10, logs JSON.
  - Tests unitarios y de integración (Testcontainers PostgreSQL 17) de AC-01 a AC-22 en lo que toca al servicio.
  - `./gradlew devTokens` y `docs/local-testing.md`: tokens locales y guía para probar los casos de uso a mano.

### Fixed

- Los filtros comparan la ruta normalizada (decodificada, sin `;parámetros`), la misma que enruta Spring:
  `/api/v1/%65vents` o `/api/v1/events;x=1` ya no se saltan la política de host, la autenticación ni el
  límite de volumen. Los controladores responden 401 si les llega una petición sin llamante autenticado.
- Un evento con U+0000 se rechaza como `invalid_event`, y su `AuditEventRejected` guarda una copia saneada.
  Un parámetro de consulta con U+0000 es un 400. Antes, PostgreSQL rechazaba el valor y el lote entero
  devolvía un 503 que el productor reintentaba sin fin.
- `occurred_at`, `from` y `to` exigen un año de cuatro dígitos (RFC 3339): `+10000-…` quedaba fuera de
  `timestamptz` y acababa también en un 503.
- Archivado: si una ejecución se interrumpe después de escribir el objeto de eventos y antes del manifiesto,
  la siguiente reutiliza ese objeto (mismos bytes) en vez de fallar cada día contra la protección de escritura
  única.
