package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.policy.CanonicalJson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Layout of the cold storage (design §3.4.6).
 *
 * <pre>
 * {tenant}/{yyyy}/{mm}/{dd}/events.jsonl.gz      one event per line, in the API format, with recorded_at
 * {tenant}/{yyyy}/{mm}/{dd}/manifest.json        tenant, day, count, hash of the events object, event ids
 * {tenant}/{yyyy}/{mm}/{dd}/events-N.jsonl.gz    later parts, for events recorded late for an archived day
 * {tenant}/{yyyy}/{mm}/{dd}/manifest-N.json
 * </pre>
 *
 * <p>Keys are deterministic and an object never mixes tenants. Both files are canonical JSON, so the
 * same events always produce the same bytes and the same hash.
 */
public final class ArchiveFormat {

    public static final String FORMAT = "audit-event-jsonl/1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArchiveFormat() {
    }

    public record Manifest(String tenant, LocalDate day, int part, int eventCount, String eventsKey,
                           String eventsHash, List<UUID> eventIds, Instant createdAt, Instant retainUntil) {

        public Manifest {
            eventIds = List.copyOf(eventIds);
        }
    }

    public static String dayPrefix(String tenantId, LocalDate day) {
        return String.format("%s/%04d/%02d/%02d/", tenantId, day.getYear(), day.getMonthValue(), day.getDayOfMonth());
    }

    public static String eventsKey(String tenantId, LocalDate day, int part) {
        return dayPrefix(tenantId, day) + (part == 0 ? "events.jsonl.gz" : "events-" + part + ".jsonl.gz");
    }

    public static String manifestKey(String tenantId, LocalDate day, int part) {
        return dayPrefix(tenantId, day) + (part == 0 ? "manifest.json" : "manifest-" + part + ".json");
    }

    public static boolean isManifestKey(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        return name.startsWith("manifest") && name.endsWith(".json");
    }

    public static byte[] eventsToGzip(List<AuditEvent> events) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (AuditEvent event : events) {
                gzip.write(CanonicalJson.canonicalize(event.toJson()).getBytes(StandardCharsets.UTF_8));
                gzip.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static List<AuditEvent> eventsFromGzip(byte[] content) {
        List<AuditEvent> events = new ArrayList<>();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
            String text = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    events.add(AuditEvent.fromStoredJson(MAPPER.readTree(line)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return events;
    }

    public static byte[] manifestToBytes(Manifest manifest) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("format", FORMAT);
        node.put("tenant", manifest.tenant());
        node.put("day", manifest.day().toString());
        node.put("part", manifest.part());
        node.put("event_count", manifest.eventCount());
        if (manifest.eventsKey() != null) {
            node.put("events_object", manifest.eventsKey());
            node.put("events_hash", manifest.eventsHash());
        }
        ArrayNode ids = node.putArray("event_ids");
        manifest.eventIds().forEach(id -> ids.add(id.toString()));
        node.put("created_at", manifest.createdAt().toString());
        node.put("retain_until", manifest.retainUntil().toString());
        return CanonicalJson.canonicalize(node).getBytes(StandardCharsets.UTF_8);
    }

    public static Manifest manifestFromBytes(byte[] content) {
        try {
            JsonNode node = MAPPER.readTree(content);
            List<UUID> ids = new ArrayList<>();
            node.get("event_ids").forEach(id -> ids.add(UUID.fromString(id.asText())));
            return new Manifest(
                    node.get("tenant").asText(),
                    LocalDate.parse(node.get("day").asText()),
                    node.get("part").asInt(),
                    node.get("event_count").asInt(),
                    node.hasNonNull("events_object") ? node.get("events_object").asText() : null,
                    node.hasNonNull("events_hash") ? node.get("events_hash").asText() : null,
                    ids,
                    Instant.parse(node.get("created_at").asText()),
                    Instant.parse(node.get("retain_until").asText()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
