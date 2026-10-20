# Probar la API en local, a mano

Guía para recorrer los casos de uso de la API contra el stack de `compose.yaml`, sin el Verifier ni el
resto de la plataforma. Los tests automáticos (`./gradlew build`) ya cubren lo mismo. Esta guía sirve
para verlo con tus propios ojos.

Requisitos: Docker Desktop arrancado y una terminal **Git Bash** (o Linux/macOS) en la raíz del repo.
En PowerShell las comillas y los acentos del JSON dan problemas: usa Git Bash.

## 1. Preparar

```bash
./gradlew devTokens                     # clave local + tokens de cada perfil (12 h) en dev-tools/local-tokens/
docker compose up --build -d            # PostgreSQL + provisioner (sandbox, red-logistica) + API (rol all)
curl -s localhost:8091/actuator/health  # {"status":"UP"} cuando está lista
```

`compose.yaml` lee `dev-tools/local-tokens/audit-local.env`, así que la API confía en la clave local. Si
regeneras los tokens con otra clave (por ejemplo, tras borrar la carpeta), reinicia la API con
`docker compose up -d --force-recreate audit-api`.

Variables de ayuda para el resto de la guía:

```bash
API=http://localhost:8090
T=dev-tools/local-tokens
W="Host: audit.internal.localhost"      # cara de escritura (host interno)
R="Host: red-logistica.localhost"       # cara de consulta del tenant red-logistica
S="Host: sandbox.localhost"             # cara de consulta del tenant sandbox
P="Host: platform.localhost"            # cara de consulta del tenant de plataforma (administrador)
auth() { echo "Authorization: Bearer $(cat $T/$1.jwt)"; }
```

Tokens disponibles (`$T/*.jwt`):

| Fichero | Quién | Poder |
|---------|-------|-------|
| `writer` | Sistema (M2M) de plataforma | `organization · EUDISTACK · Audit · Write` |
| `auditor-red-logistica` | Persona de red-logistica | `organization · RED-LOGISTICA · Audit · Read` |
| `domain-auditor-red-logistica` | Persona de la organización `VATES-A11111111` | `domain · RED-LOGISTICA · Audit · Read` |
| `auditor-sandbox` | Persona de sandbox | `organization · SANDBOX · Audit · Read` |
| `system-reader-sandbox` | Sistema (M2M) de sandbox | `organization · SANDBOX · Audit · Read` |
| `admin` | Administrador de plataforma | `organization · EUDISTACK · System · Administration` |

`dev-tools/sample-event.sh <tenant> [correlation_id] [holder]` imprime un `IssuanceCompleted` válido con un
`event_id` nuevo y `occurred_at` = ahora. Con `ORGANIZATION=...` delante cambia la organización del evento.
Envía siempre el cuerpo desde un fichero (`--data-binary @fichero`): si lo pasas en línea, Git Bash puede
estropear los acentos y la API responde 400.

## 2. Escritura (`POST /api/v1/events`, host interno)

```bash
# AC-01: un lote con eventos de dos tenants → accepted, cada uno en su esquema
echo "{\"events\":[$(dev-tools/sample-event.sh red-logistica op-001 11111111A),$(dev-tools/sample-event.sh sandbox op-001 22222222B)]}" > batch.json
curl -s -H "$W" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events

# AC-03: reenviar el mismo lote → duplicate; mismo event_id con otro contenido → event_id_conflict
curl -s -H "$W" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events
sed 's/issuance:op-001/issuance:otro/' batch.json > conflict.json
curl -s -H "$W" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @conflict.json $API/api/v1/events

# AC-02 y AC-04: campo no declarado → invalid_event (con el campo); tenant inexistente → unknown_tenant
BAD=$(dev-tools/sample-event.sh red-logistica op-002 | sed 's/"accepted_by_holder": true/"accepted_by_holder": true, "holder_phone": "600000000"/')
echo "{\"events\":[$BAD,$(dev-tools/sample-event.sh atlantis op-002)]}" > bad.json
curl -s -H "$W" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @bad.json $API/api/v1/events

# Evento de otra organización, para probar el ámbito domain más abajo
echo "{\"events\":[$(ORGANIZATION=VATES-A11111111 dev-tools/sample-event.sh red-logistica op-003)]}" > acme.json
curl -s -H "$W" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @acme.json $API/api/v1/events
```

Acceso a la escritura:

```bash
# Sin token → 401 (deja AuditWriteDenied/InvalidToken); token de lectura escribiendo → 403 (AC-07)
curl -s -o /dev/null -w '%{http_code}\n' -H "$W" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events
curl -s -o /dev/null -w '%{http_code}\n' -H "$W" -H "$(auth auditor-red-logistica)" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events

# Navegador (cabecera Origin) en el host interno → 403
curl -s -o /dev/null -w '%{http_code}\n' -H "$W" -H "Origin: https://web.example" -H "$(auth writer)" -H 'Content-Type: application/json' --data-binary @batch.json $API/api/v1/events

# AC-08: nada modifica ni borra un evento, y las caras no se cruzan → 405
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "$R" -H "$(auth auditor-red-logistica)" $API/audit/api/v1/events/00000000-0000-4000-a000-000000000000
curl -s -o /dev/null -w '%{http_code}\n' -H "$W" -H "$(auth writer)" $API/api/v1/events
```

## 3. Consulta (`GET /audit/api/v1/events`, host del tenant)

```bash
# AC-06: todos los eventos de una operación por correlation_id
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?correlation_id=op-001"

# AC-09: organization ve todo el tenant; domain solo su organización (VATES-A11111111)
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events"
curl -s -H "$R" -H "$(auth domain-auditor-red-logistica)" "$API/audit/api/v1/events"

# PRD §5: el tenant del host no es el del token → 403 tenant_mismatch
curl -s -H "$S" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events"

# AC-10: un evento de otro tenant o inexistente → 404 event_not_found (mismo error en los dos casos)
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events/00000000-0000-4000-a000-000000000000"

# AC-11: el administrador, desde el host de plataforma, ve un tenant, platform o todos
curl -s -H "$P" -H "$(auth admin)" "$API/audit/api/v1/events?tenant=all&correlation_id=op-001"
curl -s -H "$P" -H "$(auth admin)" "$API/audit/api/v1/events?tenant=platform"   # rechazos, denegaciones…

# AC-13 y AC-14: búsqueda exacta por una ruta searchable (exige event_type y from)
FROM=$(date -u -d '-1 day' +%Y-%m-%dT%H:%M:%SZ)
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?event_type=IssuanceCompleted&details_path=holder.identifier&details_value=11111111A&from=$FROM"
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?event_type=IssuanceCompleted&details_path=valid_from&details_value=x&from=$FROM"  # path_not_searchable

# AC-15: más de 90 días → 409 period_archived; un periodo de más de 31 días → 400
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?from=2020-01-01T00:00:00Z&to=2020-01-10T00:00:00Z"
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?from=$(date -u -d '-40 day' +%Y-%m-%dT%H:%M:%SZ)"

# Paginación: limit + next_cursor
curl -s -H "$R" -H "$(auth auditor-red-logistica)" "$API/audit/api/v1/events?limit=1"
#   …y repetir con &cursor=<next_cursor> (con otros filtros el cursor da 400)

# FR-18 / AC-16: una persona hace 30 consultas por minuto; la 31.ª → 429 con Retry-After (un sistema, 60)
for i in $(seq 1 32); do curl -s -o /dev/null -w '%{http_code} ' -H "$S" -H "$(auth auditor-sandbox)" "$API/audit/api/v1/events?limit=1"; done; echo
```

## 4. Ver lo que queda en la base de datos

```bash
psql_audit() { docker compose exec -T audit-db psql -U postgres -d audit -c "$1"; }

# Rechazos, denegaciones y consultas registradas por la propia API
psql_audit "SELECT event_type, reason, count(*) FROM platform.audit_event GROUP BY 1,2 ORDER BY 1,2;"
# AC-12: cada consulta, concedida o denegada, deja un AuditTrailQueried en su tenant
psql_audit "SELECT event_type, organization, correlation_id, recorded_at FROM red_logistica.audit_event ORDER BY recorded_at;"
psql_audit "SELECT * FROM platform.tenant_directory;"

# La tabla es append-only, incluso para el superusuario: UPDATE y TRUNCATE se rechazan,
# y DELETE solo lo hace el rol del archivador
psql_audit "UPDATE red_logistica.audit_event SET result = 'Failure';"
psql_audit "DELETE FROM red_logistica.audit_event;"
```

## 5. Archivado (opcional, AC-20)

El archivador solo mueve eventos con más de 90 días. Para verlo en local, copia un evento con fecha
antigua (como superusuario, fuera de la API) y lanza una ejecución única con el rol `archiver`:

```bash
psql_audit "INSERT INTO red_logistica.audit_event (event_id, event_type, event_version, occurred_at, recorded_at, tenant, organization, source, actor, action, resource, result, correlation_id, details, payload_hash)
  SELECT gen_random_uuid(), event_type, event_version, occurred_at - interval '100 days', recorded_at - interval '100 days', tenant, organization, source, actor, action, resource, result, 'op-old', details, payload_hash
  FROM red_logistica.audit_event WHERE event_type = 'IssuanceCompleted' LIMIT 1;"

MSYS_NO_PATHCONV=1 docker compose run --rm -e AUDIT_ROLE=archiver -e AUDIT_ARCHIVE_RUNONCE=true audit-api

psql_audit "SELECT count(*) FROM red_logistica.audit_event WHERE correlation_id = 'op-old';"   # 0: archivado
psql_audit "SELECT result, details->>'archived_tenant' AS tenant, details->>'event_count' AS events, details->>'archive_ref' AS ref FROM platform.audit_event WHERE event_type = 'AuditEventsArchived';"
```

En local el almacenamiento frío es un directorio dentro del contenedor (`AUDIT_ARCHIVE_STORAGE=filesystem`) y
no tiene bloqueo real. El bloqueo Compliance de S3 solo existe en los entornos desplegados.

## 6. Limpiar

```bash
docker compose down -v                  # borra también la base de datos
rm -f batch.json conflict.json bad.json acme.json
```
