# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- Despliegue: este servicio va antes que el Verifier que emite con el catálogo 2.4.0 (EUD-313). Si el Verifier llega antes, la API rechaza todos sus eventos, también `SystemAuthenticated` y `ExternalSystemAccess`.

## [0.3.0] - 2026-11-26

### Added

- **EUD-382 — catálogo de eventos 2.5.0** (`catalogue/catalogo-eventos-2.5.0.yaml`): el contenido de la 2.4.0 más la categoría `issuance_portal`, con los 14 eventos del portal de emisión. El catálogo 2.2.0 se mantiene sin cambios. Los eventos que genera la propia API pasan a la 2.5.0.
- Tests de los eventos del portal de emisión.

### Changed

- Despliegue: este servicio va antes que el portal de emisión que emite con el catálogo 2.5.0. Si el portal llega antes, la API rechaza sus eventos.

## [0.2.0] - 2026-11-19

### Added

- **EUD-293 — listado en orden descendente**: `GET /api/v1/events` acepta `order=asc|desc` (por defecto `asc`).
  Con `desc` ordena por `occurred_at` y `event_id` del más reciente al más antiguo, también en la fusión de
  `tenant=all`. El cursor firmado lleva el orden de la primera página: un cursor usado con otro `order` es
  `400 BAD_REQUEST`, y uno emitido antes de este cambio se lee como `asc`.
- **EUD-293 — administrador de sistema desde el host de un tenant**: sin `tenant`, recibe el ámbito de todo ese
  tenant (acción A2 de EUD-253) y su `AuditTrailQueried` queda en ese tenant. `tenant` sigue reservado al host de
  la plataforma. También vale para `GET /api/v1/events/{event_id}`.
- **EUD-293 — audiencia con marcador de tenant**: `AUDIT_SECURITY_AUDIENCE` admite una lista de valores separados
  por comas y cada uno puede llevar `{tenant}`, que se sustituye por el tenant del host (por ejemplo
  `vc-auth-client-{tenant}`, la audiencia de la sesión del portal de administración). En el host interno no hay
  tenant: ahí solo valen los valores fijos. Sin audiencia, `aud` no se comprueba, como hasta ahora.
- Tests unitarios y de integración del orden, del ámbito del administrador y de la audiencia.

### Changed

- **EUD-293 — `TokenVerifier.verify` recibe el tenant del host**, que resuelve `HostTenantResolver`, compartido con
  `TenantResolutionFilter`. El token de recuperación de `RecoverArchiveRunner` se verifica sin tenant.
- `compose.yaml` pasa `AUDIT_SECURITY_AUDIENCE` al contenedor local. `docs/openapi.yaml`, `docs/architecture.md`
  (D-16 y R-1) y el README describen `order`, el administrador desde un tenant y la audiencia.
- Despliegue: primero este servicio, después la IaC con `AUDIT_SECURITY_AUDIENCE` y por último el portal.

## [Unreleased]

### Added (EUD-313)

- **Catálogo de eventos 2.4.0** (`catalogue/catalogo-eventos-2.4.0.yaml`), copia del anexo del PRD de EUD-329. Añade el evento `CertificateAuthentication`, el valor `Certificate` en `session_origin` de `IamSessionEstablished` y el campo opcional `trust_warning`. El catálogo 2.2.0 se mantiene sin cambios, así que los productores que siguen emitiendo con la 2.2.0 no se ven afectados. Los eventos que genera la propia API pasan a la 2.4.0.

### Changed (EUD-26)

- **Observabilidad alineada con la Wallet EBW, la configuración de referencia.**
- **Las trazas usan el exportador OTLP/gRPC nativo de Spring Boot** (`management.otlp.tracing.transport: grpc`). Los despliegues deben fijar `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true` y quitar `SPRING_AUTOCONFIGURE_EXCLUDE`, que apagaría este exportador, `MANAGEMENT_OTLP_GRPC_TRACING_ENABLED` y `OTEL_EXPORTER_OTLP_ENDPOINT`. Se retira `OtlpGrpcTracingConfig`.
- **Muestreo con `OTEL_SAMPLING_PROBABILITY`**, 1,0 por defecto (antes `TRACING_SAMPLING_PROBABILITY`, 0,1): diez veces más trazas en OpenObserve si el despliegue no fija la variable. Paso de métricas de 5 s (antes 30 s) y propagación solo `w3c` (antes `w3c,b3`).
- **Enmascarado de datos personales y secretos** en la consola JSON y en la exportación OTLP, incluidos los correos codificados en URL (`%40`).
- **Las excepciones y los pares clave-valor se enmascaran**, en la consola JSON y hacia OpenObserve. El mensaje, las causas y las excepciones suprimidas viajan enmascarados en `exception.type`, `exception.message` y `exception.stacktrace`, que dependen de `captureKeyValuePairAttributes`.
- **Se enmascaran más valores**: claves con prefijo o en camelCase (`db_password`, `jwtSecret`, `vp_token`), `pre-authorized_code`, `txCode`, `pin`, `Authorization: Basic`, las disclosures y el JWT de key binding de un SD-JWT, los JWT sin firma y el valor completo cuando va entre comillas y lleva espacios. Los pares clave-valor numéricos se enmascaran por su clave. Los mensajes de más de 16 KB se truncan antes de enmascarar. Las reglas del correo, los JWT y `code` siguen siendo lineales con entradas largas.
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
