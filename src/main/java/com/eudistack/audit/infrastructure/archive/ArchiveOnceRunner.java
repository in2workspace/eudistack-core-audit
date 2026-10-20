package com.eudistack.audit.infrastructure.archive;

import org.springframework.boot.ApplicationArguments;

/**
 * {@code audit.archive.run-once=true}: one archiving run and exit, for a scheduled task of the platform
 * instead of the in-process cron.
 */
public final class ArchiveOnceRunner implements OneShotTask {

    private final ArchiveWorker worker;
    private int exitCode;

    public ArchiveOnceRunner(ArchiveWorker worker) {
        this.worker = worker;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = worker.runOnce() ? 0 : 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
