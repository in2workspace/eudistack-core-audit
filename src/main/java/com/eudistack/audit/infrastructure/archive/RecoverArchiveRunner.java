package com.eudistack.audit.infrastructure.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.application.workflow.RecoverArchiveWorkflow;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.port.out.TokenVerifier;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Recovery of an archived period (AC-22): {@code audit.role=archiver} with {@code audit.recover.enabled=true}.
 * The platform administrator authenticates with the same Verifier token as on the API; the result is a file
 * in the {@code EventList} format of the API, delivered to the customer by support.
 */
public final class RecoverArchiveRunner implements OneShotTask {

    private static final Logger LOG = LoggerFactory.getLogger(RecoverArchiveRunner.class);

    private final RecoverArchiveWorkflow workflow;
    private final TokenVerifier tokens;
    private final AuditProperties.Recover recover;
    private final ObjectMapper mapper;
    private int exitCode;

    public RecoverArchiveRunner(RecoverArchiveWorkflow workflow, TokenVerifier tokens, AuditProperties.Recover recover,
                                ObjectMapper mapper) {
        this.workflow = workflow;
        this.tokens = tokens;
        this.recover = recover;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Caller caller = tokens.verify(recover.getToken());
            List<AuditEvent> events = workflow.recover(caller, recover.getTenant(), LocalDate.parse(recover.getFrom()),
                    LocalDate.parse(recover.getTo()), UUID.randomUUID().toString().replace("-", ""));
            ObjectNode body = mapper.createObjectNode();
            ArrayNode list = body.putArray("events");
            events.forEach(e -> list.add(e.toJson()));
            body.putNull("next_cursor");
            Path output = Path.of(recover.getOutput());
            Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body));
            LOG.info("Recovered {} events of {} into {}", events.size(), recover.getTenant(), output.toAbsolutePath());
        } catch (Exception e) {
            LOG.error("Recovery failed: {}", e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
