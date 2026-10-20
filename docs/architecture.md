# eudistack-core-audit — Arquitectura y decisiones de implementación

Servicio API de auditoría de [EUD-244](https://eudistack.atlassian.net/browse/EUD-244). La especificación
vive en `eudistack-platform-dev/docs/features/EUD-244-api-auditoria/`: `prd.md` (contrato funcional, AC-01…AC-22),
`technical-design.md` (diseño técnico, AD-1…AD-10), `annex/openapi.yaml` (contrato HTTP, copia en
[`openapi.yaml`](openapi.yaml)) y `annex/catalogo-eventos.yaml` (catálogo, copia en
`src/main/resources/catalogue/catalogo-eventos-2.2.0.yaml`).

Este documento no repite el diseño: recoge **cómo se ha implementado**, las **decisiones tomadas con el líder técnico**
durante la implementación y lo que **queda abierto**.

## 1. Mapa del código

```text
com.eudistack.audit
├── domain                       sin Spring, JDBC, Nimbus ni AWS (HexagonalArchitectureTest)
│   ├── model                    AuditEvent, Caller, Power, TenantRef, ReadScope, EventBatch, …
│   ├── catalogue                CatalogueValidator, Condition (required condicional), FieldSpec, …
│   ├── policy                   AccessPolicy, ScopePolicy, QueryWindowPolicy, IdempotencyPolicy,
│   │                            CanonicalJson (RFC 8785), CursorCodec, SelfEventFactory
│   ├── port.in                  RecordEventsPort, ListEventsPort, GetEventPort
│   ├── port.out                 AuditEventRepositoryPort, EventQueryPort, SelfAuditPort, TenantDirectoryPort,
│   │                            RateLimiterPort, TokenVerifier, ColdStoragePort, ArchiveRepositoryPort, …
│   └── exception                jerarquía sellada AuditDomainException
├── application.workflow         RecordEventsWorkflow, ListEventsWorkflow, GetEventWorkflow, QueryAccessGuard,
│                                QueryRateLimiter, ArchiveEventsWorkflow, RecoverArchiveWorkflow,
│                                ProvisionTenantWorkflow, ArchiveFormat
└── infrastructure
    ├── web                      HostPolicyFilter, BearerAuthenticationFilter, TenantResolutionFilter,
    │                            RateLimitFilter, NoStoreFilter, controllers, AuditExceptionHandler
    ├── security                 NimbusTokenVerifier, CallerClaims
    ├── persistence              JdbcPool + SearchPath (un pool por rol), JdbcEventStore, JdbcEventQuery,
    │                            SelfAuditJdbc, TenantDirectoryJdbc, RateLimiterJdbc, ArchiveJdbc
    ├── catalogue                ClasspathCatalogue, CatalogueYamlParser
    ├── storage                  ColdStorageS3 (Object Lock Compliance), FileSystemColdStorage (solo local)
    ├── archive                  SchemaMigrator (Flyway), ArchiveWorker, runners one-shot
    └── config                   AuditProperties, AuditRole, configuración por rol
```

Un único binario; `audit.role` decide qué arranca (diseño §3.2): `writer`, `reader`, `archiver`, `provisioner`
y `all` (solo local y CI). Cada rol abre solo su pool, con su rol de base de datos (AD-10). `RoleContextIT` lo
comprueba.

## 2. Decisiones tomadas en la implementación

| Id | Decisión | Origen |
|----|----------|--------|
| D-1 | **Spring MVC servlet** (AD-1), con hilos virtuales. | Confirmado por el líder técnico. |
| D-2 | **Formato del token** igual que el que consume el Issuer (`PolicyContextFactory`): claims de primer nivel `power[]` (`type`, `domain`, `function`, `action` string o lista, alias `tmf_*`), `mandator.organizationIdentifier`, `mandatee`, `tenant` y `credential_type`. Si no hay `power` de primer nivel se lee `vc.credentialSubject.mandate.power`. **M2M** = `credential_type` `learcredential.machine.*` o scope `machine`; el resto es **H2M**. Organización de quien consulta = `mandator.organizationIdentifier` (ámbito `domain` y cupo por organización). Firma contra el JWKS del Verifier (Nimbus, caché y refresco por `kid`), `iss` contra `audit.security.trusted-issuers` (plantilla con `{tenant}`), `exp`/`nbf` con 60 s de margen. | Indicación del líder técnico: seguir la estructura del Issuer. |
| D-3 | **JdbcClient también en la lectura**, sin JPA/Hibernate. Lectura de un tenant con `set_config('search_path', …, true)` en la transacción; `tenant=all` recorre `platform` + `tenant_directory` y funde por `(occurred_at, event_id, esquema)`. | Propuesta aprobada (pregunta 7). |
| D-4 | **Condiciones `required` no evaluables = campo opcional.** Las evaluables (`==`, `!=`, `in`, `not in`, `and`, `or` sobre campos del evento) se evalúan; cuando no se cumplen, el campo es opcional (no prohibido). Tipos `object` sin subesquema aceptan cualquier objeto. | Propuesta aprobada (pregunta 4). |
| D-5 | **Varias versiones del catálogo**: cada versión publicada es un YAML inmutable en `classpath:catalogue/`, y cada evento se valida con la versión exacta que lleva. Hoy solo existe la **2.2.0**; AC-18 se prueba con *fixtures* 2.0.0 y 2.1.0 de test (`src/test/resources/catalogue-test/`). Para publicar otra versión basta con añadir su fichero. | Pregunta 2: no está claro si existen 2.0.0/2.1.0; la idea es aceptar varias. |
| D-6 | **`event_id` de `AuditEventRejected` determinista** (UUIDv5 de la huella del evento recibido): un reenvío del lote no deja un segundo rechazo. El productor vuelve a recibir `rejected`. | Propuesta aprobada (pregunta 6). |
| D-7 | Comparación tenant ↔ dominio del poder **sin distinguir mayúsculas** (`red-logistica` ↔ `RED-LOGISTICA`). | Confirmado (pregunta 5). |
| D-8 | **Tenant de plataforma = `platform`**, el mismo identificador que usa el Issuer (`TENANT_TYPE_PLATFORM`, host `platform-<env>`). El administrador (`organization · EUDISTACK · System · Administration`) solo elige `tenant` desde ese host; sin `tenant`, ve `platform`. | Pregunta 5 sin respuesta: alternativa alineada con el Issuer. |
| D-9 | **`tenant_directory` lo rellena el perfil `provisioner`** (`audit.provisioner.tenants`), en local con el servicio `audit-provisioner` de `compose.yaml` y en tests con `AuditDatabase`. El esquema se deriva del identificador: minúsculas, `-` → `_` (`red-logistica` → `red_logistica`); `platform`, `all`, `evidence`, `public` y `pg_*` están reservados. La fila del directorio se escribe tras un Flyway correcto, nunca antes. | Pregunta 5 sin respuesta: alternativa según AD-3 / ADR-040. |
| D-10 | El host se traduce a tenant como en el Issuer: cabecera `X-Tenant-Id` de un ingress de confianza o primer label del host sin sufijo de entorno (`sandbox-stg` → `sandbox`). Se sirven `/api/v1/events` y `/audit/api/v1/events` mientras PO-10 (prefijo del ingress) siga abierto. | Diseño §3.4.1 + PO-10. |
| D-11 | **Firma KMS del manifiesto fuera de alcance.** El manifiesto lleva recuento, huella SHA-256 del objeto e identificadores; `archive_hash` es la huella del manifiesto. | Respuesta a la pregunta 9. |
| D-12 | Eventos propios (§3.4.8) implementados tal cual: `source` `audit-api`/`audit-archiver`, actor `System`, recursos `audit-event:{id}`, `audit-write`, `audit-query:list|get`, `audit-archive:{tenant}:{día}`, `correlation_id` = recibido o `trace_id`. `AuditTrailQueried.details.queried_scope` = tenant, `tenant/organización` (domain), `platform` o `all`. `SelfEventFactoryTest` valida todos contra el catálogo. | Respuesta a la pregunta 11. |
| D-13 | **Escrituras y lecturas exclusivas**: un token con cualquier poder `Audit · Write` no puede leer y uno con poder de lectura no puede escribir. | FR-02 / PRD §1. |
| D-14 | **Recuperación de un periodo** (AC-22) como tarea one-shot del rol `archiver` (`audit.recover.*`, token del administrador). Deja `AuditTrailQueried` en el tenant recuperado, por lo que `audit_archiver` tiene `INSERT` en las tablas de tenant, limitado por el trigger a `AuditTrailQueried`/`AuditEventsArchived`. | Diseño §3.4.6, ampliación mínima de los GRANT de §3.4.2. |
| D-15 | Un día archivado que recibe eventos tarde genera una **parte nueva** (`events-N.jsonl.gz` + `manifest-N.json`): un objeto bloqueado nunca se reescribe. | Completa la §3.4.6. |
| D-16 | `audit.security.audience` **configurable y desactivado por defecto** hasta que el Verifier emita una audiencia para este servicio (PO-12). Con valor, se exige (`TokenAudienceIT`). | Pregunta 3. |

## 3. Cobertura de los criterios de aceptación

| AC | Dónde se prueba |
|----|-----------------|
| AC-01, AC-02, AC-03, AC-04, AC-18 | `RecordEventsIT`, `RecordEventsWorkflowTest`, `IdempotencyRaceIT`, `CatalogueValidatorTest` |
| AC-05 (parte del servicio: 503 + `Retry-After`, nada guardado) | `RecordEventsUnavailableIT`, `RecordEventsWorkflowTest` |
| AC-06, AC-09, AC-11, AC-13, AC-14, AC-15 | `ListEventsIT`, `ScopePolicyTest`, `QueryWindowPolicyTest` |
| AC-07, AC-08, AC-10, AC-12 | `AccessControlIT`, `DbRoleGrantsIT` |
| AC-16 | `RateLimitIT` |
| AC-17 | `CatalogueValidatorTest.NoSecretsNorAttestation` |
| AC-19 (contrato del prefijo `evidence/`; el detector no es de este servicio) | `EvidencePrefixPolicyTest` |
| AC-20, AC-21, AC-22 | `ArchiveEventsWorkflowIT`, `ArchiveEventsWorkflowTest` |
| FR-01 (contrato HTTP) | `OpenApiContractIT` |

Fuera de este servicio (dependencias del PRD): la bandeja de salida de los productores (AC-05 completo, DT-002),
el detector pgaudit que escribe `EvidenceStoreAccess` (AC-19), el bucket con Object Lock, la base `audit`, los roles
de base de datos y su cifrado (IaC).

## 4. Hallazgos sobre el contrato y puntos abiertos

| Id | Hallazgo | Propuesta |
|----|----------|-----------|
| H-1 | En `openapi.yaml`, `AuditEvent` es `allOf: [AuditEventInput, {recorded_at}]` y `AuditEventInput` declara `additionalProperties: false`: en JSON Schema estricto **ningún evento con `recorded_at` es válido**. | Mover `additionalProperties: false` a `AuditEvent` o usar `unevaluatedProperties: false`. `OpenApiContractIT` valida el evento sin `recorded_at` contra `AuditEventInput` mientras tanto. |
| H-2 | El OpenAPI no declara las respuestas **405** (que exige AC-08 y la §3.9) ni la cabecera `Cache-Control: no-store` (PO-13). | Añadirlas al contrato. |
| H-3 | `GET /api/v1/events/{event_id}` no declara `tenant`: el administrador no puede elegir ámbito. Se acepta `tenant` también ahí (mismo significado que en el listado). | Añadir el parámetro al contrato. |
| H-4 | El ejemplo del OpenAPI usa `event_version: 2.0.0`, que no está publicada. | Ver D-5. |
| PO-12 | El Verifier no emite `aud` para este servicio. | D-16. |
| PO-5, PO-8, PO-9, PO-10, PO-16 | Siguen abiertos en el diseño (infraestructura/PM). | Sin cambio en el código. |

## 5. Riesgos conocidos (revisión de código)

| Id | Riesgo | Propuesta |
|----|--------|-----------|
| R-1 | Sin `aud` (PO-12) y sin comprobar `typ`, cualquier JWT firmado por el Verifier con `power` sirve como token de acceso (por ejemplo, un `id_token`). | Activar `AUDIT_SECURITY_AUDIENCE` en cuanto el Verifier emita la audiencia, o exigir `typ: at+jwt` si el Verifier lo pone. |
| R-2 | `JdbcPool` convierte cualquier `DataAccessException` en 503 + `Retry-After`. Un error no transitorio de la base de datos que la validación no prevea haría que el productor reintentara el lote sin fin. Los casos conocidos (U+0000, años fuera de rango) ya se rechazan antes. | Vigilar `audit.batch.latency{outcome=unavailable}`; si aparece un caso nuevo, rechazarlo en la validación. |
| R-3 | El cuerpo del `POST` de un llamante autenticado no tiene límite de tamaño en la aplicación; `audit.web.max-body-bytes` solo acota la lectura para `AuditWriteDenied`. | Limitar el tamaño en el ingress interno (PO-9), o exigir `Content-Length` ≤ `max-body-bytes` en `HostPolicyFilter`. |
| R-4 | `X-Tenant-Id` se acepta de quien llegue: depende de que el ingress la sobrescriba siempre. El impacto está acotado, porque el tenant del token debe coincidir con el de la petición. | Confirmarlo con arquitectura junto con PO-10. |
| R-5 | Si la base de datos cae a mitad del archivado, la ejecución se corta y los tenants que faltaban esperan al día siguiente. No se pierde nada: lo copiado se reutiliza en la siguiente ejecución. | Aceptable; la alarma de `audit.archive.lag` lo detecta. |
