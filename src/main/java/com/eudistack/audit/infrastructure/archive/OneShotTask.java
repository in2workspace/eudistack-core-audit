package com.eudistack.audit.infrastructure.archive;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * A task that runs once at start-up and then ends the process with its exit code (provisioning, a single
 * archiving run, a recovery). {@code AuditApplication} exits when one of these is present.
 */
public interface OneShotTask extends ApplicationRunner, ExitCodeGenerator {
}
