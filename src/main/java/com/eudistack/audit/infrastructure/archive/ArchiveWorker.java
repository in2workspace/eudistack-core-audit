package com.eudistack.audit.infrastructure.archive;

import com.eudistack.audit.application.workflow.ArchiveEventsWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

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
        try {
            List<ArchiveEventsWorkflow.DayOutcome> outcomes = workflow.run();
            long failures = outcomes.stream().filter(o -> o.status() == ArchiveEventsWorkflow.Status.FAILURE).count();
            LOG.info("Archiving run finished: {} tenant-days, {} failures", outcomes.size(), failures);
            return failures == 0;
        } catch (RuntimeException e) {
            LOG.error("Archiving run failed; nothing was deleted for the tenant in progress", e);
            return false;
        }
    }
}
