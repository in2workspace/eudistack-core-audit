# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed (observability)

- **Observabilidad alineada con la Wallet EBW, la configuración de referencia.**
- **Las trazas usan el exportador OTLP/gRPC nativo de Spring Boot** (`management.otlp.tracing.transport: grpc`). Los despliegues deben fijar `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true` y quitar `SPRING_AUTOCONFIGURE_EXCLUDE`, que apagaría este exportador, `MANAGEMENT_OTLP_GRPC_TRACING_ENABLED` y `OTEL_EXPORTER_OTLP_ENDPOINT`. Se retira `OtlpGrpcTracingConfig`.
- **Muestreo con `OTEL_SAMPLING_PROBABILITY`**, 1,0 por defecto (antes `TRACING_SAMPLING_PROBABILITY`, 0,1). Paso de métricas de 5 s (antes 30 s) y propagación solo `w3c` (antes `w3c,b3`).
- **Enmascarado de datos personales y secretos** en la consola JSON y en la exportación OTLP, incluidos los correos codificados en URL (`%40`).
- **Etiquetas `application` y `component`** en métricas y observaciones (`MicrometerMetricsConfig`, `ObservationConfig`). La etiqueta `tenant` queda pendiente.
- `OpenTelemetryLogsInitializer` pasa a llamarse `OpenTelemetryAppenderInitializer`, como en los demás servicios, y el colector por defecto pasa a `http://127.0.0.1:4317`.

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
  - `./gradlew devTokens` y `docs/local-testing.md`: tokens locales y guía para probar los 22 criterios de
    aceptación a mano, con DBeaver, archivado, recuperación y OpenTelemetry.
  - OpenTelemetry completo según §3.10: los logs también se exportan por OTLP (appender de logback, como el
    Verifier) y por consola en JSON con `trace_id`, `span_id` y `service.name`; los spans llevan los atributos
    que solo se conocen al terminar (`audit.status`, `audit.archive.result`, `audit.archive.count`). Perfil
    `observability` en `compose.yaml` (Collector + Zipkin + métricas en formato Prometheus).

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
