package com.eudistack.audit.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base of the integration tests: the whole service with {@code audit.role=all} on a real PostgreSQL,
 * each pool with its own database role, tokens signed with the test key and the catalogue fixtures
 * 2.0.0 and 2.1.0 published next to 2.2.0. Requests carry the real hosts of each face.
 */
@SpringBootTest(properties = {
        "audit.role=all",
        "audit.scheduling.enabled=false",
        "audit.security.trusted-issuers=" + TestTokens.ISSUER_TEMPLATE,
        "audit.query.cursor-secret=integration-test-cursor-secret-0123456789",
        "audit.query.rate-limit.system=100000",
        "audit.query.rate-limit.person=100000",
        "audit.query.rate-limit.system-search=100000",
        "audit.query.rate-limit.person-search=100000",
        "audit.catalogue.locations=classpath*:catalogue/*.yaml,classpath*:catalogue-test/*.yaml",
        "audit.archive.storage=filesystem",
        "audit.archive.filesystem-root=build/it-cold-storage",
        "management.otlp.metrics.export.enabled=false",
        "management.otlp.logging.export.enabled=false",
        "management.tracing.sampling.probability=1.0",
        "management.otlp.tracing.endpoint=http://localhost:1/v1/traces",
        "management.server.port=-1"})
@AutoConfigureMockMvc
public abstract class AuditIntegrationTest {

    public static final String WRITE_HOST = "http://audit.internal.stg.eudistack.net";
    public static final String RED_HOST = "http://red-logistica.stg.eudistack.net";
    public static final String SANDBOX_HOST = "http://sandbox.stg.eudistack.net";
    public static final String PLATFORM_HOST = "http://platform.stg.eudistack.net";

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AuditDatabase.register(registry);
        registry.add("audit.security.jwks", TestTokens::publicJwks);
    }

    protected static String correlation() {
        return "corr-" + UUID.randomUUID();
    }

    protected MockHttpServletRequestBuilder write(JsonNode body, String token) {
        MockHttpServletRequestBuilder request = post(WRITE_HOST + "/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder query(String host, String pathAndQuery, String token) {
        MockHttpServletRequestBuilder request = get(host + pathAndQuery);
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return TestEvents.MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    protected JsonNode recordOk(ObjectNode... events) throws Exception {
        MvcResult result = mvc.perform(write(TestEvents.batch(events), TestTokens.writer())).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("POST failed: " + result.getResponse().getContentAsString());
        }
        return json(result);
    }

    /**
     * Rows of a schema read as the superuser, outside the API.
     */
    protected static List<Map<String, Object>> rows(String schema, String where, Object... params) {
        var statement = AuditDatabase.superuser().sql("SELECT event_id::text AS event_id, event_type, tenant, "
                + "organization, reason, correlation_id, details::text AS details, recorded_at "
                + "FROM \"" + schema + "\".audit_event WHERE " + where);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query().listOfRows();
    }
}
