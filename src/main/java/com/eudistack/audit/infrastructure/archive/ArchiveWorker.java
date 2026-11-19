package com.eudistack.audit.infrastructure.archive;

import com.eudistack.audit.application.workflow.ArchiveEventsWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Daily archiving at 02:00 UTC (design §3.4.6). Two workers never take the same tenant and day: the
 * lock lives in {@code platform.archive_lock}.
 */
public class ArchiveWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveWorker.class);

    private final ArchiveEventsWorkflow workflow;

    public ArchiveWorker(ArchiveEventsWorkflow workflow) {
        this.workflow = workflow;
    }

    @Scheduled(cron = "${audit.archive.cron:0 0 2 * * *}", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    /**
     * @return {@code true} when every tenant and day was archived or skipped, {@code false} on any failure
     */
    public boolean runOnce() {
        LOG.info("archiving run started");
        long started = System.nanoTime();
        try {
            List<ArchiveEventsWorkflow.DayOutcome> outcomes = workflow.run();
            long failures = outcomes.stream().filter(o -> o.status() == ArchiveEventsWorkflow.Status.FAILURE).count();
            int archived = outcomes.stream().mapToInt(ArchiveEventsWorkflow.DayOutcome::eventCount).sum();
            LOG.atInfo()
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .addKeyValue("archived", archived)
                    .addKeyValue("tenant_days", outcomes.size())
                    .addKeyValue("failures", failures)
                    .addKeyValue("result", failures == 0 ? "success" : "failure")
                    .log("archiving run finished");
            return failures == 0;
        } catch (RuntimeException e) {
            LOG.error("Archiving run failed; nothing was deleted for the tenant in progress", e);
            return false;
        }
    }
}
