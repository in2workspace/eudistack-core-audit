package com.eudistack.audit.infrastructure.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eudistack.audit.application.workflow.RecoverArchiveWorkflow;
import com.eudistack.audit.domain.exception.InvalidTokenException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.port.out.TokenVerifier;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("RecoverArchiveRunner")
class RecoverArchiveRunnerTest {

    private static final Caller ADMINISTRATOR = new Caller("platform-admin", ActorKind.PERSON, null, null,
            "VATES-EUDISTACK", "platform", null, List.of());

    private final List<String> hostTenants = new ArrayList<>();

    private AuditProperties.Recover recover(Path output) {
        AuditProperties.Recover recover = new AuditProperties.Recover();
        recover.setToken("administrator-token");
        recover.setTenant("red-logistica");
        recover.setFrom("2026-10-01");
        recover.setTo("2026-10-02");
        recover.setOutput(output.toString());
        return recover;
    }

    @Test
    @DisplayName("run_administratorToken_isVerifiedWithoutATenantHostAndTheRecoveryIsWritten")
    void run_administratorToken_isVerifiedWithoutATenantHostAndTheRecoveryIsWritten(@TempDir Path directory) {
        // Arrange
        TokenVerifier accepting = (token, hostTenant) -> {
            hostTenants.add(hostTenant);
            return ADMINISTRATOR;
        };
        Path output = directory.resolve("recovered.json");
        RecoverArchiveRunner runner = new RecoverArchiveRunner(mock(RecoverArchiveWorkflow.class), accepting,
                recover(output), new ObjectMapper());

        // Act
        runner.run(null);

        // Assert
        assertThat(hostTenants).singleElement().isNull();
        assertThat(runner.getExitCode()).isZero();
        assertThat(Files.exists(output)).isTrue();
    }

    @Test
    @DisplayName("run_tokenRefused_leavesExitCodeOne")
    void run_tokenRefused_leavesExitCodeOne(@TempDir Path directory) {
        // Arrange
        TokenVerifier refusing = (token, hostTenant) -> {
            hostTenants.add(hostTenant);
            throw new InvalidTokenException("The access token is not valid");
        };
        RecoverArchiveRunner runner = new RecoverArchiveRunner(mock(RecoverArchiveWorkflow.class), refusing,
                recover(directory.resolve("recovered.json")), new ObjectMapper());

        // Act
        runner.run(null);

        // Assert
        assertThat(hostTenants).singleElement().isNull();
        assertThat(runner.getExitCode()).isEqualTo(1);
    }
}
