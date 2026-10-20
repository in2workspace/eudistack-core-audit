package com.eudistack.audit.infrastructure.web;

import com.eudistack.audit.support.AuditDatabase;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filters see the same path the dispatcher routes. Runs on the real servlet container, not MockMvc:
 * Tomcat decodes {@code %XX} and drops {@code ;params} before routing, so a filter that matched the raw
 * request URI could be skipped while the controller still answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "audit.role=all",
        "audit.scheduling.enabled=false",
        "audit.security.trusted-issuers=" + TestTokens.ISSUER_TEMPLATE,
        "audit.query.cursor-secret=integration-test-cursor-secret-0123456789",
        "audit.catalogue.locations=classpath*:catalogue/*.yaml,classpath*:catalogue-test/*.yaml",
        "audit.archive.storage=filesystem",
        "audit.archive.filesystem-root=build/it-cold-storage",
        "management.otlp.metrics.export.enabled=false",
        "management.otlp.tracing.endpoint=http://localhost:1/v1/traces",
        "management.server.port=-1"})
class PathNormalisationIT {

    private static final String WRITE_HOST = "audit.internal.stg.eudistack.net";
    private static final String RED_HOST = "red-logistica.stg.eudistack.net";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AuditDatabase.register(registry);
        registry.add("audit.security.jwks", TestTokens::publicJwks);
    }

    @Test
    void encodedCollectionPath_onTheReadFace_stillRequiresAToken() throws IOException {
        assertThat(status("GET", RED_HOST, "/audit/api/v1/%65vents", null, null)).isEqualTo(401);
        assertThat(status("GET", RED_HOST, "/api/v1/events;x=1", null, null)).isEqualTo(401);
    }

    @Test
    void encodedCollectionPath_onTheWriteFace_stillRequiresAToken() throws IOException {
        String body = TestEvents.batch(TestEvents.issuanceCompleted("sandbox", "corr-" + UUID.randomUUID(), "1"))
                .toString();
        assertThat(status("POST", WRITE_HOST, "/api/v1/%65vents", null, body)).isEqualTo(401);
        assertThat(status("POST", WRITE_HOST, "/api/v1/events;x=1", null, body)).isEqualTo(401);
    }

    @Test
    void encodedPath_keepsTheHostPolicy() throws IOException {
        String reader = TestTokens.tenantAuditor("red-logistica");
        assertThat(status("GET", WRITE_HOST, "/api/v1/%65vents", reader, null)).isEqualTo(405);
        assertThat(status("DELETE", RED_HOST, "/audit/api/v1/%65vents/" + UUID.randomUUID(), reader, null))
                .isEqualTo(405);
    }

    /**
     * Raw HTTP/1.1 so the request target and the {@code Host} header go out exactly as written.
     */
    private int status(String method, String host, String target, String token, String body) throws IOException {
        byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Connection: close\r\n");
        if (token != null) {
            request.append("Authorization: Bearer ").append(token).append("\r\n");
        }
        if (body != null) {
            request.append("Content-Type: application/json\r\n");
        }
        request.append("Content-Length: ").append(content.length).append("\r\n\r\n");
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(content);
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                line.write(b);
            }
            String statusLine = line.toString(StandardCharsets.US_ASCII);
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
