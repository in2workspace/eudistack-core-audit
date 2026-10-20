# Probar la API en local, a mano

Guía para recorrer los criterios de aceptación de EUD-244 (`prd.md` §6) contra el stack de `compose.yaml`, sin
el Verifier ni el resto de la plataforma. Los tests automáticos (`./gradlew build`) cubren lo mismo; esta guía
sirve para verlo con tus propios ojos, en la API y en la base de datos (DBeaver).

Requisitos: Docker Desktop arrancado y una terminal **Git Bash** (o Linux/macOS) en la raíz del repo.
En PowerShell las comillas y los acentos del JSON dan problemas: usa Git Bash.

## 1. Preparar

```bash
./gradlew devTokens                     # clave local + tokens de cada perfil (12 h) en dev-tools/local-tokens/
docker compose up --build -d            # PostgreSQL + provisioner (sandbox, red-logistica) + API (rol all)
curl -s localhost:8091/actuator/health  # {"status":"UP"} cuando está lista
```

Con OpenTelemetry (§8), en lugar del `up` anterior:

```bash
OTEL=true TRACING_SAMPLING_PROBABILITY=1 docker compose --profile observability up --build -d
```

`compose.yaml` lee `dev-tools/local-tokens/audit-local.env`, así que la API confía en la clave local. Si
regeneras los tokens con otra clave (por ejemplo, tras borrar la carpeta), reinicia la API con
`docker compose up -d --force-recreate audit-api`. Los tokens caducan a las 12 h: vuelve a lanzar
`./gradlew devTokens` (misma clave, no hace falta reiniciar).

Variables de ayuda para el resto de la guía:

```bash
export MSYS_NO_PATHCONV=1               # Git Bash: no convertir rutas como /app/audit.jar en rutas de Windows
API=http://localhost:8090
T=dev-tools/local-tokens
W="Host: audit.internal.localhost"      # cara de escritura (host interno)
R="Host: red-logistica.localhost"       # cara de consulta del tenant red-logistica
S="Host: sandbox.localhost"             # cara de consulta del tenant sandbox
P="Host: platform.localhost"            # cara de consulta del tenant de plataforma (administrador)
auth() { echo "Authorization: Bearer $(cat $T/$1.jwt)"; }
post() { curl -s -H "$W" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @"$1" $API/api/v1/events; echo; }
psql_audit() { docker compose exec -T audit-db psql -U postgres -d audit -c "$1"; }
```

Tokens disponibles (`$T/*.jwt`):

| Fichero | Quién | Poder |
|---------|-------|-------|
| `writer` | Sistema (M2M) de plataforma | `organization · EUDISTACK · Audit · Write` |
| `auditor-red-logistica` | Persona de red-logistica | `organization · RED-LOGISTICA · Audit · Read` |
| `domain-auditor-red-logistica` | Persona de la organización `VATES-A11111111` | `domain · RED-LOGISTICA · Audit · Read` |
| `auditor-sandbox` | Persona de sandbox | `organization · SANDBOX · Audit · Read` |
| `system-reader-sandbox` | Sistema (M2M) de sandbox, organización `VATES-B00000002` | `organization · SANDBOX · Audit · Read` |
| `admin` | Administrador de plataforma | `organization · EUDISTACK · System · Administration` |

`dev-tools/sample-event.sh <tenant> [correlation_id] [holder]` imprime un `IssuanceCompleted` válido con un
`event_id` nuevo y `occurred_at` = ahora. Con `ORGANIZATION=...` delante cambia la organización del evento.
Envía siempre el cuerpo desde un fichero (`--data-binary @fichero`): si lo pasas en línea, Git Bash puede
estropear los acentos y la API responde 400.

## 2. Base de datos con DBeaver

Nueva conexión → **PostgreSQL**:

| Campo | Valor |
|-------|-------|
| Host | `localhost` |
| Port | `5433` (no 5432, para no chocar con otro PostgreSQL local) |
| Database | `audit` |
| Authentication | Database Native |
| Username / Password | `postgres` / `postgres` (superusuario: ve todo) |

En la pestaña **PostgreSQL** marca *Show all databases* si quieres verlas todas. Los esquemas que importan:

| Esquema | Qué guarda |
|---------|------------|
| `platform` | Eventos de `tenant = platform`, `AuditEventRejected`, `AuditWriteDenied`, `AuditEventsArchived`, `AuditTrailQueried` del administrador; `tenant_directory`, `rate_window`, `archive_lock`, `flyway_schema_history` |
| `red_logistica`, `sandbox` | `audit_event` de cada tenant (el `-` del tenant pasa a `_` en el esquema) |

Para ver lo que ve cada rol de la aplicación, crea conexiones con el mismo host y estos usuarios (contraseñas
solo locales, de `dev-tools/db/init-roles.sql`): `audit_writer`/`writer`, `audit_reader`/`reader`,
`audit_archiver`/`archiver`. Por ejemplo, `audit_reader` lee pero un `UPDATE` le da *permission denied*, y
`audit_writer` solo puede leer `event_id` y `payload_hash`.

Consultas útiles (en DBeaver o con `psql_audit "…"`):

```sql
-- Últimos eventos de un tenant, con lo que pone la API
SELECT recorded_at, event_type, result, reason, organization, correlation_id, actor, details
FROM red_logistica.audit_event ORDER BY recorded_at DESC LIMIT 20;

-- Lo que la API registra de sí misma
SELECT recorded_at, event_type, reason, correlation_id, details
FROM platform.audit_event ORDER BY recorded_at DESC LIMIT 20;

-- Tenants dados de alta y su esquema
SELECT * FROM platform.tenant_directory;
```

## 3. Escritura (`POST /api/v1/events`, host interno)

```bash
# AC-01: lote válido de dos tenants → accepted; cada evento en su esquema, con recorded_at
echo "{\"events\":[$(dev-tools/sample-event.sh red-logistica op-001 12345678Z),$(dev-tools/sample-event.sh sandbox op-001 22222222B)]}" > batch.json
post batch.json
psql_audit "SELECT tenant, recorded_at FROM red_logistica.audit_event WHERE correlation_id='op-001';"

# AC-03: reenviar igual → duplicate (no se guarda otra vez); mismo event_id con otro contenido → event_id_conflict
post batch.json
sed 's/issuance:op-001/issuance:otro/' batch.json > conflict.json
post conflict.json
psql_audit "SELECT count(*) FROM red_logistica.audit_event WHERE correlation_id='op-001';"            # sigue en 1
psql_audit "SELECT reason, details->'received_event'->>'resource' FROM platform.audit_event WHERE reason='EventIdConflict';"

# AC-02: tipo desconocido + campo no declarado → unknown_event_type e invalid_event; el resto del lote se guarda
UNKNOWN=$(dev-tools/sample-event.sh red-logistica op-002 | sed 's/"IssuanceCompleted"/"NoExiste"/')
EXTRA=$(dev-tools/sample-event.sh red-logistica op-002 | sed 's/"accepted_by_holder": true/"accepted_by_holder": true, "holder_phone": "600000000"/')
echo "{\"events\":[$UNKNOWN,$EXTRA,$(dev-tools/sample-event.sh red-logistica op-002)]}" > ac02.json
post ac02.json
psql_audit "SELECT reason, details->'violations' FROM platform.audit_event WHERE correlation_id='op-002';"   # con lo recibido en details.received_event

# AC-04: tenant inexistente → unknown_tenant; evento de plataforma (tenant=platform) → se guarda en platform
echo "{\"events\":[$(dev-tools/sample-event.sh atlantis op-004),$(dev-tools/sample-event.sh platform op-004)]}" > ac04.json
post ac04.json

# AC-17: el catálogo es una lista cerrada: un token o la atestación en details → invalid_event (nunca se guarda)
echo "{\"events\":[$(dev-tools/sample-event.sh red-logistica op-017 | sed 's/"accepted_by_holder": true/"accepted_by_holder": true, "vp_token": "eyJhbGciOi..."/')]}" > ac17.json
post ac17.json

# AC-18: cada evento se valida con SU versión del catálogo. En local solo está publicada la 2.2.0, así que un
# evento 2.0.0 se rechaza como unknown_event_type; la convivencia 2.0.0 + 2.1.0 la prueban los tests con
# catálogos de prueba (RecordEventsIT.ac18_*), porque esas versiones no existen todavía (D-5).
echo "{\"events\":[$(dev-tools/sample-event.sh red-logistica op-018 | sed 's/"2.2.0"/"2.0.0"/')]}" > ac18.json
post ac18.json

# Evento de otra organización, para el ámbito domain de §4
echo "{\"events\":[$(ORGANIZATION=VATES-A11111111 dev-tools/sample-event.sh red-logistica op-003)]}" > acme.json
post acme.json
```

Acceso a la escritura:

```bash
# AC-07: token de lectura escribiendo → 403 insufficient_permission + AuditWriteDenied (PermissionMissing)
curl -s -H "$W" -H "$(auth auditor-red-logistica)" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events; echo
# Sin token → 401 authentication_error + AuditWriteDenied (InvalidToken)
curl -s -o /dev/null -w '%{http_code}\n' -H "$W" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events
psql_audit "SELECT reason, details->>'event_count' FROM platform.audit_event WHERE event_type='AuditWriteDenied';"

# Navegador (cabecera Origin) en el host interno → 403, sin evento (PO-15)
curl -s -o /dev/null -w '%{http_code}\n' -H "$W" -H "Origin: https://web.example" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events

# AC-08: no hay operación para modificar ni borrar, y las caras no se cruzan → 405
ID=$(grep -o '"event_id": "[^"]*"' batch.json | head -1 | cut -d'"' -f4)
for M in PUT PATCH DELETE; do curl -s -o /dev/null -w "$M %{http_code}\n" -X $M -H "$R" -H "$(auth auditor-red-logistica)" $API/audit/api/v1/events/$ID; done
curl -s -o /dev/null -w 'GET en host interno %{http_code}\n' -H "$W" -H "$(auth writer)" $API/api/v1/events

# AC-05 (parte del servicio): sin base de datos → 503 + Retry-After y nada guardado; al volver, el reenvío entra
echo "{\"events\":[$(dev-tools/sample-event.sh red-logistica op-005)]}" > ac05.json
docker compose stop audit-db
curl -s -i -H "$W" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @ac05.json $API/api/v1/events | grep -E "HTTP/|Retry-After"
docker compose start audit-db; sleep 5
post ac05.json      # accepted
post ac05.json      # duplicate: sin pérdidas ni duplicados
```

## 4. Consulta (`GET /audit/api/v1/events`, host del tenant)

```bash
# AC-06: todos los eventos de una operación por correlation_id
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?correlation_id=op-001"

# AC-09: organization → todo su tenant, en orden cronológico; domain → solo su organización (VATES-A11111111)
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events"
curl -s -H "$R" -H "$(auth domain-auditor-red-logistica)" "$API/audit/api/v1/events"
curl -s -H "$R" -H "$(auth domain-auditor-red-logistica)" "$API/audit/api/v1/events?organization=VATES-B00000000"   # no amplía su ámbito

# AC-10: un evento de otro tenant da 404 event_not_found, igual que uno que no existe
SANDBOX_ID=$(grep -o '"event_id": "[^"]*"' batch.json | sed -n 2p | cut -d'"' -f4)
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events/$SANDBOX_ID"; echo
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events/00000000-0000-4000-a000-000000000000"; echo

# PRD §5: el tenant del host no es el del token → 403 tenant_mismatch
curl -s -H "$S" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events"; echo

# AC-07 (lectura): token de escritura consultando → 403 insufficient_permission
curl -s -H "$R" -H "$(auth writer)" "$API/audit/api/v1/events"; echo

# AC-11: el administrador, desde el host de plataforma, ve todos los tenants y platform; su rastro queda en platform
curl -s -H "$P" -H "$(auth admin)" "$API/audit/api/v1/events?tenant=all&correlation_id=op-001"
curl -s -H "$P" -H "$(auth admin)" "$API/audit/api/v1/events?tenant=platform"      # rechazos, denegaciones…
psql_audit "SELECT details->>'queried_scope', details->>'returned_count' FROM platform.audit_event WHERE event_type='AuditTrailQueried' ORDER BY recorded_at DESC LIMIT 2;"

# AC-13: búsqueda exacta por una ruta searchable → solo los eventos de esa persona
FROM=$(date -u -d '-1 day' +%Y-%m-%dT%H:%M:%SZ)
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?event_type=IssuanceCompleted,PresentationVerification&details_path=holder.identifier&details_value=12345678Z&from=$FROM"

# AC-14: ruta no buscable → path_not_searchable; details_path sin event_type o sin from → BAD_REQUEST
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?event_type=IssuanceCompleted&details_path=valid_from&details_value=x&from=$FROM"; echo
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?details_path=holder.identifier&details_value=x&from=$FROM"; echo
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?event_type=IssuanceCompleted&details_path=holder.identifier&details_value=x"; echo

# AC-15: más de 31 días → BAD_REQUEST; hace 100 días → 409 period_archived
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?from=$(date -u -d '-40 day' +%Y-%m-%dT%H:%M:%SZ)"; echo
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?from=$(date -u -d '-100 day' +%Y-%m-%dT%H:%M:%SZ)&to=$(date -u -d '-95 day' +%Y-%m-%dT%H:%M:%SZ)"; echo

# Paginación: limit + next_cursor (con otros filtros el cursor da 400)
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?limit=1"
#   …y repetir con &cursor=<next_cursor>

# AC-16: 60 consultas por minuto y organización desde sistemas; la 61.ª → 429 rate_limited con Retry-After
#        (personas: 30; búsquedas por details_path: 20 y 10). Lanzarlo al principio de un minuto.
for i in $(seq 1 61); do curl -s -o /dev/null -w '%{http_code} ' -H "$S" -H "$(auth system-reader-sandbox)" "$API/audit/api/v1/events?limit=1"; done; echo
curl -s -i -H "$S" -H "$(auth system-reader-sandbox)" "$API/audit/api/v1/events?limit=1" | grep -E "HTTP/|Retry-After"
```

## 5. Rastro de consultas y protección de la tabla

```bash
# AC-12: cada consulta, concedida o denegada, deja un AuditTrailQueried con quién, filtros, resultado y número
psql_audit "SELECT recorded_at, actor->>'id' AS quien, result, reason, details->'filters' AS filtros, details->>'returned_count' AS n
            FROM red_logistica.audit_event WHERE event_type='AuditTrailQueried' ORDER BY recorded_at DESC LIMIT 10;"
#   (los 401, 400, 409 y 429 no dejan rastro: diseño §3.9 y AD-8)

# Append-only, incluso para el superusuario: UPDATE y TRUNCATE se rechazan; DELETE solo lo hace el archivador
psql_audit "UPDATE red_logistica.audit_event SET result = 'Failure';"
psql_audit "TRUNCATE red_logistica.audit_event;"
psql_audit "DELETE FROM red_logistica.audit_event;"
```

AC-19 (`EvidenceStoreAccess` cuando un administrador toca la base) lo escribe el detector de accesos (pgaudit),
que no es de este servicio. Lo que sí es del servicio y se ve aquí: los triggers que impiden modificar o borrar, y
que el prefijo `evidence/` del almacenamiento en frío nunca se usa como tenant (`EvidencePrefixPolicyTest`).

## 6. Archivado y recuperación (AC-20, AC-21, AC-22)

El archivador solo mueve eventos registrados hace más de 90 días. Para verlo en local, copia un evento con
fecha antigua (como superusuario, fuera de la API) y lanza el archivador dentro del contenedor de la API, que
es donde está el almacenamiento en frío local (`/tmp/cold-storage`):

```bash
psql_audit "INSERT INTO red_logistica.audit_event (event_id, event_type, event_version, occurred_at, recorded_at, tenant, organization, source, actor, action, resource, result, correlation_id, details, payload_hash)
  SELECT gen_random_uuid(), event_type, event_version, occurred_at - interval '100 days', recorded_at - interval '100 days', tenant, organization, source, actor, action, resource, result, 'op-old', details, payload_hash
  FROM red_logistica.audit_event WHERE event_type = 'IssuanceCompleted' LIMIT 1;"

# AC-20: archivado → el evento sale de la base, queda en frío con manifiesto, y un AuditEventsArchived por tenant
docker compose exec -T audit-api java -jar /app/audit.jar --audit.role=archiver --audit.archive.run-once=true
psql_audit "SELECT count(*) FROM red_logistica.audit_event WHERE correlation_id = 'op-old';"     # 0
psql_audit "SELECT result, details->>'archived_tenant' AS tenant, details->>'event_count' AS n, details->>'archive_ref' AS ref FROM platform.audit_event WHERE event_type = 'AuditEventsArchived';"
docker compose exec -T audit-api sh -c 'find /tmp/cold-storage -name "*.json" | sort; cat $(find /tmp/cold-storage/red-logistica -name manifest.json | head -1)'

# AC-22: el administrador recupera el periodo → fichero con los eventos tal como se guardaron + AuditTrailQueried
DAY=$(docker compose exec -T audit-db psql -U postgres -d audit -tAc "SELECT (now() - interval '100 days')::date" | tr -d '\r')
docker compose exec -T audit-api java -jar /app/audit.jar --audit.role=archiver --audit.recover.enabled=true \
  --audit.recover.token="$(cat $T/admin.jwt)" --audit.recover.tenant=red-logistica \
  --audit.recover.from=$DAY --audit.recover.to=$DAY --audit.recover.output=/tmp/recovered.json
docker compose exec -T audit-api cat /tmp/recovered.json
psql_audit "SELECT actor->>'id', details->>'queried_scope', details->>'returned_count' FROM red_logistica.audit_event WHERE event_type='AuditTrailQueried' ORDER BY recorded_at DESC LIMIT 1;"
```

AC-21 (nadie puede borrar ni cambiar lo archivado en siete años; a los siete años y un día ya no existe) depende
del bloqueo Compliance de S3 y de la regla de ciclo de vida del bucket, que crea el IaC. En local el
almacenamiento es un directorio sin bloqueo real. El servicio escribe cada objeto con `COMPLIANCE` y su
fecha de retención, y comprueba ese bloqueo antes de borrar nada de la base. `ArchiveEventsWorkflowIT.ac21_*`
lo prueba con un almacén que se comporta como Object Lock. En AWS se comprueba en la consola del bucket:
*Object Lock* → modo *Compliance*.

## 7. Contrato HTTP

Las respuestas siguen `docs/openapi.yaml` (copia de `annex/openapi.yaml`); `OpenApiContractIT` las valida con
su JSON Schema. Desviaciones conocidas, a corregir en el contrato: H-1 a H-5 de `docs/architecture.md` §4.
Todos los errores son `application/problem+json` con `type`, `title`, `status`, `detail` e `instance`, y todas
las respuestas llevan `Cache-Control: no-store`:

```bash
curl -s -i -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?limit=9999" | grep -iE "HTTP/|content-type|cache-control|^\{"
```

## 8. OpenTelemetry

Arranca con `OTEL=true TRACING_SAMPLING_PROBABILITY=1 docker compose --profile observability up --build -d`
(§1), haz unas cuantas peticiones de §3 y §4, y espera unos 15 segundos (los lotes de exportación):

| Señal | Dónde se ve | Qué buscar (diseño §3.10) |
|-------|-------------|----------------------------|
| Trazas | Zipkin, http://localhost:9411 → servicio `audit-all` | `http post /api/v1/events` → `audit.batch.record` → `audit.catalog.validate` y `audit.event.persist` (con `audit.status`, `audit.tenant`…); `audit.query.list`, `audit.query.get` |
| Métricas | http://localhost:8889/metrics | `audit_events_total`, `audit_batch_latency_*`, `audit_catalog_validation_*`, `audit_query_latency_*`, `audit_write_denied_total`, `audit_ratelimit_rejected_total`, `audit_archive_*` y `hikaricp_connections_*{pool="audit-writer"…}` |
| Logs | `docker compose logs otel-collector` (exportados por OTLP) y `docker compose logs audit-api` (JSON por consola) | `trace_id`, `span_id`, `service.name` |

Si envías tu propio `traceparent`, la traza de la API cuelga de la del productor:

```bash
curl -s -H "$W" -H "$(auth writer)" -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' \
     -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events
# Zipkin → buscar el trace id 4bf92f3577b34da6a3ce929d0e0e4736
```

En los entornos desplegados la API exporta a `http://otel-collector:4318` (traces, metrics y logs por OTLP HTTP,
muestreo 0,1, paso de 30 s). Los endpoints se cambian con `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_ENDPOINT`.

## 9. Matriz de criterios de aceptación

| AC | En local (esta guía) | Test automático |
|----|----------------------|-----------------|
| AC-01 | §3 | `RecordEventsIT.ac01_*` |
| AC-02 | §3 | `RecordEventsIT.ac02_*`, `CatalogueValidatorTest` |
| AC-03 | §3 | `RecordEventsIT.ac03_*`, `IdempotencyRaceIT` |
| AC-04 | §3 | `RecordEventsIT.ac04_*` |
| AC-05 | §3, parte del servicio (503, nada guardado, reenvío sin duplicados). La bandeja de salida del productor es DT-002 | `RecordEventsUnavailableIT`, `RecordEventsWorkflowTest` |
| AC-06 | §4 | `ListEventsIT.ac06_*` |
| AC-07 | §3 y §4 | `AccessControlIT.ac07_*` |
| AC-08 | §3 | `AccessControlIT.ac08_*`, `PathNormalisationIT` |
| AC-09 | §4 | `ListEventsIT.ac09_*` |
| AC-10 | §4 | `AccessControlIT.ac10_*` |
| AC-11 | §4 | `ListEventsIT.ac11_*` |
| AC-12 | §5 | `AccessControlIT.ac12_*` |
| AC-13 | §4 | `ListEventsIT.ac13_*` |
| AC-14 | §4 | `ListEventsIT.ac14_*` |
| AC-15 | §4 | `ListEventsIT.ac15_*`, `QueryWindowPolicyTest` |
| AC-16 | §4 | `RateLimitIT` |
| AC-17 | §3 | `CatalogueValidatorTest.NoSecretsNorAttestation` |
| AC-18 | §3 (solo 2.2.0 publicada) | `RecordEventsIT.ac18_*` con catálogos 2.0.0 y 2.1.0 de prueba |
| AC-19 | §5, parte del servicio. El detector es de infraestructura | `DbRoleGrantsIT`, `EvidencePrefixPolicyTest` |
| AC-20 | §6 | `ArchiveEventsWorkflowIT.ac20_*`, `ArchiveEventsWorkflowTest` |
| AC-21 | §6, solo en AWS | `ArchiveEventsWorkflowIT.ac21_*` |
| AC-22 | §6 | `ArchiveEventsWorkflowIT.ac22_*` |

## 10. Limpiar

```bash
docker compose --profile observability down -v     # borra también la base de datos
rm -f batch.json conflict.json ac0*.json ac1*.json acme.json
```
